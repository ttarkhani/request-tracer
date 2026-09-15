# 🔍 Request Tracer

Lightweight distributed tracing tool that shows how a single request flows across multiple microservices — a scoped-down, from-scratch version of tools like Jaeger or Zipkin, built to demonstrate the actual mechanics of correlation ID propagation, centralized structured logging, trace visualization, and containerized deployment across a real service mesh.

This README reflects the current, verified state of the build. The full pipeline is complete and tested end-to-end: a correlation ID generated once, propagated across real HTTP hops, with every service shipping real timestamped log entries to a central aggregator — queryable by trace ID via `curl` or through a working React dashboard that renders the result as a proportional waterfall timeline. The entire system — four Java services plus the dashboard — now also runs as five Docker containers with a single `docker compose up` command, networked entirely through container hostnames rather than `localhost`. What's genuinely still open: a Shipping service was deliberately deferred early on to de-risk the correlation-ID work first, and hasn't been added back in; there's no architecture diagram yet; and there are no automated or load tests. All called out honestly below rather than glossed over.

## Features

- **Correlation ID generation & propagation** — the API Gateway generates a UUID once per incoming request and attaches it as an `X-Trace-Id` header; every downstream service reads that header off the incoming request and forwards the *same* ID on its own outgoing call, rather than generating a new one
- **Four independent Spring Boot services** — API Gateway (entry point, port 8080) → Orders Service (port 8081) → Inventory Service (port 8082), plus a Log Aggregator (port 8084) all three ship logs to
- **Centralized structured logging** — every service ships two real, timestamped log entries per request (received / completed) to the aggregator, tagged with the shared trace ID
- **React dashboard with a live waterfall view** — paste a trace ID and the dashboard fetches it from the aggregator (`GET /traces/{traceId}`, the same endpoint `curl` uses) and renders every hop as a horizontal timeline, positioned by its real timestamp offset. The aggregator has CORS explicitly enabled for this — something `curl` never needed, since cross-origin restrictions are enforced by browsers, not the server
- **One-command Docker Compose startup** — all five pieces (4 Java services + dashboard) build and run together with `docker compose up`, networked via Compose's automatic service-name DNS rather than `localhost`. Each Java service uses a multi-stage Dockerfile (Maven build stage → slim JRE runtime stage); the dashboard builds its production React bundle and serves it via nginx
- **Environment-driven service URLs** — every inter-service URL is externalized to `application.yml` and injected via `@Value`, with Docker Compose overriding each one via environment variables (Spring Boot's built-in relaxed binding maps `ORDERS_SERVICE_URL` onto `orders.service.url` automatically). Locally, defaults fall back to `localhost`; in Docker, Compose points each service at its container hostname — same code, zero branching, two environments
- **Genuine end-to-end latency, calculated from real data** — actual request duration computed from stored timestamps, never estimated (see Real metrics)
- **Best-effort logging, hard dependency on nothing but itself** — each service ships its logs inside a try/catch; if the aggregator is unreachable, the core order flow still completes successfully (proven live, unintentionally, during debugging — see Challenges) while logging fails gracefully and console output remains the fallback
- **In-memory, thread-safe trace store** — the aggregator holds logs in a `ConcurrentHashMap<traceId, List<LogEntry>>` using thread-safe collections, since multiple services can post logs for the same or different traces at effectively the same time

## Tech stack

| Layer | Tech |
|---|---|
| Services | Java 17 target bytecode (built and run on JDK 25 locally, JDK 17 inside containers), Spring Boot 3.2.0 |
| Inter-service HTTP calls | Spring `RestTemplate` |
| Build | Maven 3.9.16 |
| Correlation ID | `java.util.UUID`, propagated via a custom `X-Trace-Id` header |
| Log storage | In-memory `ConcurrentHashMap` + `CopyOnWriteArrayList` (no database — by design, for MVP scope) |
| Timestamps | `System.currentTimeMillis()`, captured at request-received and request-completed for every hop |
| Dashboard | React (Vite's official `react` template), plain CSS, native `fetch` — no chart library |
| Dashboard build tool | Vite, npm |
| Containerization | Docker (via Rancher Desktop locally), multi-stage Dockerfiles per service, Docker Compose for orchestration |

## Real metrics

Measured on this project, not estimated.

| Metric | Result |
|---|---|
| Log entries generated per traced request | 6 (2 per service × 3 services: Gateway, Orders, Inventory) |
| Correlation ID propagation across the full chain | Confirmed — one identical UUID across all 3 service logs *and* all 6 aggregator entries, on every real request tested, both natively and through Docker |
| Aggregator store + retrieve round trip | Confirmed — POST creates an entry, GET returns the exact match; verified standalone before any service was wired to send it real traffic |
| Dashboard correctly renders a live trace | Confirmed on multiple real requests, natively and through its own nginx container, all matching the aggregator's raw JSON exactly |
| Docker Compose build + startup | Confirmed — all 5 images build cleanly, all 5 containers reach a running state, zero crash loops or restarts, verified independently via Rancher Desktop's own container list |
| Service cold-start time (native, `mvn spring-boot:run`) | measured across 15+ real startups over the course of the build; range ~1.06s–1.6s |
| Service cold-start time (inside Docker containers) | ~11.9s–12.6s across all 4 Java services in one full `docker compose up --build` run — notably slower than native, consistent with containerized JVM + virtualization overhead on Mac; single-build sample, not yet repeated across multiple builds |

**One real traced request, broken down in full — the data behind an end-to-end figure:**

| Time offset | Service | Event |
|---|---|---|
| +0ms | api-gateway | Received order request |
| +6ms | orders-service | Received order request |
| +101ms | inventory-service | Received inventory check request |
| +135ms | inventory-service | Completed - inventory check finished |
| +155ms | orders-service | Completed - response received from Inventory |
| +160ms | api-gateway | Completed - response received from Orders |

**Three real, unedited native end-to-end totals, captured at different points in the build:**

| Run | Total duration | Context |
|---|---|---|
| 1 | 160ms | First full-pipeline test, verified via `curl` before the dashboard existed |
| 2 | 135ms | First trace rendered in the dashboard |
| 3 | 23ms | Second dashboard run, same code path, run shortly after |

**Seven real, unedited end-to-end totals, captured through Docker Compose in one continuous session:**

| Run | Total duration | Context |
|---|---|---|
| 1 | 1565ms | First request immediately after `docker compose up --build` — fully cold |
| 2 | 107ms | Second request |
| 3 | 77ms | Third request |
| 4 | 86ms | Fourth request |
| 5 | 70ms | Fifth request |
| 6 | 119ms | Sixth request |
| 7 | 64ms | Seventh request |

Runs 2–7 cluster tightly in a **64–119ms band, averaging ~87ms** — in the same broad range as the native results above. Run 1 sits at more than 13× that average, isolated and non-repeating. Read together, this is a clean, specific finding rather than a vague one: Docker Compose does not meaningfully slow down steady-state request latency; it adds a one-time warm-up cost to the very first request after startup, most likely compounding JVM JIT warm-up (the same effect visible natively in the 160ms→23ms spread above) with Docker's own first-time container-to-container DNS resolution and connection setup. That compound explanation is inferred from the shape of the data, not confirmed with a profiler — stated here as the likely cause, not a verified fact.

Neither set of totals is averaged into one blended headline number across environments — native and Docker measure genuinely different things (direct process-to-process calls vs. calls routed through Docker's virtual network on Mac), and collapsing them would hide a real, honest distinction rather than reveal one.

**An honest caveat on per-hop breakdowns:** each service ships its own log entries synchronously, via a blocking HTTP call, before moving on — so every gap in the detailed table above includes both real work *and* the time spent shipping the previous log entry to the aggregator. This is most visible in Inventory's own ~34ms internal gap in that run: Inventory does no downstream work between "received" and "completed," so that gap is essentially pure logging overhead, not business logic. This is a known, disclosable characteristic of synchronous instrumentation — the act of measuring adds to what's measured — not a bug. Total end-to-end figures are unaffected by this, since they're simply first-timestamp-to-last-timestamp regardless of what happened in between.

## Setup

### Option 1: Docker Compose (recommended — one command, no local Java/Node/Maven needed)

**Requirements:** Docker (this project was built and tested against Rancher Desktop on macOS; Docker Desktop works identically)

```bash
git clone https://github.com/ttarkhani/request-tracer.git
cd request-tracer
docker compose up --build
```

That single command builds all 5 images (4 Java services + dashboard) and starts all 5 containers, networked together automatically. The first build takes a few minutes; subsequent runs are much faster. Wait for all five services to log some form of "started" before testing.

- Dashboard: `http://localhost:3000`
- API Gateway: `http://localhost:8080`
- Log Aggregator: `http://localhost:8084`

Stop everything with `Ctrl+C`, or `docker compose down` from another terminal.

### Option 2: Run natively (five terminals, useful for iterating on one service at a time)

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

### Triggering and viewing a trace (works the same under either option)

```bash
curl -X POST http://localhost:8080/orders/place \
  -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-123", "items": "sku-001,sku-002"}'
```

**Then either query it directly:**

```bash
curl http://localhost:8084/traces/YOUR_TRACE_ID
```

**Or paste the trace ID into the dashboard's search box and click Trace** — same 6 log entries, rendered as a proportional waterfall timeline instead of raw JSON. (Dashboard URL is `:3000` under Docker Compose, `:5173` when run natively via Vite.)

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
- **Waterfall markers all clustering near the same spot despite correct percentage math** — each marker's horizontal position was calculated correctly from the start (`(log.timestamp − startTime) / totalDuration`), confirmed by the accurate `+Nms` label rendered next to every entry, yet visually every dot landed in nearly the same place. The cause was CSS, not JavaScript: the track element the markers were positioned within used a CSS Grid `1fr` column, which resolved to a much narrower rendered width than intended for an otherwise-empty box, compressing every percentage into a tiny visual range. Fixed by giving the track an explicit fixed pixel width instead of relying on implicit `1fr` sizing — a reminder that a calculation can be provably correct while its container silently undermines the result.
- **`localhost` URLs, correct on the host machine, would have silently broken inside Docker** — every inter-service call was originally hardcoded to `http://localhost:PORT`, which worked because all processes shared one Mac's network. Inside Docker, each container gets its own isolated `localhost` that only refers to itself, so Gateway's container calling `localhost:8081` would ask itself for Orders, not find it — a connection-refused failure that would have looked identical to the plain "service isn't running" issue hit earlier, for an entirely different reason. Fixed proactively, before the failure could happen: all three service-to-service URLs were externalized to `application.yml` and injected via `@Value`, tested locally to confirm zero behavior change, *then* Dockerized — with Compose overriding each URL via environment variables pointing at container service names (`http://orders:8081` instead of `http://localhost:8081`) rather than requiring any Java code change per environment.
- **A 1565ms first request through Docker, initially alarming, turned out to be a one-time cost, not a persistent problem** — the very first traced request after `docker compose up --build` measured over 13× slower than every request after it. Rather than assume something was broken (or silently average it away), six more identical requests were run back-to-back: 107ms, 77ms, 86ms, 70ms, 119ms, 64ms — a tight, stable band consistent with the same JVM warm-up effect already observed natively, compounding with Docker's own first-time container DNS resolution. Treated as a genuine finding worth documenting with real repeated data (see Real metrics), not a bug worth "fixing" by suppressing the outlier.

## Known limitations (in progress)

- No architecture diagram yet — the original goal included one; the system described above is accurate, but there's no visual accompanying it yet
- Chain currently covers Gateway → Orders → Inventory; a planned Shipping service was deliberately deferred to prove correlation-ID propagation with a simpler 2-hop chain first, and hasn't been added back in yet
- Dashboard requires manually copying a trace ID from a service's terminal output or a `curl` response — there's no list of recent traces or live feed to browse
- Per-hop latency figures include synchronous logging overhead, not just business logic time — see Real metrics for the full explanation; only total end-to-end figures are unaffected by this
- No automated tests — all verification so far is manual curl + log inspection, plus visual confirmation in the dashboard, both natively and in Docker
- No load testing yet — all real metrics above come from single-request tests, not concurrent traffic
- No persistence — the aggregator's trace store is in-memory only; restarting it (or its container) clears all stored traces
- Docker cold-start timing (~12s per service) is from a single `docker compose up --build` run, not repeated across multiple fresh builds — treated as a preliminary figure, not a fully confirmed average