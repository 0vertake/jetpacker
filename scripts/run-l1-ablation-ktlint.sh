#!/usr/bin/env bash
# Level-1 ablation on ktlint Harbor tasks at shipped body share (jp:default uses 15% bodies).
#
# Sweeps budgets 2k / 4k / 8k with no model calls. Requires:
#   /tmp/ktlint clone (Gradle pin same as L2)
#   /tmp/kotlin-swe-bench/tasks harbor tree
#
# Usage:
#   scripts/run-l1-ablation-ktlint.sh
#   JETPACKER_TASKS=5 scripts/run-l1-ablation-ktlint.sh   # smoke
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
harbor="${JETPACKER_HARBOR:-/tmp/kotlin-swe-bench/tasks}"
tasks="${JETPACKER_TASKS:-43}"
budgets="${JETPACKER_BUDGETS:-2000,4000,8000}"
log="${JETPACKER_L1_LOG:-$HOME/.jetpacker/l1-ablation-ktlint.log}"
KTLINT_GRADLE_REF="${JETPACKER_KTLINT_GRADLE_REF:-3fe589643b916ace8414fca50426beafe2dc245f}"

mkdir -p "$(dirname "$log")"

if [[ ! -d /tmp/ktlint/.git ]]; then
  echo "clone ktlint first: git clone https://github.com/pinterest/ktlint /tmp/ktlint" >&2
  exit 1
fi
if [[ ! -d "$harbor" ]]; then
  echo "harbor missing: $harbor" >&2
  exit 1
fi

git -C /tmp/ktlint fetch -q origin "$KTLINT_GRADLE_REF"
git -C /tmp/ktlint checkout -q "$KTLINT_GRADLE_REF"

echo "[$(date +%H:%M:%S)] L1 ablation ktlint tasks=$tasks budgets=$budgets" | tee -a "$log"

"$root/gradlew" :eval:run \
  -Pjetpacker.repo=/tmp/ktlint \
  -Pjetpacker.harbor="$harbor" \
  -Pjetpacker.harbor.repo=ktlint \
  -Pjetpacker.tasks="$tasks" \
  -Pjetpacker.budgets="$budgets" \
  2>&1 | tee -a "$log"

echo "[$(date +%H:%M:%S)] done — log: $log"
