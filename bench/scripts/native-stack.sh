#!/usr/bin/env bash
# Start/stop the four Java services natively from prebuilt jars (no Docker).
# usage: native-stack.sh start <tree-root> <logdir> [skip-service...] | stop <logdir> [service]
set -euo pipefail
cmd=$1
jar_of() { case $1 in
  aggregator) echo "$ROOT/log-aggregator/target/log-aggregator-1.0.0.jar";;
  gateway) echo "$ROOT/services/api-gateway/target/api-gateway-1.0.0.jar";;
  orders) echo "$ROOT/services/orders/target/orders-service-1.0.0.jar";;
  inventory) echo "$ROOT/services/inventory/target/inventory-service-1.0.0.jar";;
esac; }
port_of() { case $1 in aggregator) echo 8084;; gateway) echo 8080;; orders) echo 8081;; inventory) echo 8082;; esac; }
if [ "$cmd" = start ]; then
  ROOT=$2; LOG=$3; shift 3; mkdir -p "$LOG"
  for s in aggregator gateway orders inventory; do
    if [[ " $* " == *" $s "* ]]; then continue; fi
    nohup "$JAVA_HOME/bin/java" -jar "$(jar_of $s)" > "$LOG/$s.log" 2>&1 &
    echo $! > "$LOG/$s.pid"
  done
  for s in aggregator gateway orders inventory; do
    [[ " $* " == *" $s "* ]] && continue
    for i in $(seq 1 120); do grep -q "Started .*Application" "$LOG/$s.log" && break; sleep 0.5; done
    grep -o "Started .*Application in [0-9.]* seconds" "$LOG/$s.log" || { echo "$s failed to start"; exit 1; }
  done
elif [ "$cmd" = stop ]; then
  LOG=$2; only=${3:-}
  for f in "$LOG"/*.pid; do
    s=$(basename "$f" .pid); [ -n "$only" ] && [ "$s" != "$only" ] && continue
    kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"
  done
fi
