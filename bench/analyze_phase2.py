"""Phase 2 analysis: per-run rows + per-configuration median (min-max), plus no-loss checks.
usage: python3 -I bench/analyze_phase2.py bench/results/phase2 > bench/results/phase2/analysis.md
Reads each <root>/<A|B>/<before|after>-runN/ produced by bench/run-load.sh.
"""
import json, os, re, statistics, sys

root = sys.argv[1]


def k6_main(d):
    m = json.load(open(os.path.join(d, "k6-summary.json")))["metrics"]
    v = lambda name, tag: m.get(f"{name}{{{tag}}}", {}).get("values", {})
    dur = v("http_req_duration", "phase:main")
    reqs = v("http_reqs", "phase:main").get("count", 0)
    return {
        "p50": dur.get("med"), "p95": dur.get("p(95)"), "p99": dur.get("p(99)"), "max": dur.get("max"),
        "rps": reqs / 60.0, "err": v("http_req_failed", "phase:main").get("rate", 0.0),
        "dropped_iter": v("dropped_iterations", "scenario:main").get("count", 0)
        + v("dropped_iterations", "scenario:warmup").get("count", 0),
        # every 200 response across warm-up + main (the aggregator receives entries for all of them)
        "ok_total": m.get("checks", {}).get("values", {}).get("passes", 0),
        "reqs_total": m.get("http_reqs", {}).get("values", {}).get("count", 0),
    }


def agg_mem(d, label):
    for line in open(os.path.join(d, f"docker-stats-{label}.txt")):
        if "aggregator" in line:
            return re.search(r"mem=([\d.]+[KMG]iB)", line).group(1)
    return "?"


def stats(d, label):
    p = os.path.join(d, f"agg-stats-{label}.json")
    try:
        return json.load(open(p))
    except Exception:
        return None


def shipper(d, label):
    p = os.path.join(d, f"shipper-{label}.txt")
    out = {}
    if not os.path.exists(p):
        return out
    for line in open(p):
        parts = line.split()
        if not parts:
            continue
        kv = dict(x.split("=") for x in parts[1:])
        out[parts[0]] = kv
    return out


def sampled(d):
    p = os.path.join(d, "sampled-trace-check.txt")
    lines = [l for l in open(p) if l.strip()] if os.path.exists(p) else []
    good = sum(1 for l in lines if " entries=6 services=3 " in l + " " and len(l.split("ids=")[1].strip().split(",")) == 1)
    return good, len(lines)


def fmt(x, nd=1):
    return "n/a" if x is None else f"{x:.{nd}f}"


def num(x):
    try:
        return float(x)
    except Exception:
        return None


rows = {}
print("| Scenario | Code | Run | p50 ms | p95 ms | p99 ms | max ms | Throughput req/s | Error rate | k6 dropped iters | "
      "Agg mem before→after | Entries Δ / expected (6×200s) | Shipper books (enq = shipped+dropped+failed) | Sampled traces complete |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for sc in ("A", "B"):
    scdir = os.path.join(root, sc)
    if not os.path.isdir(scdir):
        continue
    for name in sorted(os.listdir(scdir)):
        d = os.path.join(scdir, name)
        if not os.path.isdir(d) or not os.path.exists(os.path.join(d, "k6-summary.json")):
            continue
        side, run = name.split("-run")
        k = k6_main(d)
        restarted = open(os.path.join(d, "containers.txt")).read()
        bad = "restarts=0" not in restarted or "oom=true" in restarted or re.search(r"restarts=[1-9]", restarted)
        s0, s1 = stats(d, "before"), stats(d, "after")
        if s0 and s1 and "entries" in s0 and "entries" in s1:
            delta = s1["entries"] - s0["entries"]
            loss = f"{delta} / {6 * k['ok_total']}"
        else:
            loss = "n/a (no /stats in before code)"
        sh = shipper(d, "after")
        books = []
        dropped_total = 0
        for svc, kv in sh.items():
            e, s_, dr, f = (num(kv.get(x)) for x in ("enqueued", "shipped", "dropped", "failed"))
            if e is None:
                continue
            dropped_total += dr
            books.append(f"{svc} {int(e)}={int(s_)}+{int(dr)}+{int(f)}{'' if e == s_ + dr + f else ' MISMATCH'}")
        g, n = sampled(d)
        print(f"| {sc} | {side} | {run}{' DISCARD' if bad else ''} | {fmt(k['p50'])} | {fmt(k['p95'])} | {fmt(k['p99'])} | {fmt(k['max'])} | "
              f"{fmt(k['rps'], 2)} | {k['err']:.4f} | {k['dropped_iter']} | {agg_mem(d, 'before')}→{agg_mem(d, 'after')} | {loss} | "
              f"{'; '.join(books) if books else 'n/a'} | {g}/{n} |")
        if not bad:
            rows.setdefault((sc, side), []).append({**k, "drops": dropped_total if books else None})

print()
print("| Scenario | Code | n | p50 ms | p95 ms | p99 ms | Throughput req/s | Error rate | Shipper drops (sum, all services) |")
print("|---|---|---|---|---|---|---|---|---|")
for (sc, side), rs in sorted(rows.items(), key=lambda x: (x[0][0], x[0][1] != "before")):
    def agg(key, nd=1, pct=False):
        vals = [r[key] for r in rs if r[key] is not None]
        if not vals:
            return "n/a"
        f = (lambda x: f"{100 * x:.2f}%") if pct else (lambda x: f"{x:.{nd}f}")
        return f"{f(statistics.median(vals))} ({f(min(vals))}–{f(max(vals))})"
    print(f"| {sc} | {side} | {len(rs)} | {agg('p50')} | {agg('p95')} | {agg('p99')} | {agg('rps', 2)} | {agg('err', pct=True)} | {agg('drops', 0)} |")
