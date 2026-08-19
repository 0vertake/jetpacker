#!/usr/bin/env bash
# Resume detekt Level 2 (Gradle-free). Same layout as ~/.jetpacker-l2/resume.sh.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export CURSOR_API_KEY
CURSOR_API_KEY="$(tr -d '[:space:]' < "$HOME/.cursor_api_key")"
if [[ -z "$CURSOR_API_KEY" ]]; then
  echo "CURSOR_API_KEY empty" >&2
  exit 1
fi
if [[ ! -d /tmp/detekt/.git ]]; then
  echo "clone detekt first: git clone https://github.com/detekt/detekt /tmp/detekt" >&2
  exit 1
fi
log="$HOME/.jetpacker-l2/level2.log"
echo "[$(date +%H:%M:%S)] resume detekt Level 2" >> "$log"
exec java -Xmx6g -Didea.is.unit.test=true -Djava.awt.headless=true \
  -Djetpacker.repo=/tmp/detekt \
  -Djetpacker.harbor=/tmp/kotlin-swe-bench/tasks \
  -Djetpacker.harbor.repo=detekt \
  -Djetpacker.patcher="$root/eval/src/main/resources/cursor_patch.py" \
  -Djetpacker.python="$HOME/.jetpacker-l2/venv/bin/python" \
  -cp "$HOME/.jetpacker-l2/lib/*" \
  dev.jetpacker.eval.Level2Kt
