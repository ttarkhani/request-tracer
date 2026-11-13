"""Counts classes each JVM loaded after Spring reported "Started" (i.e. lazily, at request time).
usage: python3 -I classload_report.py <cycle-dir>
Window 1 = after "Started" up to ~2 s after the first request; window 2 = during the next 20 requests.
"""
import collections, os, re, sys

d = sys.argv[1]
compose = open(os.path.join(d, "compose.log")).read()
names = {"aggregator": "LogAggregatorApplication", "gateway": "ApiGatewayApplication",
         "orders": "OrdersApplication", "inventory": "InventoryApplication"}
LINE = re.compile(r"^\[(\d+\.\d+)s\] (\S+) source:")


def loads(path):
    out = []
    for line in open(path, errors="replace"):
        m = LINE.match(line)
        if m:
            out.append((float(m.group(1)), m.group(2)))
    return out


def group(cls):
    parts = cls.split(".")
    if cls.startswith(("jdk.internal.reflect.Generated", "jdk.proxy", "java.lang.invoke.LambdaForm")) or "$$Lambda" in cls:
        return "generated (lambdas/proxies/reflection accessors)"
    return ".".join(parts[:3])


print(f"{'service':<11} {'started@':>9} {'loaded at startup':>17} {'first request':>13} {'next 20 req':>11}  top packages in first request")
for svc, app in names.items():
    m = re.search(rf"Started {app} in [\d.]+ seconds \(process running for ([\d.]+)\)", compose)
    started = float(m.group(1))
    first = loads(os.path.join(d, f"{svc}-classload-after-first.log"))
    steady = loads(os.path.join(d, f"{svc}-classload-after-steady.log"))
    at_start = sum(1 for t, _ in first if t <= started)
    w1 = [c for t, c in first if t > started]
    w2 = len(steady) - len(first)
    top = collections.Counter(group(c) for c in w1).most_common(6)
    print(f"{svc:<11} {started:>8.1f}s {at_start:>17} {len(w1):>13} {w2:>11}  " + ", ".join(f"{k} {v}" for k, v in top))

for svc, app in names.items():
    started = float(re.search(rf"Started {app} in [\d.]+ seconds \(process running for ([\d.]+)\)", compose).group(1))
    allp, after = [], []
    for line in open(os.path.join(d, f"{svc}-gc.log")):
        m = re.match(r"^\[(\d+\.\d+)s\] GC\(\d+\) Pause.* ([\d.]+)ms$", line.strip())
        if m:
            allp.append(float(m.group(2)))
            if float(m.group(1)) > started:
                after.append(float(m.group(2)))
    print(f"{svc}: GC pauses before Started {len(allp) - len(after)} ({sum(allp) - sum(after):.1f} ms); "
          f"after Started (first + 20 requests) {len(after)} ({sum(after):.1f} ms)")
