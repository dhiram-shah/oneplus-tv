#!/usr/bin/env bash
# Undo the debloat: re-enable every package listed in debloat.txt and restore the Google TV launcher.
# Usage: ./restore.sh            (needs tools/venv, see README.md)
set -euo pipefail
cd "$(dirname "$0")"
PY=tools/venv/bin/python
{
  grep -vE '^\s*(#|$)' debloat.txt | sed -E 's/\s+#.*$//' | awk '{print "pm enable " $1}'
  echo "cmd package set-home-activity --user 0 com.google.android.tvlauncher"
  echo "settings put global window_animation_scale 1.0"
  echo "settings put global transition_animation_scale 1.0"
  echo "settings put global animator_duration_scale 1.0"
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
