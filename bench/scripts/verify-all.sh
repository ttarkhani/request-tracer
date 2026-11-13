#!/usr/bin/env bash
# Runs `mvn -B verify` in each of the four service modules, saving full output to $1/mvn-verify-<module>.txt
set -u
OUT=$1; mkdir -p "$OUT"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
for m in services/api-gateway services/orders services/inventory log-aggregator; do
  f="$OUT/mvn-verify-$(basename $m).txt"
  { echo "\$ (cd $m && mvn -B verify)"; echo "JAVA_HOME=$JAVA_HOME"; "$JAVA_HOME/bin/java" -version 2>&1; date;
    (cd "$ROOT/$m" && mvn -B verify 2>&1); echo "exit=$?"; } > "$f"
  echo "$m: $(grep -E '^\[(INFO|ERROR|WARNING)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$f" | tail -1) $(grep -E 'BUILD (SUCCESS|FAILURE)' "$f") $(tail -1 "$f")"
done
