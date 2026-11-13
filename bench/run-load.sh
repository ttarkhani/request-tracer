#!/usr/bin/env bash
# One load-test run against a fresh stack.
#   bench/run-load.sh <tree-root> <compose-project> <scenario: A|B|ramp> <out-dir> [k6 env assignments...]
# Scenario A: steady load. Scenario B: same, with the aggregator frozen (`docker pause`) from
# PAUSE_AT_S to PAUSE_AT_S+PAUSE_S seconds after k6 starts, so connections hang instead of being refused.
# The stack is restarted (down -v, up -d) before every run. Images must already be built for the project.
set -uo pipefail
TREE=$1; PROJECT=$2; SCENARIO=$3; OUT=$4; shift 4
HERE=$(cd "$(dirname "$0")" && pwd)
PAUSE_AT_S=${PAUSE_AT_S:-45}
PAUSE_S=${PAUSE_S:-20}
K6_IMAGE=grafana/k6:0.54.0
mkdir -p "$OUT"
C="docker compose -p $PROJECT -f $TREE/docker-compose.yml${COMPOSE_EXTRA:+ -f $COMPOSE_EXTRA}"
log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$OUT/run.log"; }

{
  echo "date: $(date)"; echo "tree: $TREE"; echo "compose extra: ${COMPOSE_EXTRA:-none}"; echo "project: $PROJECT"; echo "scenario: $SCENARIO"; echo "k6 env: $*"
  docker info --format 'docker info: NCPU={{.NCPU}} MemTotal={{.MemTotal}} Server={{.ServerVersion}}'
  echo "kubernetes enabled (rdctl): $(rdctl list-settings 2>/dev/null | jq -r .kubernetes.enabled)"
  echo "host load: $(uptime)"
  echo "power: $(pmset -g batt | tr '\n' ' ') adapter: $(pmset -g ac | grep Wattage | tr -d ' ')"
  [ "$SCENARIO" = B ] && echo "pause: aggregator paused at +${PAUSE_AT_S}s for ${PAUSE_S}s after k6 start"
} > "$OUT/header.txt"

log "restart stack"
$C down -v >/dev/null 2>&1
$C up -d >/dev/null 2>&1 || { log "compose up failed"; exit 1; }
"$HERE/../e2e/wait-for-stack.sh" 240 | tee -a "$OUT/run.log" || exit 1
AGG=$($C ps -q aggregator)
NET="${PROJECT}_default"

stats_snapshot() { # $1 label
  curl -s --max-time 5 localhost:8084/stats > "$OUT/agg-stats-$1.json" 2>/dev/null || true
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}} memPerc={{.MemPerc}}' \
    $($C ps -q) > "$OUT/docker-stats-$1.txt"
  : > "$OUT/shipper-$1.txt"
  for svc in gateway:8080 orders:8081 inventory:8082; do
    line="${svc%%:*}"
    for o in enqueued shipped dropped failed; do
      v=$(curl -sf --max-time 5 "localhost:${svc#*:}/actuator/metrics/log.shipper.events?tag=outcome:$o" | jq '.measurements[0].value' 2>/dev/null)
      line="$line $o=${v:-n/a}"
    done
    v=$(curl -sf --max-time 5 "localhost:${svc#*:}/actuator/metrics/log.shipper.queue.depth" | jq '.measurements[0].value' 2>/dev/null)
    echo "$line depth=${v:-n/a}" >> "$OUT/shipper-$1.txt"
  done
}
stats_snapshot before

if [ "$SCENARIO" = B ]; then
  ( sleep "$PAUSE_AT_S"; docker pause "$AGG" >/dev/null && echo "[$(date '+%H:%M:%S')] aggregator paused" >> "$OUT/run.log"
    sleep "$PAUSE_S"; docker unpause "$AGG" >/dev/null && echo "[$(date '+%H:%M:%S')] aggregator unpaused" >> "$OUT/run.log" ) &
  PAUSER=$!
fi

MODE=steady; [ "$SCENARIO" = ramp ] && MODE=ramp
log "k6 start (MODE=$MODE)"
ENVARGS=(-e MODE=$MODE -e OUT=/out/k6-summary.json)
for kv in "$@"; do ENVARGS+=(-e "$kv"); done
docker run --rm --network "$NET" -v "$OUT:/out" -v "$HERE/loadtest.js:/scripts/loadtest.js:ro" \
  "${ENVARGS[@]}" "$K6_IMAGE" run --quiet /scripts/loadtest.js > "$OUT/k6-stdout.txt" 2> "$OUT/k6-stderr.txt"
log "k6 exit=$?"
[ -n "${PAUSER:-}" ] && wait "$PAUSER"
docker unpause "$AGG" >/dev/null 2>&1 || true

# Wait until the aggregator's counts stop changing (async shippers may still be draining).
prev=""
for i in $(seq 1 60); do
  cur=$(curl -sf --max-time 5 localhost:8084/stats || echo none)
  [ "$cur" = "$prev" ] && break
  prev=$cur; sleep 2
done
log "aggregator settled: $cur"
stats_snapshot after

# Per-trace check of the sampled trace IDs (from X-Trace-Id response headers).
grep -ho 'TRACE [0-9a-f-]*' "$OUT/k6-stderr.txt" "$OUT/k6-stdout.txt" | awk '{print $2}' | sort -u > "$OUT/sampled-trace-ids.txt"
: > "$OUT/sampled-trace-check.txt"
while read -r id; do
  curl -s --max-time 5 "localhost:8084/traces/$id" |
    jq -r --arg id "$id" '"\($id) entries=\(length) services=\([.[].serviceName] | unique | length) ids=\([.[].traceId] | unique | join(","))"' \
    >> "$OUT/sampled-trace-check.txt"
done < "$OUT/sampled-trace-ids.txt"

COMPOSE_PROJECT_NAME=$PROJECT COMPOSE_FILE=$TREE/docker-compose.yml${COMPOSE_EXTRA:+:$COMPOSE_EXTRA} "$HERE/scripts/check-containers.sh" > "$OUT/containers.txt" 2>&1; CC=$?
$C logs --no-color --timestamps 2>&1 | tail -n 3000 > "$OUT/compose-logs-tail.txt"
log "containers check exit=$CC (1 = restart/OOM -> discard run)"
$C down -v >/dev/null 2>&1
log "done"
