#!/usr/bin/env bash
# Profiles what the JVMs do during the first request after a cold start.
# Starts the stack with bench/compose/profile-classload.yml (class-load + GC logs with JVM uptime), waits for every
# "Started ..." line, sends ONE request, copies the logs, sends 20 more, copies again.
#   bench/scripts/profile-first-request.sh <tree-root> <project> <out-dir> [cycles]
set -eu
TREE=$1; PROJECT=$2; OUT=$3; N=${4:-3}; HERE=$(cd "$(dirname "$0")/.." && pwd)
C="docker compose -p $PROJECT -f $TREE/docker-compose.yml -f $HERE/compose/profile-classload.yml"
mkdir -p "$OUT"
order() { curl -s -o /dev/null -w '%{time_total}' -X POST localhost:8080/orders/place -H 'Content-Type: application/json' -d '{"customerId":"PROF","items":"sku-1"}'; }
for c in $(seq 1 "$N"); do
  d="$OUT/cycle$c"; mkdir -p "$d"
  $C down -v >/dev/null 2>&1; $C up -d >/dev/null 2>&1
  for s in aggregator gateway orders inventory; do
    until $C logs $s 2>/dev/null | grep -q "Started .*Application in"; do sleep 0.2; done
  done
  echo "first request: $(order)s" > "$d/requests.txt"
  sleep 2
  for s in aggregator gateway orders inventory; do docker cp "$($C ps -q $s):/tmp/classload.log" "$d/$s-classload-after-first.log" >/dev/null; done
  for i in $(seq 1 20); do order >> "$d/requests.txt"; echo >> "$d/requests.txt"; done
  sleep 2
  for s in aggregator gateway orders inventory; do
    docker cp "$($C ps -q $s):/tmp/classload.log" "$d/$s-classload-after-steady.log" >/dev/null
    docker cp "$($C ps -q $s):/tmp/gc.log" "$d/$s-gc.log" >/dev/null
  done
  $C logs --no-color --timestamps > "$d/compose.log" 2>&1
  python3 -I "$HERE/scripts/classload_report.py" "$d" | tee "$d/report.txt"
done
$C down -v >/dev/null 2>&1
