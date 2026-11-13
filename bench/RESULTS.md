# Results

Every number here comes from a command run in the 2026-11-12 session; each section names the command and
the raw-output path. JDK for every Java result is stated per section.

## Environment

- Host: Intel i7-9750H (12 logical CPUs), 32 GiB RAM, macOS 15.4.1, x86_64.
- Rancher Desktop VM (from `rdctl list-settings`, raw: `bench/results/env/rd-settings.json`):
  **2 CPUs, 6 GB, vmType vz, Kubernetes (k3s 1.36.4) enabled**. `docker info` NCPU/MemTotal are recorded
  per benchmark below.
- Test JDK: Temurin 17.0.21+9 (unpacked tarball, not installed).

## Step 0 — findings from the code read, checked against the running original code

Command: `bench/scripts/step0-native.sh <copy of HEAD 1c01c35> bench/results/step0`
(four services run natively from `mvn package` jars, JDK 17.0.21; no Docker).
Raw output: `bench/results/step0/step0-native.txt`, service logs in `bench/results/step0/logs/`.

| # | Hypothesis | Verdict | Evidence |
|---|---|---|---|
| a | Gateway ignores inbound `X-Trace-Id` | **Confirmed** | Sent `X-Trace-Id: 11111111-2222-3333-4444-555555555555`; `GET /traces/<that id>` → `[]`; gateway logged a fresh UUID. No `X-Trace-Id` response header either. |
| b | Missing header → null key → NPE 500 at aggregator, swallowed | **Confirmed** | `POST :8081/api/orders` without header → HTTP 200 to the caller; Orders logged `[null]` and `Failed to ship log to aggregator: 500`; aggregator logged `java.lang.NullPointerException`. Same for Inventory called directly. |
| b' | Orders forwards an *empty* `X-Trace-Id` to Inventory; Inventory's logs land under key `""` | **Confirmed** (was untested inference) | Inventory logged `[] Checking inventory`; aggregator logged `Stored log for trace  from inventory-service` twice. Those entries are unreachable: `GET /traces/` → 404. `POST /logs` with `"traceId":""` → 200 `stored`; with `null` → 500. |
| c | Log shipping is synchronous with no timeouts | **Confirmed** | Code: `new RestTemplate()` (no timeouts) called inline before/after the downstream call. Run: aggregator replaced by a socket that accepts and never answers → `curl --max-time 20` to the gateway exited 28 (timeout) after 20.0 s with no response. |
| d | Status always "success"; failed downstream leaves a dangling "received" | **Confirmed** | Inventory stopped → gateway returned HTTP 500; the trace held only `api-gateway Received` and `orders-service Received`, both `status: success`, no completion, no error marker. |
| e | Store unbounded/in-memory; GET returns arrival order | **Confirmed (order)**; unbounded by code read (`ConcurrentHashMap` with no eviction); growth measured in Phase 2 | Posted timestamps 300,100,200 → `GET` returned `[300,100,200]`. |

## Phase 1 — tests and CI

JDK for all Maven runs: Temurin 17.0.21+9. Docker for compose runs: `docker info` NCPU=2, MemTotal=6222663680
(`bench/results/env/docker-info.txt`).

### Unit / contract tests — `bench/scripts/verify-all.sh bench/results/phase1` (= `mvn -B verify` per module)

| Module | Surefire: tests run / failures / errors / skipped | Raw output |
|---|---|---|
| services/api-gateway | 18 / 0 / 0 / 0 | `bench/results/phase1/mvn-verify-api-gateway.txt` |
| services/orders | 4 / 0 / 0 / 0 | `bench/results/phase1/mvn-verify-orders.txt` |
| services/inventory | 4 / 0 / 0 / 0 | `bench/results/phase1/mvn-verify-inventory.txt` |
| log-aggregator | 32 / 0 / 0 / 0 | `bench/results/phase1/mvn-verify-log-aggregator.txt` |

