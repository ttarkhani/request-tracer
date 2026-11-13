"""Prints one short line per coldstart.py JSON row (stdin)."""
import json, sys
for l in sys.stdin:
    try:
        r = json.loads(l)
    except Exception:
        print(l.strip()); continue
    print(r["cycle"], "ready", r["all_ready_s"], "first", r["first_req_client_ms"], r["first_trace_span_ms"],
          "steady", r["steady_client_median_ms"], r["steady_trace_median_ms"], "restarts", r["restarts"], flush=True)
