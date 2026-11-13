"""Cold-start measurement: N fresh `docker compose down -v` / `up -d` cycles, one CSV row per cycle.

"Cold" here means: images already built (build time excluded), containers and volumes removed
(`down -v`), so every JVM starts from scratch: no JIT profile, no loaded classes, no open connections.
The page cache of the VM and the images themselves are warm (same as any real restart).

Metrics (kept separate):
  (i)   per service: container State.StartedAt -> "Started XApplication" log line (both VM clock),
        plus Spring's own "Started ... in N seconds" figure.
  (ii)  all ready: earliest container StartedAt -> last "Started" log line (VM clock).
        Readiness is detected from logs, NOT by HTTP probes, so no request path is warmed before (iii).
  (iii) first end-to-end request right after (ii): client wall time (host) and the trace's own
        first-to-last span from the aggregator, with per-hop offsets.
  (iv)  steady state: requests 2..(1+STEADY), median client time and median trace span.

usage: python3 -I bench/coldstart.py <tree-root> <project> <cycles> <out.csv> [extra compose file ...]
Env READY_MARKER (optional): a log line every service must also print before it counts as ready (e.g.
"Warm-up completed"); then all_warm_s = earliest StartedAt -> last marker line, and (iii) starts after it.
"""
import csv, datetime as dt, json, os, re, statistics, subprocess, sys, time, urllib.request

tree, project, cycles, out_csv = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
extra = sys.argv[5:]
STEADY = 20
MARKER = os.environ.get("READY_MARKER")
SERVICES = {"aggregator": "LogAggregatorApplication", "gateway": "ApiGatewayApplication",
            "orders": "OrdersApplication", "inventory": "InventoryApplication"}
compose = ["docker", "compose", "-p", project, "-f", os.path.join(tree, "docker-compose.yml")]
for f in extra:
    compose += ["-f", f]
logdir = os.path.splitext(out_csv)[0] + "-logs"
os.makedirs(logdir, exist_ok=True)


def sh(args, check=True):
    return subprocess.run(args, capture_output=True, text=True, check=check).stdout


def parse_ts(s):  # docker RFC3339Nano -> epoch seconds (float)
    s = s.strip().replace("Z", "+00:00")
    m = re.match(r"(.*\.\d{6})\d*(\+.*)", s)
    if m:
        s = m.group(1) + m.group(2)
    return dt.datetime.fromisoformat(s).timestamp()


def cid(svc):
    return sh(compose + ["ps", "-q", svc]).strip()


def started_line(svc):
    out = sh(["docker", "logs", "--timestamps", cid(svc)], check=False)
    for line in out.splitlines():
        if f"Started {SERVICES[svc]} in" in line:
            ts = parse_ts(line.split(" ", 1)[0])
            m = re.search(r"in ([\d.]+) seconds \(process running for ([\d.]+)\)", line)
            return ts, float(m.group(1)), float(m.group(2))
    return None


def marker_line(svc):
    out = sh(["docker", "logs", "--timestamps", cid(svc)], check=False)
    for line in out.splitlines():
        if MARKER in line:
            return parse_ts(line.split(" ", 1)[0])
    return None


def post_order():
    req = urllib.request.Request("http://localhost:8080/orders/place", method="POST",
                                 data=b'{"customerId":"COLD","items":"sku-001"}',
                                 headers={"Content-Type": "application/json"})
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        r.read()
        tid = r.headers["X-Trace-Id"]
    return (time.perf_counter() - t) * 1000, tid


def trace(tid):
    for _ in range(50):
        with urllib.request.urlopen(f"http://localhost:8084/traces/{tid}", timeout=5) as r:
            entries = json.loads(r.read())
        if len(entries) >= 6:
            return entries
        time.sleep(0.1)
    return entries


def offsets(entries):
    t0 = min(e["timestamp"] for e in entries)
    o = {}
    for e in entries:
        phase = "recv" if e["message"].startswith("Received") else "done"
        o[f"{e['serviceName'].split('-')[0]}_{phase}"] = e["timestamp"] - t0
    return o, max(e["timestamp"] for e in entries) - t0


fields = (["cycle"] + [f"{s}_start_s" for s in SERVICES] + [f"{s}_spring_s" for s in SERVICES]
          + ["all_ready_s", "all_warm_s", "host_up_to_ready_s", "first_req_client_ms", "first_trace_span_ms",
             "orders_recv", "inventory_recv", "inventory_done", "orders_done", "api_done",
             "steady_client_median_ms", "steady_trace_median_ms", "restarts"])
with open(out_csv, "w", newline="") as fh:
    w = csv.DictWriter(fh, fieldnames=fields)
    w.writeheader()
    for c in range(1, cycles + 1):
        sh(compose + ["down", "-v"], check=False)
        t_up = time.time()
        sh(compose + ["up", "-d"])
        lines = {}
        deadline = time.time() + 300
        while len(lines) < 4 and time.time() < deadline:
            for s in SERVICES:
                if s not in lines:
                    r = started_line(s)
                    if r:
                        lines[s] = r
            time.sleep(0.2)
        warm = {}
        while MARKER and len(warm) < 4 and time.time() < deadline:
            for s in SERVICES:
                if s not in warm:
                    r = marker_line(s)
                    if r:
                        warm[s] = r
            time.sleep(0.2)
        t_detect = time.time()
        row = {"cycle": c}
        starts = {}
        for s in SERVICES:
            st = parse_ts(sh(["docker", "inspect", "-f", "{{.State.StartedAt}}", cid(s)]))
            starts[s] = st
            row[f"{s}_start_s"] = round(lines[s][0] - st, 3)
            row[f"{s}_spring_s"] = lines[s][1]
        row["all_ready_s"] = round(max(v[0] for v in lines.values()) - min(starts.values()), 3)
        row["all_warm_s"] = round(max(warm.values()) - min(starts.values()), 3) if MARKER else ""
        row["host_up_to_ready_s"] = round(t_detect - t_up, 3)

        first_ms, tid = post_order()
        o, span = offsets(trace(tid))
        row["first_req_client_ms"] = round(first_ms, 1)
        row["first_trace_span_ms"] = span
        for k in ("orders_recv", "inventory_recv", "inventory_done", "orders_done", "api_done"):
            row[k] = o.get(k)
        client, spans = [], []
        for _ in range(STEADY):
            ms, t = post_order()
            client.append(ms)
            spans.append(offsets(trace(t))[1])
        row["steady_client_median_ms"] = round(statistics.median(client), 1)
        row["steady_trace_median_ms"] = statistics.median(spans)
        row["restarts"] = sum(int(sh(["docker", "inspect", "-f", "{{.RestartCount}}", cid(s)]).strip()) for s in SERVICES)
        with open(os.path.join(logdir, f"cycle{c}.log"), "w") as lf:
            lf.write(sh(compose + ["logs", "--no-color", "--timestamps"], check=False))
        w.writerow(row)
        fh.flush()
        print(json.dumps(row), flush=True)
    sh(compose + ["down", "-v"], check=False)
