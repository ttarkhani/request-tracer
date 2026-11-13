# Demo script (live mode, local stack)

A short script to follow while recording. Everything runs locally; the public Vercel site is demo mode only (no backend).

1. **Start clean.** `docker compose down -v && docker compose up --build -d`, then `./e2e/wait-for-stack.sh 240`
   until it prints `stack ready`.
2. **Open the dashboard** at http://localhost:3000. Point out the mode badge: it should say *Live* (it probes the
   aggregator at `localhost:8084`). If it says *Demo*, the aggregator isn't reachable.
3. **Place a real order from a terminal:**
   ```bash
   curl -i -X POST http://localhost:8080/orders/place -H 'Content-Type: application/json' \
     -d '{"customerId":"DEMO","items":"sku-001,sku-002"}'
   ```
   Show the `X-Trace-Id` response header. That ID was generated once at the gateway.
4. **Show the raw trace:** `curl -s http://localhost:8084/traces/<id> | jq` shows six entries, three services, one trace ID,
   sorted by timestamp.
5. **Paste the ID into the dashboard** and click Trace. Walk through the waterfall: gateway encloses orders, which encloses
   inventory.
6. **Bring your own ID** (the gateway honors a well-formed one):
   ```bash
   ID=$(uuidgen | tr A-Z a-z)
   curl -s -o /dev/null -X POST http://localhost:8080/orders/place -H "X-Trace-Id: $ID" \
     -H 'Content-Type: application/json' -d '{"customerId":"DEMO","items":"sku-1"}'
   curl -s http://localhost:8084/traces/$ID | jq length     # 6
   ```
7. **Resilience:** `docker compose pause aggregator`, place another order with the curl from step 3. It still returns
   immediately. `docker compose unpause aggregator`, wait a few seconds, then fetch that trace. It should arrive: in every Phase 2 freeze run, all entries reached the aggregator after it resumed (see `bench/RESULTS.md`).
8. **Shipper counters:** `curl -s 'localhost:8080/actuator/metrics/log.shipper.events?tag=outcome:shipped' | jq '.measurements[0].value'`
   (try `dropped` and `failed` too).
9. **Wrap up:** `docker compose down -v`.

Note: "Simulate a request" in the dashboard places a real order only when the dashboard is built with
`VITE_GATEWAY_URL` (see `dashboard/.env.example`). The compose build sets only the aggregator URL, so in compose it
generates a simulated trace instead. Use the curl steps above for real traces.
