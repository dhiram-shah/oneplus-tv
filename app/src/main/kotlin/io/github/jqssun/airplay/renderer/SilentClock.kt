package io.github.jqssun.airplay.renderer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

// tunneled video renders against the clock of an audio track in the same session; mirroring audio
// plays elsewhere, so this silent HW_AV_SYNC track exists only to run that clock.
// The clock follows the frames: each frame's (pts - arrival time) is sampled, the smallest over the
// last couple of seconds (the frame that got here fastest) maps wall time to stream time, and every
// chunk is stamped with the stream time it will be heard at, minus a cushion for jitter and decoding.
// That keeps latency at network minimum + cushion, and soaks up drift between the Mac and the TV.
class SilentClock(private val sessionId: Int, private val cushionUs: Long) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    private val lock = Object()
    private val offsets = LongArray(WINDOW)   // pts - arrival, ns
    private var count = 0
    private var next = 0

    // called for every frame fed to the decoder
    fun onFrame(ptsUs: Long, arrivalNs: Long) = synchronized(lock) {
        offsets[next] = ptsUs * 1000L - arrivalNs
        next = (next + 1) % WINDOW
        if (count < WINDOW) count++
    }

    private fun minOffsetNs(): Long? = synchronized(lock) {
        if (count == 0) return null
        var m = Long.MAX_VALUE
        for (i in 0 until count) if (offsets[i] < m) m = offsets[i]
        m
    }

    fun start() {
        if (running) return
        val bytesPerChunk = RATE / 1000 * CHUNK_MS * 4   // 16-bit stereo
        val t = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setFlags(AudioAttributes.FLAG_HW_AV_SYNC)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), bytesPerChunk * 4),
                AudioTrack.MODE_STREAM,
                sessionId
            )
        } catch (e: Exception) {
            Log.w(TAG, "HW_AV_SYNC track failed", e)
            return
        }
        track = t
        running = true
        thread = Thread({
            val silence = ByteBuffer.allocateDirect(bytesPerChunk).order(ByteOrder.nativeOrder())
            // a chunk written now is heard once the frames already buffered ahead of it have played
            val bufferedNs = t.bufferSizeInFrames * 1_000_000_000L / RATE
            var lastLog = 0L
            try {
                t.play()
                while (running) {
                    val offset = minOffsetNs()
                    if (offset == null) {
                        Thread.sleep(CHUNK_MS.toLong())
                        continue
                    }
                    val now = System.nanoTime()
                    val tsNs = now + bufferedNs + offset - cushionUs * 1000L
                    silence.clear()
                    val n = t.write(silence, bytesPerChunk, AudioTrack.WRITE_BLOCKING, tsNs)
                    if (n < 0) {
                        Log.w(TAG, "write failed: $n")
                        break
                    }
                    if (now - lastLog > 10_000_000_000L) {
                        lastLog = now
                        Log.i(TAG, "buffered ${bufferedNs / 1_000_000}ms, cushion ${cushionUs / 1000}ms")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "clock stopped", e)
            }
        }, "SilentClock").also { it.start() }
        Log.i(TAG, "started: session $sessionId, cushion ${cushionUs / 1000}ms")
    }

    fun stop() {
        running = false
        track?.let { runCatching { it.pause(); it.flush() } }
        thread?.join(200)
        thread = null
        track?.let { runCatching { it.release() } }
        track = null
    }

    companion object {
        private const val TAG = "SilentClock"
        private const val RATE = 48000
        private const val CHUNK_MS = 10
        private const val WINDOW = 120   // ~2 s of frames at 60 fps
    }
}
