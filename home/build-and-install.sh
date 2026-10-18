#!/usr/bin/env bash
# Build the home app, install it on the TV, grant what it needs over adb and make it the home screen
# and screensaver. Safe to re-run. Undo: tools/restore.sh <debloat list> com.google.android.tvlauncher
#
# Usage: TV_HOST=192.168.68.113 home/build-and-install.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
PKG=com.dhiram.tvhome
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
grep -q '^sdk.dir=' local.properties 2>/dev/null || echo "sdk.dir=$ANDROID_HOME" >> local.properties

./gradlew assembleRelease -q --console=plain
PY=../tools/venv/bin/python
APK=app/build/outputs/apk/release/app-release.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/home.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/home.apk" \
  "rm -f /data/local/tmp/home.apk" \
  "# Ahead-of-time compile all of it: no JIT warm-up on a cold start" \
  "cmd package compile -m speed -f $PKG" \
  "pm grant $PKG android.permission.READ_CALENDAR" \
  "pm grant $PKG android.permission.READ_EXTERNAL_STORAGE" \
  "appops set $PKG GET_USAGE_STATS allow" \
  "cmd notification allow_listener $PKG/$PKG.data.MediaWatcher" \
  "cmd package set-home-activity --user 0 $PKG/.MainActivity" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  "settings put secure screensaver_components $PKG/$PKG.ambient.AmbientDream" \
  "settings put secure screensaver_enabled 1" \
  "# Optical soundbar: keep the TV speakers at 0 (data/SoundbarGuard; off via config soundbar_optical)" \
  "settings put secure enabled_accessibility_services $PKG/$PKG.data.SoundbarGuard" \
  "settings put secure accessibility_enabled 1" \
  "# AirPlay receiver, if installed: may come to the front when a Mac/iPhone connects, and stays alive" \
  "pm list packages | grep -q io.github.jqssun.airplay && appops set io.github.jqssun.airplay SYSTEM_ALERT_WINDOW allow; true" \
  "pm list packages | grep -q io.github.jqssun.airplay && dumpsys deviceidle whitelist +io.github.jqssun.airplay; true" \
  "# Back to home only if home was in front: never pull someone out of a film or an AirPlay session" \
  "dumpsys activity activities | grep mResumedActivity | grep -qE '$PKG|tvlauncher|Somnambulator|Dream' && am start -a android.intent.action.MAIN -c android.intent.category.HOME; true" \
  | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote."
