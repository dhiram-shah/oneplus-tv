# The TV

| | |
|---|---|
| Model | OnePlus TV Y Series (Skyworth "shibuya", `ro.product.name` `OnePlus_Y_Series`) |
| OS | Android TV 11 (SDK 30) |
| SoC | MediaTek/MStar `m5621`, 32-bit userspace (`armeabi-v7a`) |
| UI | 1280 × 720 (960 × 540 dp); SurfaceFlinger composites on the GPU, so full-screen overdraw costs |
| Video decoder | `OMX.MS.AVC.Decoder`: up to 1920 × 1088, tunneled and adaptive playback |
| Network | `192.168.68.113`, ADB on port 5555 |
| Audio | Samsung soundbar on optical (S/PDIF, PCM); TV speakers held silent |

## Talking to the TV

The Google `adb` binary is blocked by macOS Local Network privacy ("No route to host" while the port
is open), so `tools/` talks ADB from Python instead.

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.68.113
tools/venv/bin/python -I tools/tv.py --probe                  # model, Android version, firmware
tools/venv/bin/python -I tools/tv.py 'pm list packages'       # one command
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch
tools/venv/bin/python -I tools/tv.py --pull /sdcard/x.png x.png
tools/venv/bin/python -I tools/b64push.py app.apk /data/local/tmp/app.apk   # large files, md5-checked
TV_HOST=… tools/shot.sh name                                  # screenshot to shots/name.png (git-ignored)
TV_HOST=… tools/push-photo.sh photo.jpg | --clear             # screensaver photos
```

The first connection creates `tools/adbkey` (git-ignored) and the TV asks "Allow USB debugging?".
That key is what the TV trusts: keep it, and never commit it.

### Gotchas

- Chained `pm` commands (`pm disable a; pm disable b`) can silently stop after the first. `--batch`
  runs one per shell call.
- Large pushes hang through the plain client; use `tools/b64push.py`.
- Disable the stock launcher only after the new one is installed, or Home lands on a factory test app.
- The logcat buffer is tiny by default; `logcat -G 16M` before debugging anything that runs for more
  than a few seconds.
- After switching launchers on some TVs the default input method points at an IME that isn't
  installed (no on-screen keyboard). Fix:
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`.

## Debloat

[`examples/oneplus-y-series-shibuya/debloat.txt`](../examples/oneplus-y-series-shibuya/debloat.txt)
lists the 12 packages disabled for user 0 (never uninstalled): sponsored recommendations, OxygenPlay,
store demos, the e-manual, unused streaming apps and the Assistant. Apply or undo with

```bash
tools/disable.sh examples/oneplus-y-series-shibuya/debloat.txt
tools/restore.sh examples/oneplus-y-series-shibuya/debloat.txt   # also restores the stock launcher and screensaver
```

## The backup

[`backup/`](../backup/) is the TV as it was before any change, taken over ADB:

| File | Contents |
|---|---|
| `getprop.txt` | All system properties (model, build, serial) |
| `packages-all.txt`, `-system.txt`, `-3rdparty.txt`, `-disabled.txt` | Installed packages by kind, and what was already disabled |
| `settings-global.txt`, `-secure.txt`, `-system.txt` | Every Android setting |
| `home-activity.txt` | The stock home activity |
| `ime.txt` | Input methods and the default |
| `mem.txt` | Memory at the time |

It identifies this TV (serial, `android_id`, device names), which is why the repo is private. To put a
setting back: find it in the matching file, then `tools/tv.py 'settings put <namespace> <key> <value>'`.
`airplay-dist/fdroid-0.0.31.apk` is the receiver as originally installed.

## Settings changed from stock

| Setting | Value | Set by |
|---|---|---|
| Home activity | `com.dhiram.tvhome/.MainActivity`; `com.google.android.tvlauncher` disabled | `home/build-and-install.sh` |
| `secure screensaver_components` | `com.dhiram.tvhome/…ambient.AmbientDream` | same |
| `secure enabled_accessibility_services` | `com.dhiram.tvhome/…data.SoundbarGuard` | same |
| Notification listener, usage access, calendar, storage | granted to the home app | same |
| `global airplay_tunnel`, `airplay_tunnel_cushion_ms` | `1`, unset (80) | by hand, see [airplay.md](airplay.md#settings-on-the-tv-no-rebuild) |
| Receiver appops / idle whitelist | `SYSTEM_ALERT_WINDOW allow`, whitelisted | install steps in [airplay.md](airplay.md#build-and-install) |
| STREAM_MUSIC volume | held at 0 | SoundbarGuard |
