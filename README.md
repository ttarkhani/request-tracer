# 🔍 Request Tracer

Lightweight distributed tracing tool that shows how a single request flows across multiple microservices — a scoped-down, from-scratch version of tools like Jaeger or Zipkin, built to demonstrate the actual mechanics of correlation ID propagation and centralized structured logging across a real service mesh.

This README reflects the current, verified state of the build. The full logging pipeline is complete and tested end-to-end: a correlation ID generated once, propagated across real HTTP hops, with every service shipping real timestamped log entries to a central aggregator that can be queried for a single request's full path — including genuine, measured end-to-end latency. The dashboard and Docker Compose setup are still in progress and are called out explicitly below rather than described as if they already exist.

## Features

- **Correlation ID generation & propagation** — the API Gateway generates a UUID once per incoming request and attaches it as an `X-Trace-Id` header; every downstream service reads that header off the incoming request and forwards the *same* ID on its own outgoing call, rather than generating a new one
- **Four independent Spring Boot services** — API Gateway (entry point, port 8080) → Orders Service (port 8081) → Inventory Service (port 8082), plus a Log Aggregator (port 8084) all three ship logs to
- **Centralized structured logging** — every service ships two real, timestamped log entries per request (received / completed) to the aggregator, tagged with the shared trace ID; a full request's path is now queryable as one JSON list, not reconstructed by hand from separate terminals
- **Genuine end-to-end latency, calculated from real data** — with real timestamps stored per hop, actual request duration is computed from the data itself (see Real metrics), not estimated or eyeballed from console output
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

Installed and ready, not yet integrated: Node.js v24.21.0 / npm 11.19.0 (for the dashboard), Docker via Rancher Desktop (for Compose).

## Real metrics

Measured on this project, not estimated.

| Metric | Result |
|---|---|
| End-to-end request latency, one real traced request (first log timestamp → last) | **160ms** |
| Log entries generated per traced request | 6 (2 per service × 3 services: Gateway, Orders, Inventory) |
| Correlation ID propagation across the full chain | Confirmed — one identical UUID across all 3 service logs *and* all 6 aggregator entries for a single request |
| Aggregator store + retrieve round trip | Confirmed — POST creates an entry, GET returns the exact match; verified standalone before any service was wired to send it real traffic |
| Service cold-start time | measured across 15+ real startups over the course of the build; range ~1.06s–1.6s |

**One real traced request, in full — the actual data behind the 160ms figure:**

| Time offset | Service | Event |
|---|---|---|
| +0ms | api-gateway | Received order request |
| +6ms | orders-service | Received order request |
| +101ms | inventory-service | Received inventory check request |
| +135ms | inventory-service | Completed - inventory check finished |
| +155ms | orders-service | Completed - response received from Inventory |
| +160ms | api-gateway | Completed - response received from Orders |

**An honest caveat on the per-hop breakdown above:** each service ships its own log entries synchronously, via a blocking HTTP call, before moving on — so every gap in the table includes both real work *and* the time spent shipping the previous log entry to the aggregator. This is most visible in Inventory's own ~34ms internal gap: Inventory does no downstream work between "received" and "completed," so that gap is essentially pure logging overhead, not business logic. This is a known, disclosable characteristic of synchronous instrumentation — the act of measuring adds to what's measured — not a bug. The **total 160ms end-to-end figure is unaffected by this** and remains a clean, trustworthy number, since it's simply first-timestamp-to-last-timestamp regardless of what happened in between.

## Setup

**Requirements:** Java 17+ (tested on Java 25), Maven 3.9+

```bash
git clone https://github.com/ttarkhani/request-tracer.git
cd request-tracer
```

**Run all four services, one per terminal** (start the aggregator first, though order isn't strictly required — each service degrades gracefully if it's unreachable):

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

**Trigger a full traced request:**

```bash
curl -X POST http://localhost:8080/orders/place \
  -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-123", "items": "sku-001,sku-002"}'
```

