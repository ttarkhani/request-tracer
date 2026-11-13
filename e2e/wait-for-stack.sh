#!/usr/bin/env bash
# Polls until the full chain answers: a real order through the gateway returns 200 and the
# aggregator answers GET /traces. Exits non-zero after TIMEOUT seconds (default 180).
set -u
TIMEOUT=${1:-180}
GATEWAY=${GATEWAY_URL:-http://localhost:8080}
AGGREGATOR=${AGGREGATOR_URL:-http://localhost:8084}
start=$(date +%s)
while true; do
  agg=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$AGGREGATOR/traces/readiness-probe" || true)
  gw=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$GATEWAY/orders/place" \
       -H 'Content-Type: application/json' -d '{"customerId":"READY","items":"probe"}' || true)
  if [ "$agg" = 200 ] && [ "$gw" = 200 ]; then
    echo "stack ready after $(( $(date +%s) - start ))s"; exit 0
  fi
  if [ $(( $(date +%s) - start )) -ge "$TIMEOUT" ]; then
    echo "stack not ready after ${TIMEOUT}s (aggregator=$agg gateway=$gw)"; exit 1
  fi
  sleep 2
done
