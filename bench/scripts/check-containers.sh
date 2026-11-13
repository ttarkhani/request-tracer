#!/usr/bin/env bash
# Prints restart count / OOMKilled / state for every container of the compose project. Exit 1 if any restarted or OOM-killed.
bad=0
for c in $(docker compose ps -a -q); do
  line=$(docker inspect -f '{{.Name}} restarts={{.RestartCount}} oom={{.State.OOMKilled}} status={{.State.Status}} exit={{.State.ExitCode}}' "$c")
  echo "$line"
  case "$line" in *"oom=true"*) bad=1;; esac
  [[ "$line" =~ restarts=([0-9]+) ]] && [ "${BASH_REMATCH[1]}" != 0 ] && bad=1
done
exit $bad
