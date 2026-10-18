package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.AudioManager
import android.media.MediaFormat
import android.provider.Settings
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {

    // the service builds this before its context is attached: resolve lazily
    private val appCtx by lazy { ctx.applicationContext }

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    // decode straight into the display surface: no GL copy, one less frame of latency, and the
    // hardware composer scales the full-size frame instead of the GPU drawing it at window size
    var directOutput = true
    private var direct = false
    // tunneled playback: the decoder feeds the TV's hardware video plane at panel resolution instead
    // of the 1280x720 UI layer. Needs the display surface before the first frame, so frames from the
    // session's keyframe are held until it exists. Off: adb shell settings put global airplay_tunnel 0
    private var tunnelActive = false
    private var tunnelSurface: Surface? = null
    private var audioSessionId = 0
    private val held = ArrayList<Pair<ByteArray, Long>>()
    private var clock: SilentClock? = null
    private var heldH265 = false

    private fun _tunnelWanted() = directOutput &&
        Settings.Global.getString(appCtx.contentResolver, TUNNEL_SETTING) != "0"
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec; decoder renders into the display (direct) or the pipeline's own surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        if (codec == null && held.isNotEmpty() && surface.isValid) {
            _startAndReplay(allowTunnel = true)
            return@synchronized
        }
        if (tunnelActive) {
            // the same surface is often handed over twice; a tunnel can't move to a different one,
            // so then resume from the next keyframe
            if (surface !== tunnelSurface) stopCodec()
            return@synchronized
        }
        val c = codec
        // the display usually arrives after the session's codec has started: move the running decoder
        // onto it (no restart, so no wait for a keyframe)
        if (c != null && directOutput && surface.isValid) {
            if (!direct) pipeline.detachDisplaySync()
            try {
                c.setOutputSurface(surface)
                direct = true
                Log.i(TAG, "Decoder output moved to the display, direct")
                return@synchronized
            } catch (e: Exception) {
                Log.w(TAG, "setOutputSurface failed, using the GL pipeline", e)
                if (direct) pipeline.inputSurface?.let { s -> runCatching { c.setOutputSurface(s) } }
                direct = false
            }
        }
        if (!direct) pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        if (tunnelActive) {
            stopCodec()
            return@synchronized
        }
        if (direct) {
            // park the decoder on the pipeline's surface until a display comes back
            pipeline.inputSurface?.let { s -> runCatching { codec?.setOutputSurface(s) } }
            direct = false
        }
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265) selector.hevc(avcDecoder, w, h, fps) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) { _resetStats() }

    fun stopSession() = synchronized(lock) {
        held.clear()
        stopCodec()
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            _updateStats(data.size)
            if (tunnelActive) clock?.onFrame(ntpTimeNs / 1000, System.nanoTime())
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null && held.isNotEmpty()) {
                held.add(data to ntpTimeNs)
                if (held.size < HOLD_MAX) return
                Log.i(TAG, "No display after ${held.size} frames; starting without the tunnel")
                _startAndReplay(allowTunnel = false)
                return
            }

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
                if (_tunnelWanted() && displaySurface?.isValid != true) {
                    held.add(data to ntpTimeNs)
                    heldH265 = isH265
                    Log.i(TAG, "Holding frames until the display surface exists (tunneled playback)")
                    return
                }
            }

            try {
                if (codec == null) {
                    startCodec(isH265)
                    if (tunnelActive) _startClock()
                }
                _feedToCodec(data, ntpTimeNs)
                drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _startAndReplay(allowTunnel: Boolean) {
        val frames = ArrayList(held)
        held.clear()
        try {
            startCodec(heldH265, allowTunnel)
            // the clock follows live arrivals, so the held backlog decodes at once without being shown
            if (tunnelActive) _startClock()
            for ((d, t) in frames) _feedToCodec(d, t)
            drainOutput()
        } catch (e: Exception) {
            Log.w(TAG, "Codec error, resetting", e)
            stopCodec()
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        // a dropped frame corrupts everything up to the next keyframe, and mirroring only sends one on
        // connect: in a tunnel, frames wait for the clock instead, so wait for them
        val retries = if (!firstFrameQueued || tunnelActive) FIRST_FEED_RETRIES else FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
                firstFrameQueued = true
                return
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean, allowTunnel: Boolean = true) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val viaPipeline = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        val display = displaySurface?.takeIf { directOutput && it.isValid }
        if (directOutput && display == null) Log.i(TAG, "No display surface yet; decoding via the GL pipeline until one arrives")
        if (display != null) {
            pipeline.detachDisplaySync()
            if (allowTunnel && _tunnelWanted() && _canTunnel(info, mime)) {
                try {
                    _startTunneled(info, mime, display, h265)
                    direct = true
                    tunnelActive = true
                    tunnelSurface = display
                    Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName), tunneled, audio session $audioSessionId")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Tunneled playback failed, decoding direct", e)
                }
            }
            try {
                _startWithLadder(info, mime, display, h265)
                direct = true
                Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName), direct")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct output failed, using the GL pipeline", e)
                pipeline.setDisplaySurface(display)
            }
        }
        try {
            _startWithLadder(info, mime, viaPipeline, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, viaPipeline, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        // skip the "low-latency" rung for codecs that don't advertise it; some omx decoders reject the key
        var tryNum = if (selector.supportsLowLatency(info, mime)) 0 else 1
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startClock() {
        val cushionMs = Settings.Global.getString(appCtx.contentResolver, CUSHION_SETTING)?.toLongOrNull() ?: DEF_CUSHION_MS
        clock = SilentClock(audioSessionId, cushionMs * 1000).also { it.start() }
    }

    private fun _canTunnel(info: MediaCodecInfo, mime: String) = runCatching {
        info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
    }.getOrDefault(false)

    private fun _startTunneled(info: MediaCodecInfo, mime: String, display: Surface, h265: Boolean) {
        if (audioSessionId == 0) audioSessionId = appCtx.getSystemService(AudioManager::class.java).generateAudioSessionId()
        val format = _format(mime, info).apply {
            setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback, true)
            setInteger(MediaFormat.KEY_AUDIO_SESSION_ID, audioSessionId)
        }
        _startDecoder(MediaCodec.createByCodecName(info.name), format, display, h265)
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
        clock?.stop()
        clock = null
        tunnelActive = false
        tunnelSurface = null
        if (direct) {
            // decoder has let go of the display surface; the pipeline takes it back
            direct = false
            pipeline.setDisplaySurface(displaySurface)
        }
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            _recordOutputFrameTime()
            if (scheduledOutputBufferRelease) {
                // schedule frame at VSYNC matching its NTP presentation time
                val ptsUs = info.presentationTimeUs
                if (_ptsBaseUs == Long.MIN_VALUE) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = System.nanoTime()
                }
                c.releaseOutputBuffer(idx, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
            } else {
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        _resetStats()
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 20_000L
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50
        // ~2 s of frames held for the display surface before giving up on the tunnel
        private const val HOLD_MAX = 120
        private const val TUNNEL_SETTING = "airplay_tunnel"
        // how far the silent clock runs behind the fastest recent frame: room for jitter and decoding
        private const val CUSHION_SETTING = "airplay_tunnel_cushion_ms"
        private const val DEF_CUSHION_MS = 80L
    }
}
