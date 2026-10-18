# CLAUDE.md: oneplus-tv

## Project
Dhiram's OnePlus Y Series Android TV (Android 11, `armeabi-v7a`, 1280 × 720 UI) made tvOS-like: a
custom home screen + screensaver (`home/`), an AirPlay receiver fork (`airplay/`), tools to drive the
TV over ADB (`tools/`). **Public** repo `dhiram-shah/oneplus-tv` (remote `personal`). The TV's
original backup (`backup/`) is git-ignored and stays local. Remote `origin` is the template author's
`Gathin23/tv-vibehack`: **never push to origin**.

## Tech stack
- `home/`: Java, Android views + RecyclerView, Gradle (AGP), JDK 17, compileSdk 34, minSdk/targetSdk 30.
- `airplay/`: Kotlin + C/C++ (UxPlay, FFmpeg, OpenSSL, libplist as submodules), NDK 27, GPL-3.0.
- `tools/`: Python (pure-Python ADB client, `tools/venv`), bash.

## Key files
| Path | Role |
|---|---|
| `home/app/src/main/java/com/dhiram/tvhome/MainActivity.java` | Rows, heroes, navigation (`dispatchKeyEvent` + `stops()`), rebuild signature |
| `home/app/src/main/res/values/config.xml` | Every user-facing knob (apps, profiles, photos, services, soundbar) |
| `home/build-and-install.sh` | Release build, install, AOT compile, grants, home/screensaver/accessibility setup |
| `home/feed/Code.gs` | Apps Script feed (mail, events, tasks, Flent numbers) |
| `airplay/app/src/main/kotlin/.../renderer/VideoRenderer.kt`, `SilentClock.kt` | Direct + tunneled video, the silent clock |
| `airplay/app/src/main/kotlin/.../service/AirPlayService.kt` | Wake on connect, home on disconnect, software volume |
| `airplay-dist/` | Signed receiver APKs; `livingroom-v3.apk` installed, `fdroid-0.0.31.apk` original |
| `tools/tv.py`, `tools/b64push.py`, `tools/shot.sh` | Shell, push, screenshots |

## Secrets (names only, never committed)
- `home/local.properties`: `tmdb.key`, `feed.<profile id>` (web-app URL with `?k=TOKEN`).
- Apps Script properties: `TOKEN`, `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `OCCUPANCY_PATH`,
  `WINS_PATH`, `VISITORS_PATH` (Flent table names stay out of the repo).
- `tools/adbkey` (the key the TV trusts). Git-ignored with `tools/venv`, `shots/`, `*.idsig`, `backup/`.
- The repo is public: never commit `backup/`, real feed samples (mail, tasks, Flent numbers),
  Flent schema/table names, or screenshots showing Flent numbers.

## Build & verify
- Home: `TV_HOST=192.168.68.113 home/build-and-install.sh`, then screenshots with `tools/shot.sh`.
- Receiver: see `docs/airplay.md#build-and-install` (assembleRelease → zipalign → apksigner with
  `~/.android/debug.keystore` → `pm install -r`). Same key every time or the install fails.
- Performance: `am start -W` cold start, `dumpsys gfxinfo` over a fixed D-pad script, `meminfo`
  (docs/home.md#performance). Measure warm: the first run after an install is always worse.

## Decisions & patterns
- The TV composites on the GPU: avoid full-screen overdraw; `withLayer()` on the hero frame or rows
  made jank worse (reverted).
- Soundbar on optical: system volume is pinned at 0 (SoundbarGuard); the receiver uses software gain.
- Tunneled video must never drop a frame (corrupts until the next IDR).
- Install script must not pull the user out of a film: it only goes home if home was showing.
- Live sends: none here; nothing in this repo messages anyone.

## Docs
| Doc | Covers |
|---|---|
| `README.md` | Overview, screenshots, quick start, undo, credits |
| `docs/home.md` | Home app: layout, remote, profiles, sources, config, screensaver, soundbar guard, performance |
| `docs/airplay.md` | Receiver changes, silent clock, settings, build/install, logs, rollback, upstream merges |
| `docs/tv.md` | Hardware, ADB tooling and gotchas, debloat, backup, settings changed from stock |
| `docs/CHANGELOG.md` | Dated history |
