# Worklog

Session start: 2026-11-12 ~16:54 EST. Agent: Claude Code (claude-opus-5-5).

## Initial state

`git status` at start:

```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
HEAD = 1c01c35 ("Redesign dashboard for standalone recruiter-facing deploy ...").

## Environment (bench/results/env/)

- Host: Intel i7-9750H, 12 logical CPUs, 32 GiB, macOS 15.4.1 (24E263), x86_64.
- Host JDK: Oracle 25.0.4.1. Maven 3.9.16. Node v24.21.0, npm 11.19.0. jq, curl, python3 present.
- `command -v brew` -> not found.
- Test JDK: Temurin 17.0.21+9 tarball from api.adoptium.net, SHA-256 checked against the Adoptium API
  (868f5c67...72cc), unpacked to the session scratchpad (no system install). All `mvn` runs use
  `JAVA_HOME=<scratchpad>/jdk17/jdk-17.0.21+9/Contents/Home` unless stated.
- Ports 8080-8084 and 3000: free at start (lsof showed no listeners).
- Rancher Desktop settings (`rdctl list-settings` -> env/rd-settings.json): VM 2 CPUs / 6 GB, vmType vz,
  engine moby, **Kubernetes enabled (k3s 1.36.4)**, traefik on.
- Rancher Desktop was NOT running at session start. I launched it with `open -a "Rancher Desktop"`
  (no settings change). It failed to start (env/rd-background-startfail-1.log):
  `limactl start` fatal: `failed to get Info from .../lima/0/ha.sock ... connection refused`.
  Cause found: stale `lima/0/ha.pid` and `vz.pid` (both 981, dated Oct 4) — PID 981 is now an unrelated
  macOS AudioToolbox helper, so lima thinks the host agent is alive. Ran `rdctl shutdown` (clean).
  Docker is DOWN until the user authorizes/does the repair.

## Phase log

### Step 0 (done) — native runs of original HEAD under JDK 17
- `bench/scripts/step0-native.sh <scratch copy of HEAD> bench/results/step0` -> `bench/results/step0/step0-native.txt`.
- All findings a–e confirmed; b' (empty-key) inference confirmed. Write-up in bench/RESULTS.md.

### Phase 1 progress
- poms: added spring-boot-starter-test (test scope, BOM-managed). No WireMock: tests use a JDK HttpServer stub
  (`src/test/.../testsupport/StubHttpServer.java`, duplicated per service).
- Aggregator: `store/TraceStore` (ConcurrentHashMap + CopyOnWriteArrayList), POST /logs -> 400 on null/blank traceId,
  GET sorted by timestamp. Tests: LogControllerTest (7), TraceStoreConcurrencyTest (@RepeatedTest 25, 16 threads x 200 traces x 5).
- Concurrency negative control (HashMap/ArrayList swapped in, then restored from backup; `cmp` identical):
  `bench/results/phase1/aggregator-concurrency-negative-control.txt` -> 25 run, 17 failures, 8 errors.
- Gateway: `TraceIds.resolve` (strict canonical UUID, else new UUID; lowercases), X-Trace-Id response header on
  every outcome, CORS `exposedHeaders=X-Trace-Id`; Orders HTTP error -> status passed through; Orders unreachable -> 502.
  Tests: GatewayContractTest (15), GatewayDependencyDownTest (3: refused, HUNG [documents blocking], orders unreachable).
- Orders/Inventory: missing/blank X-Trace-Id -> new UUID at that hop (forwarded downstream). Contract tests 4 each.
- e2e/ module (JUnit + java.net.http, no Testcontainers) + e2e/wait-for-stack.sh. Native prelim run passed:
  `bench/results/phase1/e2e-native-prelim.txt` (NOT the compose evidence).
- Dashboard: vitest ^5.1.0 devDependency, `npm test`, src/lib/trace.test.js (8 tests). Local CI commands:
  `bench/results/phase1/dashboard-ci-local.txt` (npm ci, lint, test, build all exit 0).
- `.github/workflows/ci.yml` written (java matrix x4, dashboard, e2e).
- `bench/scripts/verify-all.sh bench/results/phase1` -> mvn-verify-*.txt: gateway 18, orders 4, inventory 4, aggregator 32; all BUILD SUCCESS (JDK 17.0.21).
- User authorized removing stale lima ha.pid/vz.pid/ha.sock; done 17:17, Rancher Desktop relaunched (settings unchanged).
- actionlint: bench/results/phase1/actionlint.txt (0 errors). Compose e2e green: e2e-compose.txt; negative control red
  (6 vs 4): e2e-compose-negative-control.txt; reverted + green: e2e-compose-after-revert.txt; stack down.
- **PHASE 1 DONE.** Snapshot: <scratchpad>/snapshots/phase1 (rsync excl node_modules/target/.git/dist);
  manifest bench/results/phase1/snapshot-phase1-manifest.sha256.
  Scratchpad = /private/tmp/claude-501/-Users-tahatarkhani-Documents-GitHub-request-tracer/8091b665-b8c6-412f-a45a-6a540b97aeaf/scratchpad

### Phase 2 progress
- k6: grafana/k6:0.54.0 Docker image on the compose network (`<project>_default`), target http://gateway:8080.
  Script bench/loadtest.js, harness bench/run-load.sh (restart stack per run; docker stats + /stats + shipper counters
  before/after; sampled trace checks; container restart/OOM check), summarizer bench/summarize.py.
- Before images: `docker compose -p rtbefore -f <scratch>/snapshots/phase1/docker-compose.yml build`.
- Ramp on BEFORE (bench/results/phase2/ramp-before): knee between 50 and 100 rps (see RESULTS).
- Pilot v1 (discarded design): 50 rps with 30 s full-rate warm-up from cold -> backlog carried into main window
  (pilot-before-A p50 493 ms; pilot-before-B). Kept on record.
- Pilot v2 (final design): 40 rps, warm-up ramping 5->40 over 60 s, main 60 s: pilot2-before-A p50 21.9 / p95 82.7 / p99 170.0 ms.
- THE FIX written AFTER those baseline runs: LogShipper per service (+ actuator, management exposure health,metrics),
  aggregator GET /stats, LogShipperTest x3, hung-aggregator test flipped. verify: bench/results/phase2/verify/ (24/10/10/33 green).
- After snapshot: <scratch>/snapshots/phase2 (manifest bench/results/phase2/snapshot-phase2-manifest.sha256);
  images project rtafter. e2e on after stack green: bench/results/phase2/e2e-compose-after.txt.
- 17:5x: launched `bench/run-ab-series.sh` A x3 then B x3 alternating before/after -> bench/results/phase2/{A,B}/, log series.log.
- 18:07 STOPPED series. Host was ON BATTERY for the whole session (60% -> 15%); idle sleep 17:38:06-17:40:24 and
  clamshell sleep 18:02:33-18:04:20 (bench/results/env/pmset-*.txt). A/after-run2 stalled (k6 testRunDuration 206.8 s vs 120 s).
  => ALL Phase 2 load runs so far (ramp, pilots, A runs 1-3) are compromised (battery throttling + sleep); kept on record,
  NOT used for claims. Waiting for user to connect AC; rerun everything (ramp, series) under `caffeinate -i -s`.
- 18:12 AC confirmed (45 W). Ramp rerun -> rate 50. Series A x3 + B x3 alternated under caffeinate: done 18:47.
  analysis: bench/results/phase2/analysis.md. Overload run: bench/results/phase2/overload-small-queue/.
- **PHASE 2 DONE** (RESULTS.md Phase 2 section).

### Phase 3 (done ~19:45)
- bench/coldstart.sh|py, solo-start.sh, profile-first-request.sh + classload_report.py, summarize_coldstart.py.
- CSVs: bench/results/phase3/{baseline,fix-jvm-flags,fix-jvm-c1only,fix-jvm-serialgc,fix-warmup}.csv (n=7 each).
- First attempt at c1only/serialgc had a broken override (flag split on ':'), output deleted, rerun.
- WarmUp.java added to all 4 services (adopted). Snapshot <scratch>/snapshots/phase3-warmup == repo (diff empty), images rtwarm.
- **PHASE 3 DONE.** Next: docs (README, DEMO.md), Phase 4 time-boxed (Mermaid diagram only).

### Docs + wrap-up (~19:55)
- README.md rewritten (every number cross-checked against RESULTS.md with a grep loop); dashboard/README.md; docs/DEMO.md.
- Phase 4: Mermaid diagram only; other items not done (time-box).
- bench/results is ~42 MB (phase3/profile class-load logs are ~23 MB of it); prune before committing if you like.
- No project containers or helper processes left running. A `caffeinate -i -t 300` process seen at the end is not mine.
- Rancher Desktop was started by me this session -> shut down with `rdctl shutdown` at the end. Built images
  (rtbefore-*, rtafter-*, rtwarm-*, request-tracer-*) and grafana/k6:0.54.0, rhysd/actionlint:1.7.7 remain in the VM.
- Nothing committed; all changes are uncommitted for the user.
