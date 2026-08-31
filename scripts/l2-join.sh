#!/usr/bin/env bash
# Join L2 ledgers with L1/L1.5 metrics (no model calls).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
harbor="${JETPACKER_HARBOR:-/tmp/kotlin-swe-bench/tasks}"

join() {
  local repo_path="$1"
  local harbor_repo="$2"
  local l2_dir="$3"
  if [[ ! -f "$l2_dir/level2.tsv" ]]; then
    echo "skip $harbor_repo — no ledger at $l2_dir/level2.tsv"
    return 0
  fi
  echo "joining $harbor_repo from $l2_dir"
  "$root/gradlew" -q :eval:level2join \
    -Pjetpacker.repo="$repo_path" \
    -Pjetpacker.harbor="$harbor" \
    -Pjetpacker.harbor.repo="$harbor_repo" \
    -Pjetpacker.l2="$l2_dir"
}

join /tmp/detekt detekt "$HOME/.jetpacker-l2"
join /tmp/ktlint ktlint "$HOME/.jetpacker-l2-ktlint"
