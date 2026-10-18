# The AirPlay receiver (`airplay/`)

"Living Room TV": [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
0.0.31 (GPL-3.0, built on UxPlay), with changes for this TV. The fork's history, upstream's included,
came in with the merge "Bring the AirPlay receiver in-tree": `git log 5fd8b61^2`. Package `io.github.jqssun.airplay`, signed with the local debug key.

## What the fork changes, and why

| Change | Why | Where |
|---|---|---|
| Name "Living Room TV"; ask the sender for 1920 × 1080 | The TV's UI runs at 1280 × 720, so the stock "match the display" size gave a soft 720p picture. The decoder (`OMX.MS.AVC.Decoder`) handles up to 1920 × 1088. | `Prefs.kt` (`DEF_SERVER_NAME`, `AUTO_DISPLAY_SIZE`), `AirPlayService.kt` |
| Decode straight to the display surface | Upstream decodes to a SurfaceTexture and redraws it with GL: an extra scale and copy. `MediaCodec.setOutputSurface` switches the running codec to the SurfaceView when it appears. | `renderer/VideoRenderer.kt`, `renderer/VideoPipeline.kt` (`detachDisplaySync`) |
| Tunneled playback with a silent clock | The decoder supports tunneled playback (video goes to the display hardware directly, sharpest and lowest overhead), but it only renders against the clock of a HW A/V-sync audio track in the same session, and mirroring audio plays elsewhere. `SilentClock` plays silence on such a track, stamped so the clock follows the frames (below). | `renderer/SilentClock.kt`, `VideoRenderer.startCodec` |
| Never drop a frame while tunneled | One lost H.264 frame corrupts every frame until the next keyframe, and the Mac only sends one on connect. Feeding waits for an input buffer (up to 50 × 20 ms) instead of dropping; frames from the first keyframe are held until a display exists (up to 120, then it starts untunneled). | `VideoRenderer.feedFrame`, `_feedToCodec` |
| Wake the TV and come to the front | A mirror or video session wakes the screen (wake lock with `ACQUIRE_CAUSES_WAKEUP`) and launches the receiver over the lock screen. | `AirPlayService._wakeAndFront`, `MainActivity.kt` |
| Back to home on disconnect | When the last sender disconnects, the TV's home screen comes up instead of the receiver's own screen. Follows the "launch on connect" setting. | `AirPlayService.onConnectionDestroy`, `_goHome` |
| Software volume | The TV's volume is held at 0 for the optical soundbar, so mapping the sender's slider to system volume did nothing. The slider now scales the samples in the native audio callback: gain = 10^(dB/20), mute at −144 dB, reset to 1 on disconnect. System volume is never touched (`SOFT_VOLUME`). | `cpp/audio_output.h` (`g_soft_gain`), `cpp/native_bridge.cpp` (`nativeSetSoftGain`), `AirPlayService.onVolumeChange` |
| `armeabi-v7a` only | The TV's ABI; a smaller APK. | `app/build.gradle.kts` |

### The silent clock

The decoder shows each frame when the audio clock reaches the frame's timestamp. For every frame fed,
the receiver records `pts − arrival time`; the smallest value over the last ~2 s (120 frames) is the
frame that got here fastest, and it maps wall time to stream time. Every 10 ms chunk of silence is
written with

```
timestamp = now + audio buffered ahead + min(pts − arrival) − cushion
```

so latency settles at the network's best case plus the cushion, and drift between the Mac's clock and
the TV's is absorbed. Anchoring to the first frame instead (the first attempt) left ~0.45 s of lag.

## Settings (on the TV, no rebuild)

```bash
tools/venv/bin/python -I tools/tv.py 'settings put global airplay_tunnel 0'            # 1 (default) = tunneled, 0 = direct output
tools/venv/bin/python -I tools/tv.py 'settings put global airplay_tunnel_cushion_ms 80' # jitter cushion, default 80
```

Both take effect on the next connection. If the picture stutters on a busy network, raise the
cushion; for less lag, lower it.

## Build and install

```bash
cd airplay
git submodule update --init --depth 1       # from the repo root, once
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH=$JAVA_HOME/bin:$PATH
./gradlew -q assembleRelease                # the build patches UxPlay (src/main/cpp/patches) first
BT=$ANDROID_HOME/build-tools/35.0.0
$BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/airplay-aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out ../airplay-dist/livingroom-<name>.apk /tmp/airplay-aligned.apk
cd ..
tools/venv/bin/python -I tools/b64push.py airplay-dist/livingroom-<name>.apk /data/local/tmp/ap.apk
printf '%s\n' \
  'pm install -r /data/local/tmp/ap.apk' 'rm -f /data/local/tmp/ap.apk' \
  'appops set io.github.jqssun.airplay SYSTEM_ALERT_WINDOW allow' \
  'dumpsys deviceidle whitelist +io.github.jqssun.airplay' \
  'monkey -p io.github.jqssun.airplay -c android.intent.category.LEANBACK_LAUNCHER 1' \
  'input keyevent HOME' | tools/venv/bin/python -I tools/tv.py --batch
```

`SYSTEM_ALERT_WINDOW` lets it start activities from the background (waking and going home); the idle
whitelist keeps the service alive; opening it once starts the service, after which it starts on boot.
`pm install -r` needs the same key as the installed build: always sign with the same keystore.

## Logs

```bash
tools/venv/bin/python -I tools/tv.py 'logcat -G 16M'   # once; the default buffer rolls over in seconds
tools/venv/bin/python -I tools/tv.py 'logcat -d | grep -E "AirPlayService|VideoRenderer|SilentClock|DecoderSelector"'
```

What healthy looks like: `VideoRenderer: ... tunneled, audio session N` on connect, then
`SilentClock: started` and `buffered …ms, cushion 80ms` every 10 s. A black screen while tunneled
with `in pause state, waiting for resume` from the decoder means the clock track didn't start.

## Rollback

`airplay-dist/` keeps every build:

| APK | What |
|---|---|
| `fdroid-0.0.31.apk` | The original F-Droid release, as first installed. Different signing key: `pm uninstall io.github.jqssun.airplay` first. |
| `livingroom-0.0.31.apk` | Rename, 1080p, wake on connect. |
| `livingroom-direct.apk` | + direct output. |
| `livingroom-tunnel.apk` | + tunneled playback with the silent clock. |
| `livingroom-v3.apk` | + home on disconnect, software volume. **Installed.** |

## Updating from upstream

```bash
git fetch https://github.com/jqssun/android-airplay-server main
git merge -s subtree FETCH_HEAD     # merges upstream into airplay/
```

Then rebuild and re-test tunneled playback; `VideoRenderer.kt` and `AirPlayService.kt` carry most of
the fork's changes.
