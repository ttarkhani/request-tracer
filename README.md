# 🔍 Request Tracer

Lightweight distributed tracing tool that shows how a single request flows across multiple microservices — a scoped-down, from-scratch version of tools like Jaeger or Zipkin, built to demonstrate the actual mechanics of correlation ID propagation across a real service mesh.

This README reflects the current, verified state of the build, not the full feature set from the original design doc. The hardest part — a correlation ID generated once and automatically propagated across real HTTP hops between independent services — is built and tested end-to-end. The log aggregator, dashboard, and Docker Compose setup are still in progress and are called out explicitly below rather than described as if they already exist.

## Features

- **Correlation ID generation & propagation** — the API Gateway generates a UUID once per incoming request and attaches it as an `X-Trace-Id` header; every downstream service reads that header off the incoming request and forwards the *same* ID on its own outgoing call, rather than generating a new one
- **Three independent Spring Boot microservices** simulating a real order flow: API Gateway (entry point, port 8080) → Orders Service (port 8081) → Inventory Service (port 8082)
- **Per-hop structured console logging** — every service logs its action tagged with the correlation ID, so one request's path can currently be reconstructed by reading three terminals side by side
- **Verified, not assumed, propagation** — confirmed by tracing one real UUID through all three services' logs for a single live request (see Real metrics)

## Tech stack

| Layer | Tech |
|---|---|
| Services | Java 17 target bytecode (built and run on JDK 25), Spring Boot 3.2.0 |
| Inter-service HTTP calls | Spring `RestTemplate` |
| Build | Maven 3.9.16 |
| Correlation ID | `java.util.UUID`, propagated via a custom `X-Trace-Id` header |

Installed and ready, not yet integrated: Node.js v24.21.0 / npm 11.19.0 (for the dashboard), Docker via Rancher Desktop (for Compose).

## Real metrics

Measured on this project, not estimated.

| Metric | Result |
|---|---|
| Full 3-hop automatic correlation ID propagation | 1 / 1 — one UUID (`10a2b976-d188-...`) confirmed identical across Gateway, Orders, and Inventory logs for a single request |
| Manual verification tests run during the build (curl + log inspection) | 7 / 7 passed (100%) |
| Services run and tested concurrently | 3 / 3, confirmed via `lsof -i` |
| Service cold-start time, across 8 measured startups | avg ~1.19s (range 1.056s–1.458s) |

**The propagation test, in detail:**
- A request was sent to the Gateway with no trace header supplied — the Gateway had to generate one itself
- Gateway generated `10a2b976-d188-4923-b7e2-36a5dcb72567`, logged it, and called Orders
- The identical ID appeared in Orders' log immediately after
- The identical ID appeared in Inventory's log immediately after that
- No manual step between the first and last log line — the ID traveled automatically across two real network hops

**Not yet measured, on purpose:**
- **End-to-end request latency** — not reported. The console logs used above don't carry precise, comparable timestamps (some nearby timestamps are one-time servlet-initialization cost, not steady-state per-request time), so no number is given rather than estimating one. Real latency will be captured once explicit duration tracking is added.
- **Log volume / logs per trace** — not applicable yet; no aggregator exists to collect or count them
- **Throughput under load** — not tested yet

## Setup

**Requirements:** Java 17+ (tested on Java 25), Maven 3.9+

```bash
git clone https://github.com/ttarkhani/request-tracer.git
cd request-tracer
```

**Run each service, one per terminal:**

```bash
cd services/api-gateway && mvn spring-boot:run
```
```bash
cd services/orders && mvn spring-boot:run
```
```bash
cd services/inventory && mvn spring-boot:run
```

Wait for all three to print `Started ...Application in X seconds` before testing.

**Trigger a full traced request:**

```bash
curl -X POST http://localhost:8080/orders/place \
  -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-123", "items": "sku-001,sku-002"}'
```

Check all three terminals — the same trace ID should appear in each.

## API

| Endpoint | Service (port) | Description |
|---|---|---|
| `POST /orders/place` | API Gateway (8080) | Entry point. Generates the correlation ID, calls Orders |
| `POST /api/orders` | Orders Service (8081) | Receives the correlation ID, calls Inventory with it |
| `POST /api/inventory/check` | Inventory Service (8082) | Receives the correlation ID, logs it, returns a stock result |

## Challenges & how they were solved

- **Files created in the wrong nested folder** — manually right-clicking to create files in VS Code's Explorer doesn't enforce a project's expected structure the way a scaffolding tool would; two application classes and one `pom.xml` initially landed one folder too deep. Caught by running `find` immediately after creating each file and checking the real path against the expected one, instead of assuming it landed correctly.
- **The identical Maven error from two unrelated causes** — "No plugin found for prefix 'spring-boot'" showed up twice: once from a genuinely misplaced `pom.xml`, and later from a `cd` typo that silently left the shell in the wrong directory so Maven never saw a `pom.xml` at all. Same error text, different root cause both times — fixed by verifying the actual current directory and file locations directly rather than trusting the error message alone.
- **VS Code repeatedly flagging correct files as "package mismatch"** — the Java language server's project index fell out of sync each time a new Maven module was added, flagging valid files as misplaced. Confirmed each time (via `head` and `find`) that the file's real package declaration and location matched, meaning Maven — the tool actually compiling and running the code — was unaffected; the editor-only issue was cleared with `Java: Clean Java Language Server Workspace`.
- **A service silently not running** — a chain test failed with `curl: (7) Couldn't connect to server` on port 8080. Rather than guessing, `lsof -i :8080/:8081/:8082` showed exactly which of the three services was actually still listening — Gateway had been stopped earlier and never restarted, not a code problem at all.

## Known limitations (in progress)

- No log aggregator yet — each service's logs live only in its own terminal; nothing is centralized or queryable by trace ID
- No dashboard yet — no UI exists; verifying a trace currently means manually reading three terminals side by side
- No Docker Compose yet — all three services are started manually, one per terminal
- Chain currently covers Gateway → Orders → Inventory; a planned Shipping service is not yet built
- No real end-to-end latency numbers — see Real metrics for why none are reported rather than estimated
- No automated tests — all verification so far is manual curl + log inspection
- No load testing yet