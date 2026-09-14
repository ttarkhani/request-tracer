cd /Users/tahatarkhani/Documents/GitHub/request-tracer

cat > README.md << 'EOF'
# 🔍 Request Tracer

Lightweight distributed tracing tool that shows how a single request flows across multiple microservices — a scoped-down, from-scratch version of tools like Jaeger or Zipkin, built to demonstrate the actual mechanics of correlation ID propagation, centralized structured logging, and trace visualization across a real service mesh.

This README reflects the current, verified state of the build. The full logging pipeline is complete and tested end-to-end: a correlation ID generated once, propagated across real HTTP hops, with every service shipping real timestamped log entries to a central aggregator — queryable by trace ID either via `curl` or through a working React dashboard that renders the result as a proportional waterfall timeline. Docker Compose is the one piece still outstanding, called out explicitly below rather than described as if it exists.

## Features

- **Correlation ID generation & propagation** — the API Gateway generates a UUID once per incoming request and attaches it as an `X-Trace-Id` header; every downstream service reads that header off the incoming request and forwards the *same* ID on its own outgoing call, rather than generating a new one
- **Four independent Spring Boot services** — API Gateway (entry point, port 8080) → Orders Service (port 8081) → Inventory Service (port 8082), plus a Log Aggregator (port 8084) all three ship logs to
- **Centralized structured logging** — every service ships two real, timestamped log entries per request (received / completed) to the aggregator, tagged with the shared trace ID
- **React dashboard with a live waterfall view** — paste a trace ID and the dashboard fetches it from the aggregator (`GET /traces/{traceId}`, the same endpoint `curl` uses) and renders every hop as a horizontal timeline, positioned by its real timestamp offset. The aggregator has CORS explicitly enabled for this — something `curl` never needed, since cross-origin restrictions are enforced by browsers, not the server
- **Genuine end-to-end latency, calculated from real data** — actual request duration computed from stored timestamps, never estimated (see Real metrics)
- **Best-effort logging, hard dependency on nothing but itself** — each service ships its logs inside a try/catch; if the aggregator is unreachable, the core order flow still completes successfully (proven live, unintentionally, during debugging — see Challenges) while logging fails gracefully and console output remains the fallback
- **In-memory, thread-safe trace store** — the aggregator holds logs in a `ConcurrentHashMap<traceId, List<LogEntry>>` using thread-safe collections, since multiple services can post logs for the same or different traces at effectively the same time

## Tech stack

