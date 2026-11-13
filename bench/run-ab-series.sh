#!/usr/bin/env bash
# Alternating before/after series (before, after, before, after, ...) for one scenario.
#   bench/run-ab-series.sh <before-tree> <after-tree> <scenario A|B> <runs> <out-root>
set -u
BEFORE=$1; AFTER=$2; SC=$3; N=$4; OUT=$5; HERE=$(cd "$(dirname "$0")" && pwd)
ARGS=(RATE=50 WARMUP_S=60 DURATION_S=60 MAX_VUS=300 TIMEOUT=10s SAMPLE_EVERY=50)
export PAUSE_AT_S=75 PAUSE_S=20   # scenario B: aggregator frozen 15-35 s into the 60 s main window
for i in $(seq 1 "$N"); do
  for side in before after; do
    tree=$BEFORE; proj=rtbefore; [ $side = after ] && { tree=$AFTER; proj=rtafter; }
    d="$OUT/$SC/$side-run$i"
    pct=$(pmset -g batt | grep -o '[0-9]*%' | tr -d %)
    if ! pmset -g batt | grep -q "AC Power" || [ "${pct:-0}" -lt 8 ]; then echo "ABORT: not on AC or battery ${pct}% < 8%"; exit 2; fi
    top -l 1 -o cpu -n 8 -stats pid,command,cpu,mem > "$d.host-top.txt" 2>/dev/null || true
    echo "=== $SC $side run $i -> $d"
    "$HERE/run-load.sh" "$tree" "$proj" "$SC" "$d" "${ARGS[@]}" 2>&1 | grep -E "k6 exit|containers|settled|paused"
    python3 -I "$HERE/summarize.py" "$d/k6-summary.json" phase:main=60
  done
done
