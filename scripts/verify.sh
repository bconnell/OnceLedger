#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
sh scripts/audit-public-text.sh
command -v java >/dev/null 2>&1 || { echo 'JDK 21 is required.' >&2; exit 1; }
java scripts/JavaSyntaxGate.java
command -v mvn >/dev/null 2>&1 || { echo 'Maven 3.9+ is required.' >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo 'Docker is required for integration tests.' >&2; exit 1; }
docker info --format '{{.ServerVersion}}' >/dev/null
mvn -B -ntp clean verify
if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git diff --check
  git diff --cached --check
fi
printf '%s\n' 'Local build and database integration gates completed.'