| Layer | Tech |
|---|---|
| Services | Java 17 target bytecode (built and run on JDK 25), Spring Boot 3.2.0 |
| Inter-service HTTP calls | Spring `RestTemplate` |
| Build | Maven 3.9.16 |
| Correlation ID | `java.util.UUID`, propagated via a custom `X-Trace-Id` header |
| Log storage | In-memory `ConcurrentHashMap` + `CopyOnWriteArrayList` (no database — by design, for MVP scope) |
| Timestamps | `System.currentTimeMillis()`, captured at request-received and request-completed for every hop |
| Dashboard | React (Vite's official `react` template), plain CSS, native `fetch` — no chart library |
| Dashboard build tool | Vite, npm |

Installed and ready, not yet integrated: Docker via Rancher Desktop (for Compose).

## Real metrics

Measured on this project, not estimated.

| Metric | Result |
|---|---|
| Log entries generated per traced request | 6 (2 per service × 3 services: Gateway, Orders, Inventory) |
| Correlation ID propagation across the full chain | Confirmed — one identical UUID across all 3 service logs *and* all 6 aggregator entries, on every real request tested |
| Aggregator store + retrieve round trip | Confirmed — POST creates an entry, GET returns the exact match; verified standalone before any service was wired to send it real traffic |
| Dashboard correctly renders a live trace | Confirmed on 2 separate real requests, both matching the aggregator's raw JSON exactly |
| Service cold-start time | measured across 15+ real startups over the course of the build; range ~1.06s–1.6s |

**One real traced request, broken down in full — the data behind an end-to-end figure:**

| Time offset | Service | Event |
|---|---|---|
| +0ms | api-gateway | Received order request |
| +6ms | orders-service | Received order request |
| +101ms | inventory-service | Received inventory check request |
| +135ms | inventory-service | Completed - inventory check finished |
| +155ms | orders-service | Completed - response received from Inventory |
| +160ms | api-gateway | Completed - response received from Orders |

**Three real, unedited end-to-end totals, captured at different points in the build:**

| Run | Total duration | Context |
|---|---|---|
| 1 | 160ms | First full-pipeline test, verified via `curl` before the dashboard existed |
| 2 | 135ms | First trace rendered in the dashboard |
| 3 | 23ms | Second dashboard run, same code path, run shortly after |

These three are shown as-is rather than averaged into one headline number — with only 3 samples and this much spread, an average would imply more statistical confidence than actually exists. The spread itself is a real, honest finding: it's consistent with JVM warm-up (JIT compilation and connection setup typically make the first several requests after a service starts slower than steady state), though that explanation hasn't been confirmed with profiling — it's the likely cause, stated as a hypothesis, not a verified fact.

**An honest caveat on per-hop breakdowns:** each service ships its own log entries synchronously, via a blocking HTTP call, before moving on — so every gap in the detailed table above includes both real work *and* the time spent shipping the previous log entry to the aggregator. This is most visible in Inventory's own ~34ms internal gap in that run: Inventory does no downstream work between "received" and "completed," so that gap is essentially pure logging overhead, not business logic. This is a known, disclosable characteristic of synchronous instrumentation — the act of measuring adds to what's measured — not a bug. Total end-to-end figures are unaffected by this, since they're simply first-timestamp-to-last-timestamp regardless of what happened in between.

## Setup

**Requirements:** Java 17+ (tested on Java 25), Maven 3.9+, Node.js 18+ (tested on v24.21.0) and npm

```bash
git clone https://github.com/ttarkhani/request-tracer.git
cd request-tracer
```

**Run all four backend services, one per terminal** (start the aggregator first, though order isn't strictly required — each service degrades gracefully if it's unreachable):

```bash
cd log-aggregator && mvn spring-boot:run
```
```bash
cd services/api-gateway && mvn spring-boot:run
```
```bash
cd services/orders && mvn spring-boot:run
```
```bash
cd services/inventory && mvn spring-boot:run
```

Wait for all four to print `Started ...Application in X seconds` before testing.

**Then, in a 5th terminal, start the dashboard:**

```bash
cd dashboard
npm install
npm run dev
```

Open `http://localhost:5173` in a browser.

**Trigger a full traced request:**

```bash
curl -X POST http://localhost:8080/orders/place \
  -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-123", "items": "sku-001,sku-002"}'
```

**Then either query it directly:**

```bash
curl http://localhost:8084/traces/YOUR_TRACE_ID
```

**Or paste the trace ID into the dashboard's search box and click Trace** — same 6 log entries, rendered as a proportional waterfall timeline instead of raw JSON.

## API

| Endpoint | Service (port) | Description |
|---|---|---|
| `POST /orders/place` | API Gateway (8080) | Entry point. Generates the correlation ID, calls Orders, ships logs |
| `POST /api/orders` | Orders Service (8081) | Receives the correlation ID, calls Inventory, ships logs |
| `POST /api/inventory/check` | Inventory Service (8082) | Receives the correlation ID, ships logs, returns a stock result |
| `POST /logs` | Log Aggregator (8084) | Receives one structured log entry, stores it keyed by trace ID |
| `GET /traces/{traceId}` | Log Aggregator (8084) | Returns every log entry stored for a given trace ID; CORS-enabled, consumed by both `curl` and the dashboard |

## Challenges & how they were solved

- **Files created in the wrong nested folder** — manually right-clicking to create files in VS Code's Explorer doesn't enforce a project's expected structure the way a scaffolding tool would; several application classes and `pom.xml` files initially landed one folder too deep. Caught by running `find` immediately after creating each file and checking the real path against the expected one, instead of assuming it landed correctly.
- **The identical Maven error from two unrelated causes** — "No plugin found for prefix 'spring-boot'" showed up twice: once from a genuinely misplaced `pom.xml`, and later from a `cd` typo that silently left the shell in the wrong directory so Maven never saw a `pom.xml` at all. Same error text, different root cause both times — fixed by verifying the actual current directory and file locations directly rather than trusting the error message alone.
- **VS Code repeatedly flagging correct files as "package mismatch"** — the Java language server's project index fell out of sync each time a new Maven module was added, flagging valid files as misplaced. Confirmed each time (via `head` and `find`) that the file's real package declaration and location matched, meaning Maven — the tool actually compiling and running the code — was unaffected; the editor-only issue was cleared with `Java: Clean Java Language Server Workspace`.
- **A service silently not running** — a chain test failed with `curl: (7) Couldn't connect to server`. Rather than guessing, `lsof -i :PORT` across all four ports showed exactly which service was actually still listening — one had been stopped earlier and never restarted, not a code problem at all.
- **Two services' identically-named `LogEntry.java` files ended up with each other's content** — Gateway and the Log Aggregator each needed their own `LogEntry.java` (same shape, different package, deliberately not shared between services). Pasting into a same-named file open in a different editor tab swapped their contents — both compiled fine in isolation but failed at runtime in confusing, different-looking ways (one as a package-mismatch class-loading error, one as a `400 Bad Request` on every log the aggregator tried to store). Root-caused by `cat`-ing each file's actual contents directly rather than trusting `find`'s path-only confirmation, and prevented for good afterward by writing file contents straight from the terminal via heredoc (`cat > file << 'EOF' ... EOF`) instead of the editor, removing the tab-mixup risk entirely.
- **A newly created file compiled as if it were empty** — `LogAggregatorApplication.java` produced "Unable to find a suitable main class" even though `find` confirmed it existed at the right path. `cat`-ing it directly showed zero bytes: the content had been pasted into the editor but never actually saved before a later terminal `mv` command relocated the file — `mv` operates on-disk, so it silently moved an empty file, discarding the unsaved buffer.
- **Stale compiled output masking a real fix** — after correcting file content, Maven sometimes reported `Nothing to compile - all classes are up to date` and reused old, incorrect `.class` files from a previous broken state, hiding whether a fix had actually worked. Solved by running `mvn clean spring-boot:run` (which deletes `target/` before rebuilding) whenever a fix didn't seem to take effect, rather than assuming a persisting error meant the fix itself had failed.
- **Waterfall markers all clustering near the same spot despite correct percentage math** — each marker's horizontal position was calculated correctly from the start (`(log.timestamp − startTime) / totalDuration`), confirmed by the accurate `+Nms` label rendered next to every entry, yet visually every dot landed in nearly the same place. The cause was CSS, not JavaScript: the track element markers were positioned within used a CSS Grid `1fr` column, which resolved to a much narrower rendered width than intended for an otherwise-empty box, compressing every percentage into a tiny visual range. Fixed by giving the track an explicit fixed pixel width instead of relying on implicit `1fr` sizing — a reminder that a calculation can be provably correct while its container silently undermines the result.

## Known limitations (in progress)

- No Docker Compose yet — all four backend services and the dashboard are started manually, five separate terminals
- Chain currently covers Gateway → Orders → Inventory; a planned Shipping service was deliberately deferred to prove correlation-ID propagation with a simpler 2-hop chain first, and hasn't been added back in yet
- Dashboard requires manually copying a trace ID from a service's terminal output or a `curl` response — there's no list of recent traces or live feed to browse
- Per-hop latency figures include synchronous logging overhead, not just business logic time — see Real metrics for the full explanation; only total end-to-end figures are unaffected by this
- No automated tests — all verification so far is manual curl + log inspection, plus visual confirmation in the dashboard
- No load testing yet — all real metrics above come from single-request tests, not concurrent traffic
- No persistence — the aggregator's trace store is in-memory only; restarting it clears all stored traces
EOF