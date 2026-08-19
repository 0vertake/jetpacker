#!/usr/bin/env bash
# Poll until detekt L2 finishes (20 certified tasks × 4 arms), then start ktlint in screen.
# If detekt stops early, restart it via resume-detekt-l2.sh. Run under screen/caffeinate.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
detekt_ledger="$HOME/.jetpacker-l2/level2.tsv"
ktlint_log="$HOME/.jetpacker-l2/level2-ktlint.log"
poll_log="$HOME/.jetpacker-l2/chain.log"
target_arms=80

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$poll_log"; }

count_detekt_arms() {
  [[ -f "$detekt_ledger" ]] || { echo 0; return; }
  wc -l < "$detekt_ledger" | tr -d ' '
}

count_detekt_complete() {
  python3 - "$detekt_ledger" <<'PY'
import sys
from collections import defaultdict
path = sys.argv[1]
try:
    rows = [l.strip().split("\t") for l in open(path) if l.strip()]
except FileNotFoundError:
    print(0)
    raise SystemExit
cert = set()
for line in open(f"{__import__('os').path.expanduser('~')}/.jetpacker-l2/certified.tsv"):
    p = line.strip().split("\t")
    if len(p) >= 3 and p[0].startswith("detekt_") and p[1] == "RESOLVED" and p[2] == "UNRESOLVED":
        cert.add(p[0])
by = defaultdict(set)
for t, a, *_ in rows:
    by[t].add(a)
print(sum(1 for t in cert if {"none", "chunk-bm25", "bm25", "jp"}.issubset(by[t])))
PY
}

l2_running() { pgrep -f 'dev.jetpacker.eval.Level2Kt' >/dev/null; }

start_detekt() {
  log "starting detekt resume ($(count_detekt_arms)/$target_arms arms)"
  screen -dmS jetpacker-l2 bash -c \
    "caffeinate -i '$root/scripts/resume-detekt-l2.sh' >> '$HOME/.jetpacker-l2/level2.log' 2>&1"
}

start_ktlint() {
  log "detekt complete — starting ktlint Level 2"
  "$root/scripts/update-level2-doc.sh" detekt || true
  screen -dmS jetpacker-l2-ktlint bash -c \
    "caffeinate -i '$root/scripts/resume-ktlint-l2.sh' >> '$ktlint_log' 2>&1"
}

mkdir -p "$HOME/.jetpacker-l2-ktlint"
cp -f "$HOME/.jetpacker-l2/certified.tsv" "$HOME/.jetpacker-l2-ktlint/certified.tsv"

log "chain watcher started (target $target_arms detekt arms, 20 complete tasks)"

while true; do
  arms="$(count_detekt_arms)"
  complete="$(count_detekt_complete)"
  if [[ "$complete" -ge 20 && "$arms" -ge "$target_arms" ]]; then
    start_ktlint
    log "done"
    exit 0
  fi
  if ! l2_running; then
    start_detekt
  fi
  "$root/scripts/update-level2-doc.sh" detekt >/dev/null 2>&1 || true
  log "detekt $complete/20 tasks complete, $arms/$target_arms arms"
  sleep 120
done
