#!/usr/bin/env bash
# Drop flaky Level-2 rows so the runner will re-score them.
# Usage: l2-prune-flaky.sh LEDGER [OUTCOME ...]
# Default outcomes: NO_ANSWER NOT_APPLIED
set -euo pipefail

ledger="${1:?ledger path required}"
shift || true
if (($# == 0)); then
  set -- NO_ANSWER NOT_APPLIED
fi

if [[ ! -f "$ledger" ]]; then
  echo "ledger missing: $ledger" >&2
  exit 1
fi

tmp="$(mktemp)"
python3 - "$ledger" "$tmp" "$@" <<'PY'
import sys

ledger, out_path, *outcomes = sys.argv[1:]
drop = set(outcomes)
kept = []
removed = 0
with open(ledger, encoding="utf-8") as fh:
    for line in fh:
        row = line.rstrip("\n").split("\t")
        if len(row) >= 3 and row[2] in drop:
            removed += 1
            continue
        if line.strip():
            kept.append(line.rstrip("\n"))
with open(out_path, "w", encoding="utf-8") as fh:
    if kept:
        fh.write("\n".join(kept) + "\n")
PY

mv "$tmp" "$ledger"
echo "pruned $ledger — removed rows with outcome in: $*"
