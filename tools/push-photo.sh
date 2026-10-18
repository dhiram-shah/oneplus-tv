#!/usr/bin/env bash
# Push photos to the TV's screensaver folder. While the folder has photos, the screensaver shows only
# those (home/app/src/main/res/values/config.xml: ambient_pin_local). Re-encodes to JPEG ≤1920 px.
#
# Usage:  TV_HOST=192.168.68.113 tools/push-photo.sh photo.png [more…]
#         TV_HOST=192.168.68.113 tools/push-photo.sh --clear      # back to the daily fetched photos
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP address}"
PY=tools/venv/bin/python
DIR=/sdcard/Pictures/Ambient

if [[ "${1:-}" == "--clear" ]]; then
  "$PY" -I tools/tv.py "rm -f $DIR/*"
  echo "Cleared $DIR; the screensaver uses the fetched photos again."
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
"$PY" -I tools/tv.py "mkdir -p $DIR"
for src in "$@"; do
  name=$(basename "${src%.*}" | tr ' ' '-' | tr '[:upper:]' '[:lower:]').jpg
  sips -s format jpeg -s formatOptions 92 -Z 1920 "$src" --out "$tmp/$name" >/dev/null
  "$PY" -I tools/b64push.py "$tmp/$name" "$DIR/$name" | tail -1
  echo "pushed $name"
done
