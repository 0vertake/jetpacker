#!/usr/bin/env bash
# Sync the eval classpath and rerun flaky arms with Phase-1 noise reduction.
#
# Prunes NO_ANSWER / NOT_APPLIED from each ledger, then re-scores only those pairs with:
#   - fuzzier git apply / patch fallbacks
#   - one model retry on NO_ANSWER and NOT_APPLIED (apply stderr fed back)
#   - pinned JETPACKER_MODEL (default composer-2.5)
#
# Usage:
#   scripts/run-l2-phase1-rerun.sh detekt
#   scripts/run-l2-phase1-rerun.sh ktlint
#   scripts/run-l2-phase1-rerun.sh both
#
# Run under screen; needs CURSOR_API_KEY and warm task images.
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

echo "[$(date +%H:%M:%S)] building eval jar"
"$root/gradlew" -q :eval:jar :core:jar :baselines:jar

lib="$HOME/.jetpacker-l2/lib"
mkdir -p "$lib"
cp -f "$root"/eval/build/libs/eval-*.jar "$lib/"
cp -f "$root"/core/build/libs/core-*.jar "$lib/"
cp -f "$root"/baselines/build/libs/baselines-*.jar "$lib/"

run_repo() {
  local repo="$1"
  local harbor_repo="$2"
  local repo_path="$3"
  local l2_dir="$4"
  local ledger="$l2_dir/level2.tsv"

  if [[ ! -d "$repo_path/.git" ]]; then
    echo "clone $repo_path first" >&2
    exit 1
  fi
  if [[ ! -f "$ledger" ]]; then
    echo "ledger missing: $ledger" >&2
    exit 1
  fi

  "$root/scripts/l2-prune-flaky.sh" "$ledger" NO_ANSWER NOT_APPLIED

  echo "[$(date +%H:%M:%S)] phase-1 rerun $repo (model=$JETPACKER_MODEL)"
  java -Xmx6g -Didea.is.unit.test=true -Djava.awt.headless=true \
    -Djetpacker.repo="$repo_path" \
    -Djetpacker.harbor="${JETPACKER_HARBOR:-/tmp/kotlin-swe-bench/tasks}" \
    -Djetpacker.harbor.repo="$harbor_repo" \
    -Djetpacker.l2="$l2_dir" \
    -Djetpacker.l2.retries=1 \
    -Djetpacker.l2.rerunOutcomes=NO_ANSWER,NOT_APPLIED \
    -Djetpacker.patcher="$root/eval/src/main/resources/cursor_patch.py" \
    -Djetpacker.python="${JETPACKER_PYTHON:-$HOME/.jetpacker-l2/venv/bin/python}" \
    -cp "$lib/*" \
    dev.jetpacker.eval.Level2Kt

  "$root/scripts/update-level2-doc.sh" "$repo"
}

case "$target" in
  detekt)
    run_repo detekt detekt /tmp/detekt "$HOME/.jetpacker-l2"
    ;;
  ktlint)
    KTLINT_GRADLE_REF="${JETPACKER_KTLINT_GRADLE_REF:-3fe589643b916ace8414fca50426beafe2dc245f}"
    git -C /tmp/ktlint fetch -q origin "$KTLINT_GRADLE_REF"
    git -C /tmp/ktlint checkout -q "$KTLINT_GRADLE_REF"
    cp -f "$HOME/.jetpacker-l2/certified.tsv" "$HOME/.jetpacker-l2-ktlint/certified.tsv"
    run_repo ktlint ktlint /tmp/ktlint "$HOME/.jetpacker-l2-ktlint"
    ;;
  both)
    run_repo detekt detekt /tmp/detekt "$HOME/.jetpacker-l2"
    KTLINT_GRADLE_REF="${JETPACKER_KTLINT_GRADLE_REF:-3fe589643b916ace8414fca50426beafe2dc245f}"
    git -C /tmp/ktlint fetch -q origin "$KTLINT_GRADLE_REF"
    git -C /tmp/ktlint checkout -q "$KTLINT_GRADLE_REF"
    cp -f "$HOME/.jetpacker-l2/certified.tsv" "$HOME/.jetpacker-l2-ktlint/certified.tsv"
    run_repo ktlint ktlint /tmp/ktlint "$HOME/.jetpacker-l2-ktlint"
    ;;
  *)
    echo "usage: $0 [detekt|ktlint|both]" >&2
    exit 1
    ;;
esac

echo "[$(date +%H:%M:%S)] phase-1 rerun complete"
