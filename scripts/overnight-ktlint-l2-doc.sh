#!/usr/bin/env bash
# Refresh docs/level2.md ktlint section while L2 runs.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
log="$HOME/.jetpacker/overnight-ktlint-doc.log"
ledger="$HOME/.jetpacker-l2-ktlint/level2.tsv"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$log"; }
log "ktlint L2 doc watcher started"

while pgrep -f 'dev.jetpacker.eval.Level2Kt' >/dev/null 2>&1; do
  if [[ -s "$ledger" ]]; then
    "$root/scripts/update-level2-doc.sh" ktlint >> "$log" 2>&1 || true
    arms=$(wc -l < "$ledger" | tr -d ' ')
    log "refreshed level2.md ($arms arms)"
  fi
  sleep 600
done

"$root/scripts/update-level2-doc.sh" ktlint >> "$log" 2>&1 || true
log "ktlint L2 finished — final doc refresh"
