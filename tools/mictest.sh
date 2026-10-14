#!/bin/bash
# Phase-0 acoustics test: shows the prompt script on the tablet, pauses/plays the
# TV box on the script's marks, then pulls <name>.wav/.tsv into recordings/.
#   TAB=ip:port BOX=ip:port tools/mictest.sh [name] [extra am-start args…]
#   e.g. tools/mictest.sh smoke --es script '!Проверка@5' --ei ch 2
set -euo pipefail

TAB=${TAB:?set TAB=<tablet-ip>:<port>}
BOX=${BOX:?set BOX=<tv-ip>:5555}
PKG=com.megarohas.tvvoice
NAME=${1:-test-$(date +%Y%m%d-%H%M%S)}
[ $# -gt 0 ] && shift
OUT="$(cd "$(dirname "$0")/.." && pwd)/recordings"
mkdir -p "$OUT"

# The remote shell re-parses the command line: quote every extra argument for it.
args=""
for a in "$@"; do args="$args '${a//\'/}'"; done

LOG=$(mktemp)
adb -s "$TAB" logcat -c
adb -s "$TAB" logcat -v raw -s tvvoice:I > "$LOG" &
LC=$!
trap 'kill $LC 2>/dev/null; wait $LC 2>/dev/null; rm -f "$LOG"' EXIT

adb -s "$TAB" shell "am start -n $PKG/.MicTestActivity --es name '$NAME' $args" >/dev/null
echo "running $NAME — watch the tablet"

n=0
while :; do
  total=$(wc -l < "$LOG" | tr -d ' ')
  while [ "$n" -lt "$total" ]; do
    n=$((n + 1))
    line=$(sed -n "${n}p" "$LOG")
    case "$line" in
      "MARK pause") adb -s "$BOX" shell input keyevent KEYCODE_MEDIA_PAUSE; echo ">> TV paused" ;;
      "MARK play")  adb -s "$BOX" shell input keyevent KEYCODE_MEDIA_PLAY;  echo ">> TV playing" ;;
      LVL*) ;;  # once a second; too chatty for the terminal
      START*|ITEM*) echo "$line" ;;
      DONE*|ABORTED*|ERR*) echo "$line"; break 2 ;;
    esac
  done
  sleep 0.3
done

sleep 1
for ext in wav tsv; do
  adb -s "$TAB" pull "/sdcard/Android/data/$PKG/files/$NAME.$ext" "$OUT/" >/dev/null
done
ls -lh "$OUT/$NAME".*