**Then retrieve the complete trace** (grab the trace ID from any service's terminal output):

```bash
curl http://localhost:8084/traces/YOUR_TRACE_ID
```

Returns all 6 log entries for that request, in order, each with a real timestamp.

## API

| Endpoint | Service (port) | Description |
|---|---|---|
| `POST /orders/place` | API Gateway (8080) | Entry point. Generates the correlation ID, calls Orders, ships logs |
| `POST /api/orders` | Orders Service (8081) | Receives the correlation ID, calls Inventory, ships logs |
| `POST /api/inventory/check` | Inventory Service (8082) | Receives the correlation ID, ships logs, returns a stock result |
| `POST /logs` | Log Aggregator (8084) | Receives one structured log entry, stores it keyed by trace ID |
| `GET /traces/{traceId}` | Log Aggregator (8084) | Returns every log entry stored for a given trace ID |

## Challenges & how they were solved

- **Files created in the wrong nested folder** — manually right-clicking to create files in VS Code's Explorer doesn't enforce a project's expected structure the way a scaffolding tool would; several application classes and `pom.xml` files initially landed one folder too deep. Caught by running `find` immediately after creating each file and checking the real path against the expected one, instead of assuming it landed correctly.
- **The identical Maven error from two unrelated causes** — "No plugin found for prefix 'spring-boot'" showed up twice: once from a genuinely misplaced `pom.xml`, and later from a `cd` typo that silently left the shell in the wrong directory so Maven never saw a `pom.xml` at all. Same error text, different root cause both times — fixed by verifying the actual current directory and file locations directly rather than trusting the error message alone.
- **VS Code repeatedly flagging correct files as "package mismatch"** — the Java language server's project index fell out of sync each time a new Maven module was added, flagging valid files as misplaced. Confirmed each time (via `head` and `find`) that the file's real package declaration and location matched, meaning Maven — the tool actually compiling and running the code — was unaffected; the editor-only issue was cleared with `Java: Clean Java Language Server Workspace`.
- **A service silently not running** — a chain test failed with `curl: (7) Couldn't connect to server`. Rather than guessing, `lsof -i :PORT` across all four ports showed exactly which service was actually still listening — one had been stopped earlier and never restarted, not a code problem at all.
- **Two services' identically-named `LogEntry.java` files ended up with each other's content** — Gateway and the Log Aggregator each needed their own `LogEntry.java` (same shape, different package, deliberately not shared between services). Pasting into a same-named file open in a different editor tab swapped their contents — both compiled fine in isolation but failed at runtime in confusing, different-looking ways (one as a package-mismatch class-loading error, one as a `400 Bad Request` on every log the aggregator tried to store). Root-caused by `cat`-ing each file's actual contents directly rather than trusting `find`'s path-only confirmation, and prevented for good afterward by writing file contents straight from the terminal via heredoc (`cat > file << 'EOF' ... EOF`) instead of the editor, removing the tab-mixup risk entirely.
- **A newly created file compiled as if it were empty** — `LogAggregatorApplication.java` produced "Unable to find a suitable main class" even though `find` confirmed it existed at the right path. `cat`-ing it directly showed zero bytes: the content had been pasted into the editor but never actually saved before a later terminal `mv` command relocated the file — `mv` operates on-disk, so it silently moved an empty file, discarding the unsaved buffer.
- **Stale compiled output masking a real fix** — after correcting file content, Maven sometimes reported `Nothing to compile - all classes are up to date` and reused old, incorrect `.class` files from a previous broken state, hiding whether a fix had actually worked. Solved by running `mvn clean spring-boot:run` (which deletes `target/` before rebuilding) whenever a fix didn't seem to take effect, rather than assuming a persisting error meant the fix itself had failed.

## Known limitations (in progress)

- No dashboard yet — no UI exists; exploring a trace currently means a manual `curl` to `/traces/{traceId}`
- No Docker Compose yet — all four services are started manually, one per terminal
- Chain currently covers Gateway → Orders → Inventory; a planned Shipping service was deliberately deferred to prove correlation-ID propagation with a simpler 2-hop chain first, and hasn't been added back in yet
- Per-hop latency figures include synchronous logging overhead, not just business logic time — see Real metrics for the full explanation; only the total end-to-end figure is unaffected by this
- No automated tests — all verification so far is manual curl + log inspection
- No load testing yet — all real metrics above come from single-request tests, not concurrent traffic
- No persistence — the aggregator's trace store is in-memory only; restarting it clears all stored traces