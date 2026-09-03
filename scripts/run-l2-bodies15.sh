#!/usr/bin/env bash
# Level-2 at the shipped packer config (15% body share) across token budgets.
#
# Writes to separate ledgers so the bodies-only baseline stays untouched:
#   ~/.jetpacker-l2-bodies15-{2k,4k,8k}/level2.tsv
#
# Usage:
#   scripts/run-l2-bodies15.sh detekt
#   scripts/run-l2-bodies15.sh ktlint
#   scripts/run-l2-bodies15.sh both
#
# Run under screen; needs CURSOR_API_KEY and certified.tsv copied into each workspace.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
target="${1:-both}"
export CURSOR_API_KEY
CURSOR_API_KEY="$(tr -d '[:space:]' < "$HOME/.cursor_api_key")"
if [[ -z "$CURSOR_API_KEY" ]]; then
  echo "CURSOR_API_KEY empty" >&2
  exit 1
fi
export JETPACKER_MODEL="${JETPACKER_MODEL:-composer-2.5}"
budgets=(2000 4000 8000)

echo "[$(date +%H:%M:%S)] building eval jar"
"$root/gradlew" -q :eval:jar :core:jar :baselines:jar

lib="$HOME/.jetpacker-l2/lib"
mkdir -p "$lib"
cp -f "$root"/eval/build/libs/eval-*.jar "$lib/"
cp -f "$root"/core/build/libs/core-*.jar "$lib/"
cp -f "$root"/baselines/build/libs/baselines-*.jar "$lib/"

run_repo_budget() {
  local repo="$1"
  local harbor_repo="$2"
  local repo_path="$3"
  local budget="$4"
  local l2_dir="$HOME/.jetpacker-l2-bodies15-${budget}"

  mkdir -p "$l2_dir"
  cp -f "$HOME/.jetpacker-l2/certified.tsv" "$l2_dir/certified.tsv"

  echo "[$(date +%H:%M:%S)] bodies15 $repo @ ${budget}t -> $l2_dir"
  java -Xmx6g -Didea.is.unit.test=true -Djava.awt.headless=true \
    -Djetpacker.repo="$repo_path" \
    -Djetpacker.harbor="${JETPACKER_HARBOR:-/tmp/kotlin-swe-bench/tasks}" \
    -Djetpacker.harbor.repo="$harbor_repo" \
    -Djetpacker.l2="$l2_dir" \
    -Djetpacker.budgets="$budget" \
    -Djetpacker.fullTierShare=0.15 \
    -Djetpacker.l2.retries=1 \
    -Djetpacker.patcher="$root/eval/src/main/resources/cursor_patch.py" \
    -Djetpacker.python="${JETPACKER_PYTHON:-$HOME/.jetpacker-l2/venv/bin/python}" \
    -cp "$lib/*" \
    dev.jetpacker.eval.Level2Kt
}

run_repo() {
  local repo="$1"
  local harbor_repo="$2"
  local repo_path="$3"

  if [[ ! -d "$repo_path/.git" ]]; then
    echo "clone $repo_path first" >&2
    exit 1
  fi

  for budget in "${budgets[@]}"; do
    run_repo_budget "$repo" "$harbor_repo" "$repo_path" "$budget"
  done
}

case "$target" in
  detekt) run_repo detekt detekt /tmp/detekt ;;
  ktlint)
    KTLINT_GRADLE_REF="${JETPACKER_KTLINT_GRADLE_REF:-3fe589643b916ace8414fca50426beafe2dc245f}"
    git -C /tmp/ktlint checkout -q "$KTLINT_GRADLE_REF"
    run_repo ktlint ktlint /tmp/ktlint
    ;;
  both)
    run_repo detekt detekt /tmp/detekt
    KTLINT_GRADLE_REF="${JETPACKER_KTLINT_GRADLE_REF:-3fe589643b916ace8414fca50426beafe2dc245f}"
    git -C /tmp/ktlint checkout -q "$KTLINT_GRADLE_REF"
    run_repo ktlint ktlint /tmp/ktlint
    ;;
  *)
    echo "usage: $0 [detekt|ktlint|both]" >&2
    exit 1
    ;;
esac

echo "[$(date +%H:%M:%S)] bodies15 sweep complete"
