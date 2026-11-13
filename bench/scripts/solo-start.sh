#!/usr/bin/env bash
# Startup time of ONE service started alone (no other stack containers running), n cycles.
#   bench/scripts/solo-start.sh <tree-root> <project> <service> <AppClass> <n>
set -eu
TREE=$1; PROJECT=$2; SVC=$3; APP=$4; N=$5
C="docker compose -p $PROJECT -f $TREE/docker-compose.yml"
docker info --format 'docker info: NCPU={{.NCPU}} MemTotal={{.MemTotal}}'
for i in $(seq 1 "$N"); do
  $C down -v >/dev/null 2>&1
  $C up -d --no-deps "$SVC" >/dev/null 2>&1
  until $C logs "$SVC" 2>/dev/null | grep -q "Started $APP in"; do sleep 0.2; done
  id=$($C ps -q "$SVC")
  started_at=$(docker inspect -f '{{.State.StartedAt}}' "$id")
  line=$(docker logs --timestamps "$id" 2>&1 | grep "Started $APP in")
  python3 -I -c '
import sys,datetime as dt,re
def p(s):
    s=s.replace("Z","+00:00"); m=re.match(r"(.*\.\d{6})\d*(\+.*)",s); s=m.group(1)+m.group(2) if m else s
    return dt.datetime.fromisoformat(s).timestamp()
a=p(sys.argv[1]); b=p(sys.argv[2].split(" ")[0]); spring=re.search(r"in ([\d.]+) seconds",sys.argv[2]).group(1)
print(f"cycle '"$i"' {sys.argv[3]} container_start_to_Started_s={b-a:.3f} spring_reported_s={spring}")' "$started_at" "$line" "$SVC"
done
$C down -v >/dev/null 2>&1
