#!/usr/bin/env bash
# Screenshot the TV into shots/<name>.png.  Usage: TV_HOST=... tools/shot.sh name
set -euo pipefail
cd "$(dirname "$0")/.."
PY=tools/venv/bin/python
"$PY" -I tools/tv.py 'screencap -p /sdcard/s.png' >/dev/null 2>&1
"$PY" -I tools/tv.py --pull /sdcard/s.png "shots/$1.png" >/dev/null
"$PY" -I tools/tv.py 'rm /sdcard/s.png' >/dev/null 2>&1
echo "shots/$1.png"
