#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
command -v java >/dev/null 2>&1 || {
  printf '%s\n' 'JDK 21 is required for the repository text audit.' >&2
  exit 1
}
java scripts/PublicTextGate.java
