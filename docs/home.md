# The home screen (`home/`)

`com.dhiram.tvhome`: a native Android TV launcher (Java, Android views, RecyclerView; no Leanback, no
Compose), built for this TV's 1280 × 720 UI (960 × 540 dp) and weak GPU. It is also the screensaver
and runs the soundbar guard.

## Layout

From the top:

1. **Shelf.** Full-bleed artwork and text for whatever is focused: what you last watched in an app,
   what's popular there, a device, your day. On the top rows the art fades out under them; on deeper
   rows it runs to the bottom edge and the rows sit over it (Apple TV app style).
2. **Apps** (row 1): the ten apps this profile used most recently, as banners. Before there is any
   history, `default_apps` sets the order.
3. **Inputs and settings** (row 2, compact): a tile for each HDMI port with something plugged in (by
   its HDMI-CEC name such as "Fire TV", or "HDMI 2" if it doesn't announce one), AirPlay, Inputs,
   Settings. Plugging something in or out updates the row live.
4. **Your day** (row 3): one strip with what's next ("Makhana Sync at 7:30 pm · 3 unread · 2 due
   today"). Focus it for the whole day in the shelf; OK starts the screensaver.
5. **Media rows**, below the fold: Continue Watching, Trending, Popular on each service (ordered by
   how recently you used it), All Apps.

Chrome is only the avatar (top left) and weather and clock (top right).

## Remote

- Up/Down move exactly one row: apps → inputs → day strip → each media row. Up from the apps row goes
  to the avatar.
- Left/Right stay within a row; the ends of a row and the bottom of the page hold focus.
- Each row remembers its last tile. Home returns to the first app tile; Back returns to the apps row.
- OK opens; holding OK on an app opens its Android settings page.

The rules live in one place, `MainActivity.dispatchKeyEvent`, over the ordered list from `stops()`.

## Profiles

Up to four profiles, Guest always last (`data/Profiles.java`). Each has its own app ranking, Continue
Watching, calendars and feed. In the picker (avatar → OK), hold OK on a profile to rename it, lock it
with a 4-digit PIN or delete it. The first profiles come from `profiles` in `config.xml`; after that
they're managed on the TV.

Profile pictures come from `profile_photo_names` + `profile_photos` in `config.xml` (Dhiram's is
the brick from dhiram.com, `res/drawable/avatar_brick.xml`). Profiles without one show their initial
on their colour.

## Where the content comes from

| What | Source | Code |
|---|---|---|
| App ranking | UsageStats foreground events (catches the remote's Netflix/YouTube buttons too) | `data/Apps.java` |
| Continue Watching | Media sessions of the streaming apps, via a notification listener: title, artwork, position | `data/MediaWatcher.java`, `data/ContinueStore.java` (`continue.json`) |
| Hi-res art for Continue Watching | TMDB `w1280` backdrops; for YouTube, the video's `maxresdefault` thumbnail found by a keyless search. Cached per title in `tmdb-art.json` | `data/Tmdb.java` (`backdropFor`) |
| Trending / Popular rows | TMDB, filtered to the services in `config.xml` | `data/Tmdb.java` (`tmdb.json`) |
| Calendar | The TV's Android calendar provider | `data/CalendarRepo.java` |
| Mail, tasks, Flent numbers | An Apps Script web app per Google account (below) | `data/Feed.java`, `home/feed/Code.gs` |
| Weather and air | Open-Meteo; city from the connection's IP (ipwho.is), `weather_*` as fallback | `data/Weather.java` |
| Inputs | `TvInputManager`; CEC child inputs carry the device name | `data/Devices.java` |
| Screensaver photos | Bing photo of the day, Google Earth View, Art Institute of Chicago, fetched daily; or your own | `data/Photos.java` |

## Keys

`home/local.properties` (git-ignored) is read at build time into `BuildConfig`:

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools   # added by build-and-install.sh if missing
tmdb.key=<TMDB v3 API key>                              # no key: no TMDB rows, no hi-res art
feed.<profile id>=<Apps Script web-app URL>?k=<TOKEN>[,<another account's URL>]
```

### The feed (`home/feed/Code.gs`)

One Apps Script project per Google account publishes unread mail, today's and tomorrow's events and
open tasks as JSON. Setup is in the file's header: paste it, add the Google Tasks service, set the
script property `TOKEN`, deploy as a web app ("Execute as: Me", "Anyone"), and put the URL plus
`?k=<TOKEN>` in `local.properties`. On the Flent account, script properties `SUPABASE_URL` and
`SUPABASE_SERVICE_KEY` add occupancy, wins and visitors; the key stays in the script and the TV only
receives the numbers.

## Configuration (`home/app/src/main/res/values/config.xml`)

Edit, then run `home/build-and-install.sh`.

| Key | What it does |
|---|---|
| `hidden_apps` | Packages never shown as tiles (the tuner app, stock launcher, the AirPlay app). |
| `profiles` | First-run profiles: `name|colour|calendars`. |
| `profile_photo_names`, `profile_photos` | Profile pictures by name (two lists that line up). |
| `default_apps` | Apps-row order before there is usage history. |
| `live_tv_package` | The tuner app, last in the Inputs popup. |
| `weather_city`, `weather_lat`, `weather_lon` | Fallback when the IP lookup fails. |
| `world_clocks` | Screensaver clocks: `label|IANA zone`. |
| `services` | TMDB rows: `label|TMDB provider name|package`. |
| `photo_sources` | `bing`, `earthview`, `artic`; remove a line to drop one. |
| `ambient_photos_dir`, `ambient_pin_local` | Your own screensaver photos (`tools/push-photo.sh`) replace the fetched ones while the folder has any. |
| `airplay_package` | The receiver; shown as the AirPlay tile when installed. |
| `soundbar_optical` | `true`: hold the TV speakers at 0 and ignore volume keys. |

## Screensaver

`ambient/AmbientDream.java`, a `DreamService`: date and city, a large clock, weather, high/low and air
quality, today's events, and world clocks on the left; the photo on the right. Waking it goes
straight back to home (no profile picker).

## Soundbar guard

The Samsung soundbar is on optical (S/PDIF). Optical carries a fixed-level signal, so the TV's
volume only drives its own speakers, and the TV's "Speakers" setting only switches over HDMI-CEC.
`data/SoundbarGuard.java` is an accessibility service that keeps `STREAM_MUSIC` at 0 (re-zeroing on
every `VOLUME_CHANGED_ACTION`) and swallows the volume keys, so the soundbar is the only speaker and
its remote is the volume control. `soundbar_optical=false` turns it off (it disables itself).

AirPlay volume is unaffected: the receiver scales its own samples (see [airplay.md](airplay.md)).

## Install (`home/build-and-install.sh`)

Builds `assembleRelease` (R8-shrunk, signed with the debug key so it upgrades in place), pushes it
with `tools/b64push.py`, then on the TV: installs, AOT-compiles (`cmd package compile -m speed`),
grants calendar, storage and usage access, enables the notification listener, sets it as home and
screensaver, disables the stock launcher, enables the soundbar guard, and whitelists the AirPlay
receiver. It only brings home to the front if home was already showing.

## Performance

| | Before (debug build) | Now |
|---|---|---|
| Cold start | 4.2 s, 145 frames skipped | 0.25–0.48 s |
| Janky frames, fixed D-pad script | 19 % | 8.5–15 % |
| Memory (PSS) | ~102 MB | 70–99 MB |

What did it: release build + full AOT compile; the app scan (every banner) moved off the main
thread; rows rebuild only when what they show changed (a signature of ranking, Continue Watching,
TMDB, devices, profile); hero bitmaps decoded at 1280 px and recycled, not cached.

What's left is GPU fill rate during the full-screen artwork crossfade (the TV composites on the GPU).
Two things made it worse and were reverted: hardware layers on the hero frame and on whole rows
during fades (`withLayer()`).

Measure with:

```bash
tools/venv/bin/python -I tools/tv.py 'am force-stop com.dhiram.tvhome; am start -W -a android.intent.action.MAIN -c android.intent.category.HOME'
tools/venv/bin/python -I tools/tv.py 'dumpsys gfxinfo com.dhiram.tvhome reset' # then drive the D-pad
tools/venv/bin/python -I tools/tv.py 'dumpsys gfxinfo com.dhiram.tvhome | grep -E "Janky|percentile"'
```

## Code map

| File | Role |
|---|---|
| `MainActivity.java` | Rows, cards, heroes, navigation, input/profile/screensaver entry points. |
| `App.java` | Shared executors (`io`, `main`), profiles, Continue store, image loader. |
| `ui/Shelf.java` | The hero: art crossfade, browse mode, text. |
| `ui/RowView.java`, `ui/CardAdapter.java`, `ui/Card.java` | Rows and tiles (compact mode for row 2). |
| `ui/ProfilePicker.java`, `ui/PinPad.java` | "Who's watching?" and PIN entry. |
| `ui/Images.java` | Async image loading with a 16 MB memory cache. |
| `data/*` | Content sources (table above), `SoundbarGuard`. |
| `ambient/AmbientDream.java` | The screensaver. |
