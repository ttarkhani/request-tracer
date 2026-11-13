"""Summarize k6 handleSummary JSON files.
usage: python3 -I bench/summarize.py <k6-summary.json> [tag ...]   (default tag: phase:main)
Prints, per tag: requests, throughput (requests / tagged window seconds), error rate, p50/p95/p99/max (ms), dropped iterations.
"""
import json, sys

def sub(metrics, name, tag):
    return metrics.get(f"{name}{{{tag}}}", {}).get("values", {})

def summarize(path, tags, window):
    d = json.load(open(path))
    m = d["metrics"]
    rows = []
    for tag in tags:
        dur = sub(m, "http_req_duration", tag)
        reqs = sub(m, "http_reqs", tag).get("count", 0)
        failed = sub(m, "http_req_failed", tag).get("rate", 0.0)
        k, v = tag.split(":", 1)
        scenario = v if k == "phase" else f"step_{v}"
        dropped = sub(m, "dropped_iterations", f"scenario:{scenario}").get("count", 0)
        w = window.get(tag)
        rows.append({
            "tag": tag, "requests": reqs, "throughput_rps": round(reqs / w, 2) if w else None,
            "error_rate": round(failed, 5), "p50_ms": round(dur.get("med", 0), 2),
            "p95_ms": round(dur.get("p(95)", 0), 2), "p99_ms": round(dur.get("p(99)", 0), 2),
            "max_ms": round(dur.get("max", 0), 2), "dropped_iterations": dropped,
        })
    return rows

if __name__ == "__main__":
    path = sys.argv[1]
    args = sys.argv[2:]
    window = {}
    tags = []
    for a in args:  # tag or tag=windowSeconds
        if "=" in a:
            t, w = a.rsplit("=", 1); window[t] = float(w); tags.append(t)
        else:
            tags.append(a)
    for r in summarize(path, tags or ["phase:main"], window):
        print(json.dumps(r))
