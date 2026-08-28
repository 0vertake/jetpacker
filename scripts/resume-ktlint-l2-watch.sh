#!/usr/bin/env bash
# Restart ktlint Level 2 when the JVM exits (API silence, crash) until 43/43 tasks complete.
# Refresh docs/level2.md every loop. Run under screen + caffeinate.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
log="$HOME/.jetpacker-l2/level2-ktlint-watch.log"
ledger="$HOME/.jetpacker-l2-ktlint/level2.tsv"
certified="$HOME/.jetpacker-l2/certified.tsv"
target_tasks=43
poll="${JETPACKER_L2_POLL_SEC:-120}"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$log"; }

count_complete() {
  python3 - "$ledger" "$certified" <<'PY'
import sys
from collections import defaultdict
ledger, certified_path = sys.argv[1:3]
try:
    rows = [l.strip().split("\t") for l in open(ledger) if l.strip()]
except FileNotFoundError:
    print(0)
    raise SystemExit
cert = set()
for line in open(certified_path):
    p = line.strip().split("\t")
    if len(p) >= 3 and p[0].startswith("pinterest_ktlint-") and p[1] == "RESOLVED" and p[2] == "UNRESOLVED":
        cert.add(p[0])
by = defaultdict(set)
for t, a, *_ in rows:
    if t.startswith("pinterest_ktlint-"):
        by[t].add(a)
arms = {"none", "chunk-bm25", "bm25", "jp"}
print(sum(1 for t in cert if arms.issubset(by[t])))
PY
}

log "watch started (target $target_tasks complete tasks, poll ${poll}s)"

while true; do
  complete="$(count_complete)"
  if [[ -s "$ledger" ]]; then
    "$root/scripts/update-level2-doc.sh" ktlint >> "$log" 2>&1 || true
  fi
  if [[ "$complete" -ge "$target_tasks" ]]; then
    log "done — $complete/$target_tasks tasks complete"
    exit 0
  fi
  if ! pgrep -f 'dev.jetpacker.eval.Level2Kt' >/dev/null; then
    log "L2 not running ($complete/$target_tasks) — restarting resume"
    bash "$root/scripts/resume-ktlint-l2.sh" >> "$HOME/.jetpacker-l2/level2-ktlint.log" 2>&1 || \
      log "resume exited $?"
  elif [[ -f "$ledger" ]]; then
    # JVM hung after API crash: process alive but ledger stale for 90+ minutes.
    stale_sec=$(( $(date +%s) - $(stat -f %m "$ledger" 2>/dev/null || stat -c %Y "$ledger") ))
    if (( stale_sec > 5400 )); then
      log "L2 stale (${stale_sec}s since last score) — killing and restarting"
      pkill -f 'dev.jetpacker.eval.Level2Kt' || true
      sleep 2
      bash "$root/scripts/resume-ktlint-l2.sh" >> "$HOME/.jetpacker-l2/level2-ktlint.log" 2>&1 || \
        log "resume exited $?"
    fi
  fi
  log "status: $complete/$target_tasks tasks complete"
  sleep "$poll"
done
