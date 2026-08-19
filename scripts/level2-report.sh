#!/usr/bin/env bash
# Print a markdown summary of ~/.jetpacker-l2/level2.tsv (or pass a path).
set -euo pipefail
ledger="${1:-$HOME/.jetpacker-l2/level2.tsv}"
if [[ ! -s "$ledger" ]]; then
  echo "empty ledger: $ledger" >&2
  exit 1
fi
python3 - "$ledger" <<'PY'
import sys
from collections import Counter, defaultdict

path = sys.argv[1]
rows = [line.strip().split("\t") for line in open(path) if line.strip()]
if not rows:
    sys.exit(0)
by_arm = defaultdict(Counter)
by_task = defaultdict(set)
for parts in rows:
    if len(parts) < 3:
        continue
    task, arm, outcome = parts[0], parts[1], parts[2]
    by_arm[arm][outcome] += 1
    by_task[task].add(arm)

complete = sum(1 for arms in by_task.values() if arms >= {"none", "chunk-bm25", "bm25", "jp"})
print(f"{len(rows)} arms scored, {complete} tasks with all four arms\n")
print("| arm | resolved | no answer | not applied | unresolved | no verdict |")
print("|-----|----------|-----------|-------------|------------|------------|")
order = ["none", "chunk-bm25", "bm25", "jp"]
for arm in order + sorted(set(by_arm) - set(order)):
    c = by_arm[arm]
    total = sum(c.values())
    print(
        f"| `{arm}` | {c.get('RESOLVED', 0)}/{total} "
        f"| {c.get('NO_ANSWER', 0)} | {c.get('NOT_APPLIED', 0)} "
        f"| {c.get('UNRESOLVED', 0)} | {c.get('NO_VERDICT', 0)} |"
    )
PY
