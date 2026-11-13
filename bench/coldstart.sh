#!/usr/bin/env bash
# Cold-start cycles -> CSV. usage: bench/coldstart.sh <tree-root> <project> <cycles> <out.csv> [extra compose file ...]
set -eu
{ docker info --format "docker info: NCPU={{.NCPU}} MemTotal={{.MemTotal}}"; echo "kubernetes enabled: $(rdctl list-settings | jq .kubernetes.enabled)"; echo "power: $(pmset -g batt | tr "\n" " ")"; echo "args: $*"; date; } > "${4%.csv}-header.txt"
exec caffeinate -i -s python3 -I "$(dirname "$0")/coldstart.py" "$@"
