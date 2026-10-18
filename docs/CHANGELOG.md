# Changelog

Newest first.

## 2026-10-18

**Repo**
- Made public. The TV backup stays local (git-ignored); the Flent feed's queries moved to Apps
  Script properties; unused sample feeds removed. History rewritten so none of it is recoverable.
- One repo, `dhiram-shah/oneplus-tv`, for everything: the home app, the receiver (merged in with its
  upstream history, replacing the submodule), the APKs and these docs.

**Home screen**
- Layout: apps (row 1), a compact row of inputs, AirPlay and Settings (row 2), the day as a strip
  (row 3), then the media rows. Any plugged-in HDMI port gets a jump tile, updated live.
- Profile pictures from `config.xml`; Dhiram's is the dhiram.com brick.
- Navigation made explicit: one row per Up/Down, Up from apps to the avatar, row ends and the page
  bottom hold focus, Home always returns to the first tile.
- Inputs: aligned port/device columns; states Connected, Standby, Nothing plugged in. Wider popup.
- Profile picker redesigned: equal faces, focus ring, lock icon, opaque backdrop.
- Speed: release build with R8 and AOT compile (cold start 4.2 s → ~0.3 s); app scan off the main
  thread; rows rebuild only when their content changes.
- Full-bleed artwork on deeper rows; hi-res art for Continue Watching (TMDB `w1280`, YouTube
  `maxresdefault`).
- Soundbar guard: TV speakers held at 0, volume keys ignored, so the optical soundbar is the only
  speaker.
- The Home button goes home, not to the profile picker; no picker after the screensaver.

**AirPlay receiver**
- Back to the TV's home screen when the last sender disconnects.
- Sender volume applied in software, so the Mac/iPhone slider works with the TV volume at 0.
- Tunneled playback clocked by a silent HW A/V-sync track that follows frame arrival (cushion 80 ms).
- Direct output to the display; never drops frames while tunneled.
- 1080p from the sender, named "Living Room TV", wakes the TV and comes to the front on connect.

**TV**
- Backed up (`backup/`, kept local), debloated (12 packages disabled), stock launcher replaced, ambient
  screensaver with daily photos.
