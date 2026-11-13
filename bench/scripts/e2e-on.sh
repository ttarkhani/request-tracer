#!/usr/bin/env bash
# Start a compose tree under a project name, run the e2e module against it, dump shipper/aggregator counters, stop it.
#   bench/scripts/e2e-on.sh <tree-root> <project>
set -u
TREE=$1; PROJECT=$2; HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
C="docker compose -p $PROJECT -f $TREE/docker-compose.yml"
docker info --format 'docker info: NCPU={{.NCPU}} MemTotal={{.MemTotal}}'; "$JAVA_HOME/bin/java" -version 2>&1 | head -1
$C up -d 2>&1
"$ROOT/e2e/wait-for-stack.sh" 240
echo "gateway health: $(curl -s localhost:8080/actuator/health)"
(cd "$ROOT/e2e" && mvn -B verify 2>&1); echo "e2e exit=$?"
sleep 2
echo "aggregator /stats: $(curl -s localhost:8084/stats)"
for p in 8080 8081 8082; do
  for o in enqueued shipped dropped failed; do
    echo "port $p $o=$(curl -s "localhost:$p/actuator/metrics/log.shipper.events?tag=outcome:$o" | jq '.measurements[0].value')"
  done
  echo "port $p queue.depth=$(curl -s "localhost:$p/actuator/metrics/log.shipper.queue.depth" | jq '.measurements[0].value')"
done
COMPOSE_PROJECT_NAME=$PROJECT COMPOSE_FILE=$TREE/docker-compose.yml "$HERE/check-containers.sh"
$C down -v 2>&1
