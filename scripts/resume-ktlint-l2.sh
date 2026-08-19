#!/usr/bin/env bash
# Resume ktlint Level 2 after detekt finishes. Uses the same jar layout as ~/.jetpacker-l2/resume.sh.
set -euo pipefail
export CURSOR_API_KEY
CURSOR_API_KEY="$(tr -d '[:space:]' < "$HOME/.cursor_api_key")"
if [[ -z "$CURSOR_API_KEY" ]]; then
  echo "CURSOR_API_KEY empty" >&2
  exit 1
fi
if [[ ! -d /tmp/ktlint/.git ]]; then
  echo "clone ktlint first: git clone https://github.com/pinterest/ktlint /tmp/ktlint" >&2
  exit 1
fi
root="$(cd "$(dirname "$0")/.." && pwd)"
log="$HOME/.jetpacker-l2/level2-ktlint.log"
mkdir -p "$HOME/.jetpacker-l2-ktlint"
cp -f "$HOME/.jetpacker-l2/certified.tsv" "$HOME/.jetpacker-l2-ktlint/certified.tsv"
echo "[$(date +%H:%M:%S)] resume ktlint Level 2" >> "$log"
exec java -Xmx6g -Didea.is.unit.test=true -Djava.awt.headless=true \
  -Djetpacker.repo=/tmp/ktlint \
  -Djetpacker.harbor=/tmp/kotlin-swe-bench/tasks \
  -Djetpacker.harbor.repo=ktlint \
  -Djetpacker.l2="$HOME/.jetpacker-l2-ktlint" \
  -Djetpacker.patcher="$root/eval/src/main/resources/cursor_patch.py" \
  -Djetpacker.python="$HOME/.jetpacker-l2/venv/bin/python" \
  -cp "$HOME/.jetpacker-l2/lib/*" \
  dev.jetpacker.eval.Level2Kt