(58 in total.) What they cover:
- Gateway (`GatewayContractTest`, real HTTP stubs for Orders and the aggregator): generated ID is a canonical UUID,
  sent to Orders as `X-Trace-Id`, exactly two entries shipped with it, Orders' body passed through; well-formed inbound
  ID honored (upper case normalized); 10 malformed inbound values replaced; CORS preflight + `Access-Control-Expose-Headers: X-Trace-Id`;
  Orders 500 passed through with the header. `GatewayDependencyDownTest` (real sockets): aggregator connection refused →
  200 with Orders' body; **aggregator hung → caller's request still blocked after 3 s (documents today's behavior)**;
  Orders unreachable → 502 with the trace ID.
- Orders / Inventory: same inbound ID forwarded (Orders → Inventory) and shipped twice; missing or blank header →
  a new UUID is generated at that hop (decision: start a new trace there rather than drop logs or ship a null/blank key;
  Orders forwards the new ID so Inventory's entries join it).
- Aggregator: null / blank / missing `traceId` → 400; reads sorted by timestamp; unknown ID → `[]`;
  concurrency: `@RepeatedTest(25)`, 16 threads × 200 traces × 5 entries = 16,000 entries per repetition, exact total and
  per-trace (80) counts.

### Negative controls (tests proven able to fail)

| Control | Result | Raw output |
|---|---|---|
| `TraceStore` temporarily switched to `HashMap` + `ArrayList` | 25 repetitions: 17 failures + 8 errors (`ConcurrentModificationException`, `ArrayIndexOutOfBoundsException`, wrong counts); reverted, `cmp`-identical | `bench/results/phase1/aggregator-concurrency-negative-control.txt` |
| Orders temporarily forwards a fresh UUID to Inventory (compose stack, Orders image rebuilt) | e2e 2 run, 2 failures: `Expected size: 6 but was: 4`; reverted (`cmp`-identical), rebuilt, green again | `bench/results/phase1/e2e-compose-negative-control.txt`, `bench/results/phase1/e2e-compose-after-revert.txt` |

### End-to-end against the real compose stack

`docker compose build && docker compose up -d && e2e/wait-for-stack.sh 240 && (cd e2e && mvn -B verify)`:
2 tests, 0 failures — one request → exactly 6 entries, 3 services × 2, one trace ID equal to the returned
`X-Trace-Id`, causal order gateway recv ≤ orders recv ≤ inventory recv ≤ inventory done ≤ orders done ≤ gateway done;
plus an inbound well-formed ID honored end to end. No container restarts/OOM. Raw: `bench/results/phase1/e2e-compose.txt`.
The e2e module uses plain JUnit 5 + `java.net.http`; **Testcontainers is not used** (CI starts compose itself).

### CI

`.github/workflows/ci.yml`: Java matrix (4 modules, Temurin 17, Maven cache, `mvn -B verify`), dashboard
(`npm ci`, lint, `npm test`, build), e2e (compose build/up, readiness poll, `mvn -B verify` in e2e/, logs on failure, `down -v`).
- actionlint 1.7.7 (Docker): 0 errors — `bench/results/phase1/actionlint.txt`.
- Each job's commands run locally: Java (above), dashboard `bench/results/phase1/dashboard-ci-local.txt` (all exit 0;
  Vitest 8/8), e2e (above). **Not run on GitHub Actions — status unknown until pushed.**

## Phase 2 — load test and asynchronous log shipping

### Method
- Load: k6 0.54.0 (`grafana/k6:0.54.0` image) on the compose network → `POST http://gateway:8080/orders/place`.
  Script `bench/loadtest.js` (open model, `constant-arrival-rate`), harness `bench/run-load.sh`, series
  `bench/run-ab-series.sh`, analysis `bench/analyze_phase2.py` → `bench/results/phase2/analysis.md`.
- Every run header (`*/header.txt`): `docker info` NCPU=2 MemTotal=6222663680, Kubernetes enabled = true,
  host on AC power (45 W adapter), `caffeinate -i -s` active. Stack restarted (`down -v` / `up -d`) before every run.
- Before = Phase 1 snapshot (synchronous `RestTemplate` shipping, no timeouts); after = Phase 2 snapshot
  (`LogShipper`). Same images for the whole series; runs alternated before, after, before, after…
- Knee (ramp on before code, 30 s per step, `bench/results/phase2/ramp-before/`): 100 req/s sustained
  (p50 41.9 ms, 6 dropped iterations); 150 req/s saturated (p50 1309.3 ms, achieved 134.6 req/s, 463 dropped).
  Fixed rate chosen: **50 req/s**. Each run: 60 s warm-up ramping 5→50 req/s (excluded), then 60 s at 50 req/s (reported).
  k6 request timeout 10 s, maxVUs 300.
- Scenario A: healthy. Scenario B: aggregator frozen with `docker pause` 15–35 s into the 60 s measured window
  (connections hang rather than being refused).
- No run had a container restart or OOM kill (`*/containers.txt`).

### Results (n = 3 per configuration; median (min–max))

| Scenario | Code | p50 ms | p95 ms | p99 ms | Throughput req/s | Error rate | Shipper drops |
|---|---|---|---|---|---|---|---|
| A healthy | before | 23.3 (23.1–24.6) | 142.1 (136.5–162.7) | 440.2 (377.0–457.4) | 50.02 (50.02–50.02) | 0.00% | n/a |
| A healthy | after | 10.6 (10.0–11.2) | 95.5 (53.2–109.4) | 240.5 (183.3–353.2) | 50.02 (50.02–50.02) | 0.00% | 0 |
| B aggregator frozen 20 s | before | 179.6 (104.5–224.8) | 10000.7 (10000.7–10000.8) | 10004.3 (10002.4–10005.4) | 41.40 (41.35–41.97) | 18.96% (17.79–19.35%) | n/a |
| B aggregator frozen 20 s | after | 10.8 (10.1–12.2) | 123.9 (118.4–221.2) | 326.3 (268.9–930.2) | 50.02 (49.95–50.02) | 0.00% | 0 |

In B-before, p95/p99 sit at k6's 10 s client timeout: requests hung on the frozen aggregator; k6 also could not
start 483–520 iterations (all 300 VUs blocked). Per-run rows, aggregator memory and sampled-trace checks:
`bench/results/phase2/analysis.md`; raw k6 summaries `bench/results/phase2/{A,B}/{before,after}-runN/k6-summary.json`.

Aggregator memory (`docker stats --no-stream`, before → after each run): A-before 137.4–150.7 → 166.2–189 MiB,
A-after 140.3–159.5 → 186.3–200.1 MiB, B-before 137.9–152.4 → 257–326 MiB, B-after 151–158.7 → 186–204.6 MiB.
The store keeps everything (after a run `/stats` reports e.g. 27,906 entries / 4,651 traces, none evicted), but JVM
heap size is too noisy at this scale to call this a measured "leak"; the growth evidence is the entry count.

### No-loss verification (after code)
- A, all 3 runs: entries stored during the run (`/stats` after − before) == 6 × successful requests exactly
  (27906/27906, 27906/27906, 27900/27900); per service `enqueued == shipped`, dropped 0, failed 0; every sampled trace
  (111, 71, 76 IDs taken from `X-Trace-Id` response headers) had exactly 6 entries across 3 services under one ID.
- B, all 3 runs: stored == 6 × successful exactly (27900, 27876, 27906); books balance; dropped 0; but **each service
  counted 20 `failed`** — the two workers each hit ten 2 s read timeouts during the 20 s freeze. Those POSTs were
  nonetheless stored once the aggregator resumed (stored count == expected, no retries so no duplicates): a client
  timeout racing a delivery. So `failed` over-reports real loss in this case; it is reported as-is.
- Deliberate overload (`bench/compose/overload-small-queue.yml`: queue capacity 100, scenario B, 50 req/s;
  `bench/results/phase2/overload-small-queue/`): 4,651 successful requests → 27,906 expected entries; stored 22,237;
  dropped 1,889 + 1,890 + 1,890 = 5,669; 27,906 − 5,669 = 22,237. Books per service: gateway 9304 = 7395 + 1889 + 20,
  orders 9304 = 7394 + 1890 + 20, inventory 9304 = 7394 + 1890 + 20. 74 of 91 sampled traces complete. Drops are counted, not silent;
  user-facing latency stayed p50 9.4 / p95 66.8 ms with 0 errors.
- "No traces lost" holds only for what was tested: 50 req/s, default queue (10,000), drops == 0, A and B.

### Tests after the fix (JDK 17.0.21) — `bench/scripts/verify-all.sh bench/results/phase2/verify`
gateway 24, orders 10, inventory 10, aggregator 33 — all 0 failures/errors. The Phase 1 hung-aggregator test now
asserts the request completes in < 1 s with Orders' body (`requestCompletesPromptlyWhileAggregatorHangs`).
e2e on the after stack: 2/2 (`bench/results/phase2/e2e-compose-after.txt`).

### Discarded data (kept for transparency)
`bench/results/phase2/compromised-battery/`: an earlier ramp, pilots and A runs taken while the Mac was on battery
(60% → 15%) and twice went to sleep (17:38:06–17:40:24 idle, 18:02:33–18:04:20 lid closed; `bench/results/env/pmset-sleep-wake.txt`).
One of those runs ran 206.8 s instead of 120 s. None of these numbers are used anywhere.

## Phase 3 — cold start

### Definitions
"Cold" = images already built (build excluded); `docker compose down -v` then `up -d`, so every JVM starts fresh
(no loaded classes, no JIT profile, no connections). The VM's page cache and images stay warm. Harness:
`bench/coldstart.sh` → `bench/coldstart.py` (one CSV row per cycle, compose logs per cycle in `*-logs/`).
- (i) per service: container `State.StartedAt` → its "Started XApplication" log line (VM clock).
- (ii) all Started: earliest container start → last "Started" line. Readiness is read from logs, not HTTP probes, so
  nothing warms the request path before (iii). (ii′) for the warm-up variant: → last "Warm-up completed" line.
- (iii) first `POST /orders/place` right after (ii) / (ii′): client wall time (host) and the trace's first→last span.
- (iv) requests 2–21: median client time and median trace span.
Every config: n = 7, `docker info` NCPU=2 MemTotal=6222663680, Kubernetes on, AC power (`*-header.txt`), 0 restarts.
Configs were run one after another, not interleaved; only differences whose ranges do not overlap are called effects.

### Results — median (min–max), n = 7 each (`bench/results/phase3/summary.md`)

| Config | (ii) all Started, s | (ii′) all warm, s | (iii) first request, client ms | (iii) first trace span, ms | (iv) steady client ms |
|---|---|---|---|---|---|
| Baseline (Phase 2 code, defaults) — `baseline.csv` | 24.283 (22.92–24.967) | — | 2073.8 (1776.5–2701.9) | 1622 (1472–2443) | 52.6 (48.9–65.8) |
| `-XX:TieredStopAtLevel=1` + SerialGC — `fix-jvm-flags.csv` | 11.887 (11.134–14.201) | — | 753 (681–1042.8) | 508 (487–767) | 33.9 (31.9–37.5) |
| `-XX:TieredStopAtLevel=1` only — `fix-jvm-c1only.csv` | 12.769 (12.239–14.246) | — | 755.9 (700.2–1216.5) | 580 (521–1035) | 35.4 (34.6–38.1) |
| SerialGC only — `fix-jvm-serialgc.csv` | 23.706 (21.959–25.557) | — | 1798.4 (1655.2–2329) | 1537 (1442–2062) | 50.7 (39.8–58.2) |
| ApplicationReadyEvent warm-up (code) — `fix-warmup.csv` | 24.771 (23.682–25.317) | 25.837 (25.002–26.571) | 600.7 (464.2–706.5) | 515 (353–617) | 50.7 (44.4–88.8) |

(Per-service (i) columns are in the CSVs and `summary.md`; e.g. baseline gateway 22.662 s (21.564–23.813).)

### Profiling (before fixing)
1. **Startup is CPU contention, not per-service slowness** (`bench/scripts/solo-start.sh`, n = 5 each):
   aggregator started alone 4.160–4.484 s (Spring: 3.148–3.495 s) vs 18.532–20.664 s with the full stack;
   gateway alone 4.784–5.660 s vs 21.564–23.813 s. Raw: `bench/results/phase3/solo-start-{aggregator,gateway}.txt`.
   Four JVMs (plus k3s) start at once on 2 vCPUs.
2. **First-request cost is lazy class loading** (`bench/scripts/profile-first-request.sh`, 3 cold cycles,
   `-Xlog:class+load` / `-Xlog:gc` with JVM uptime; `bench/results/phase3/profile/cycle{1,2,3}/report.txt`):
   after "Started", each JVM loaded 463–851 classes during the first request (top: `com.fasterxml.jackson` 147–189,
   generated lambdas/proxies/accessors 47–111, `org.springframework.web` 41–79, Tomcat/Coyote, `com.sun.jmx` 53)
   versus 3–45 classes during the next 20 requests. GC pauses after "Started" totalled 0–88.4 ms per JVM — minor.
   DispatcherServlet initialization itself took 1–11 ms (28 inits: 4 services × 7 baseline cycles, "Completed
   initialization in N ms" in `baseline-logs/cycle*.log`), so
   eager servlet init was not pursued as a fix. The prior 1565 ms cold trace is consistent with this but was not re-measured.

### Interpretation and what was adopted
- C1-only JIT (`-XX:TieredStopAtLevel=1`) roughly halves time-to-all-Started (median 24.283 → 12.769 s) and cuts the
  first request (2073.8 → 755.9 ms); SerialGC contributes nothing measurable on its own. **Not adopted** in
  `docker-compose.yml`: C1-only gives up C2's peak-throughput optimizations, and steady-state under load was not
  measured with it (Phase 2 numbers use default JIT). Kept as an opt-in override: `bench/compose/fix-jvm-c1only.yml`.
- The warm-up (`WarmUp.java` in each service: three local GETs through Tomcat, DispatcherServlet, Jackson and the HTTP
  client on `ApplicationReadyEvent`; no downstream calls, no trace logs) cuts the first request from 2073.8 to 600.7 ms
  (ranges 1776.5–2701.9 vs 464.2–706.5) at a cost of ~1 s later readiness (all-warm 25.837 s vs all-Started 24.771 s
  in the same runs). **Adopted** (it's in the code). Steady-state requests unchanged (52.6 vs 50.7 ms).
- Not tried: Compose healthchecks/`depends_on: service_healthy`, CPU limits, class-data sharing (time-boxed).
- Final tree (with warm-up) e2e: 2/2 green, `bench/results/phase3/e2e-compose-final.txt`; tests
  `bench/results/phase3/verify-with-warmup/` (24/10/10/33, all green, JDK 17.0.21).

## Final state (tree with warm-up, = `<scratch>/snapshots/phase3-warmup`)

- JUnit (JDK 17.0.21), `bench/results/phase3/verify-with-warmup/`: gateway 24 + orders 10 + inventory 10 + aggregator 33
  = **77 tests, 0 failures, 0 errors**.
- e2e against compose: **2 tests, 0 failures** (`bench/results/phase3/e2e-compose-final.txt`).
- Dashboard Vitest: **8 tests passed** (`bench/results/phase1/dashboard-ci-local.txt`; dashboard code unchanged since).
- Concurrency negative control: 25/25 repetitions failed (17 failures + 8 errors).
- CI workflow validated with actionlint (0 errors); **never run on GitHub Actions in this session**.
- Phase 4: only the Mermaid architecture diagram (README) and `docs/DEMO.md` were done. Not done: `GET /traces`
  summaries + dashboard wiring, error spans, bounded store with eviction.
