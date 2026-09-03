#!/usr/bin/env bash
# Restart ktlint Phase-1 rerun when the JVM exits (API down, 4× NO_ANSWER) until 43/43 × 4 arms.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
log="$HOME/.jetpacker-l2/phase1-ktlint-watch.log"
ledger="$HOME/.jetpacker-l2-ktlint/level2.tsv"
certified="$HOME/.jetpacker-l2/certified.tsv"
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

log "phase-1 ktlint watch started (target 43/43 tasks, poll ${poll}s)"

while true; do
  complete="$(count_complete)"
  if [[ -s "$ledger" ]]; then
    "$root/scripts/update-level2-doc.sh" ktlint >> "$log" 2>&1 || true
  fi
  if [[ "$complete" -ge 43 ]]; then
    log "done — $complete/43 tasks complete"
    exit 0
  fi
  if ! pgrep -f 'dev.jetpacker.eval.Level2Kt' >/dev/null; then
    log "L2 not running ($complete/43) — restarting phase-1 ktlint rerun"
    bash "$root/scripts/run-l2-phase1-rerun.sh" ktlint >> "$HOME/.jetpacker-l2/phase1-rerun-ktlint.log" 2>&1 || \
      log "phase-1 rerun exited $?"
  fi
  log "status: $complete/43 tasks complete"
  sleep "$poll"
done
