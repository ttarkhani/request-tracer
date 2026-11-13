"""Median (min-max) per cold-start CSV. usage: python3 -I summarize_coldstart.py <label=csv> ..."""
import csv, statistics, sys
cols = [("all_ready_s", "(ii) all Started, s"), ("all_warm_s", "(ii') all warm, s"), ("gateway_start_s", "(i) gateway start, s"),
        ("aggregator_start_s", "(i) aggregator start, s"), ("first_req_client_ms", "(iii) first request, client ms"),
        ("first_trace_span_ms", "(iii) first trace span ms"), ("steady_client_median_ms", "(iv) steady client ms"),
        ("steady_trace_median_ms", "(iv) steady trace span ms")]
print("| Config | n | " + " | ".join(c[1] for c in cols) + " |")
print("|---|---|" + "---|" * len(cols))
for arg in sys.argv[1:]:
    label, path = arg.split("=", 1)
    rows = list(csv.DictReader(open(path)))
    cells = []
    for key, _ in cols:
        vals = [float(r[key]) for r in rows if r.get(key) not in (None, "")]
        cells.append(f"{statistics.median(vals):g} ({min(vals):g}–{max(vals):g})" if vals else "—")
    print(f"| {label} | {len(rows)} | " + " | ".join(cells) + " |")
