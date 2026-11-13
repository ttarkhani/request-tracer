#!/usr/bin/env bash
# Step 0: probe the ORIGINAL (HEAD 1c01c35) code running natively under JDK 17.
set -uo pipefail
ROOT=$1; OUT=$2; H=$(dirname "$0"); mkdir -p "$OUT"
LOG=$OUT/logs
echo "java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
"$H/native-stack.sh" start "$ROOT" "$LOG"
body='{"customerId":"CUST-1","items":"sku-1"}'
echo; echo "### (a) inbound X-Trace-Id to gateway"
IN=11111111-2222-3333-4444-555555555555
curl -s -D - -o /dev/null -X POST localhost:8080/orders/place -H 'Content-Type: application/json' -H "X-Trace-Id: $IN" -d "$body"
sleep 0.5
echo "GET /traces/$IN -> $(curl -s localhost:8084/traces/$IN)"
echo "gateway log line:"; grep "Received order request" "$LOG/gateway.log" | tail -1
echo; echo "### (b) POST directly to Orders with NO X-Trace-Id"
curl -s -w '\nHTTP %{http_code}\n' -X POST localhost:8081/api/orders -H 'Content-Type: application/json' -d "$body"
sleep 0.5
echo "orders.log (ship failures):"; grep -i "Failed to ship\|\[null\]" "$LOG/orders.log" | tail -5
echo "inventory.log:"; tail -4 "$LOG/inventory.log"
echo "aggregator.log:"; grep -i "Stored log\|NullPointer\|WARN\|ERROR" "$LOG/aggregator.log" | tail -6
echo "GET /traces/ (empty key; path segment empty -> expect 404):"; curl -s -w ' HTTP %{http_code}\n' localhost:8084/traces/
echo "GET /traces/%20 :"; curl -s -w ' HTTP %{http_code}\n' "localhost:8084/traces/%20"
echo; echo "### (b2) POST directly to Inventory with NO X-Trace-Id"
curl -s -w '\nHTTP %{http_code}\n' -X POST localhost:8082/api/inventory/check -H 'Content-Type: application/json' -d '{"items":"x"}'
sleep 0.3; grep -i "Failed to ship" "$LOG/inventory.log" | tail -2
echo; echo "### (b3) aggregator directly: POST /logs with traceId null and blank"
curl -s -w '\nHTTP %{http_code}\n' -X POST localhost:8084/logs -H 'Content-Type: application/json' -d '{"traceId":null,"serviceName":"probe","message":"m","status":"success","timestamp":1}'
curl -s -w '\nHTTP %{http_code}\n' -X POST localhost:8084/logs -H 'Content-Type: application/json' -d '{"traceId":"","serviceName":"probe","message":"m","status":"success","timestamp":1}'
echo; echo "### (e) arrival order vs timestamp order"
for ts in 300 100 200; do curl -s -o /dev/null -X POST localhost:8084/logs -H 'Content-Type: application/json' -d "{\"traceId\":\"order-probe\",\"serviceName\":\"probe\",\"message\":\"ts$ts\",\"status\":\"success\",\"timestamp\":$ts}"; done
echo "GET /traces/order-probe -> $(curl -s localhost:8084/traces/order-probe | jq -c '[.[].timestamp]')"
echo; echo "### (d) inventory down: gateway call"
"$H/native-stack.sh" stop "$LOG" inventory; sleep 1
R=$(curl -s -w '\nHTTP %{http_code}' -X POST localhost:8080/orders/place -H 'Content-Type: application/json' -d "$body"); echo "$R"
sleep 0.5
TID=$(grep "Received order request" "$LOG/gateway.log" | tail -1 | sed -E 's/.*\[([0-9a-f-]{36})\].*/\1/')
echo "trace $TID entries:"; curl -s localhost:8084/traces/$TID | jq -c '.[] | {serviceName,message,status}'
echo; echo "### (c) aggregator HUNG (accepts, never replies) -> does gateway block?"
"$H/native-stack.sh" stop "$LOG" aggregator; sleep 1
python3 -I "$H/hang_server.py" 8084 & HP=$!
sleep 0.5
"$JAVA_HOME/bin/java" -jar "$ROOT/services/inventory/target/inventory-service-1.0.0.jar" > "$LOG/inventory2.log" 2>&1 & IP=$!
for i in $(seq 1 120); do grep -q "Started" "$LOG/inventory2.log" && break; sleep 0.5; done
START=$(python3 -c 'import time;print(time.time())')
curl -s -o /dev/null -w 'curl exit after %{time_total}s, HTTP %{http_code}\n' --max-time 20 -X POST localhost:8080/orders/place -H 'Content-Type: application/json' -d "$body"; echo "curl exit code: $?"
kill $HP $IP 2>/dev/null
"$H/native-stack.sh" stop "$LOG"
echo done
