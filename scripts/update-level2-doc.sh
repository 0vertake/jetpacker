#!/usr/bin/env bash
# Refresh the detekt (or ktlint) section in docs/level2.md from a Level-2 ledger.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
repo="${1:-detekt}"
case "$repo" in
  detekt)
    prefix="detekt_"
    ledger="${LEDGER:-$HOME/.jetpacker-l2/level2.tsv}"
    marker="DETEKT_L2"
    ;;
  ktlint)
    prefix="pinterest_ktlint-"
    ledger="${LEDGER:-$HOME/.jetpacker-l2-ktlint/level2.tsv}"
    marker="KTLINT_L2"
    ;;
  *)
    echo "usage: $0 [detekt|ktlint]" >&2
    exit 1
    ;;
esac
certified="$HOME/.jetpacker-l2/certified.tsv"
doc="$root/docs/level2.md"
[[ -f "$ledger" ]] || { echo "missing ledger: $ledger" >&2; exit 1; }

section="$(python3 - "$ledger" "$certified" "$prefix" "$repo" <<'PY'
import sys
from collections import Counter, defaultdict
from datetime import date

ledger, certified_path, prefix, label = sys.argv[1:5]
rows = [l.strip().split("\t") for l in open(ledger) if l.strip()]
cert = set()
for line in open(certified_path):
    p = line.strip().split("\t")
    if len(p) >= 3 and p[0].startswith(prefix) and p[1] == "RESOLVED" and p[2] == "UNRESOLVED":
        cert.add(p[0])

by_task = defaultdict(dict)
tokens = defaultdict(list)
for parts in rows:
    if len(parts) < 3:
        continue
    task, arm, outcome = parts[0], parts[1], parts[2]
    if not task.startswith(prefix):
        continue
    by_task[task][arm] = outcome
    if len(parts) > 3 and parts[3].isdigit():
        tokens[arm].append(int(parts[3]))

arms = ["none", "chunk-bm25", "bm25", "jp"]
complete = sorted(t for t in cert if set(arms).issubset(by_task[t]))
partial = sorted(t for t in cert if 0 < len(by_task.get(t, {})) < len(arms))
pending = sorted(t for t in cert if len(by_task.get(t, {})) == 0)

def pct(n, d):
    return f"{n}/{d} ({100 * n // d}%)" if d else "0/0"

# summary on complete tasks only
summary = {a: Counter(by_task[t][a] for t in complete) for a in arms}
n = len(complete)

def pairwise(pred):
    return sum(1 for t in complete if pred(by_task[t]))

lines = []
title = label.capitalize()
status = "complete" if n == len(cert) and not pending else "in progress"
lines.append(f"## {title} results ({n}/{len(cert)} tasks, bodies-only, 4k)")
lines.append("")
lines.append(
    f"*Auto-generated {date.today().isoformat()} from `{ledger}` — "
    f"run `scripts/update-level2-doc.sh {label}` to refresh.*"
)
lines.append("")
if status == "in progress":
    lines.append(
        f"**{title} Level 2 is in progress.** {n} of {len(cert)} certified tasks have all "
        f"four arms scored; {len(pending)} not started, {len(partial)} partial."
    )
    lines.append("")
lines.append("### Summary")
lines.append("")
lines.append("| arm | resolved | no answer | not applied | unresolved |")
lines.append("|-----|----------|-----------|-------------|------------|")
for arm in arms:
    c = summary[arm]
    lines.append(
        f"| `{arm}` | {pct(c.get('RESOLVED', 0), n)} "
        f"| {c.get('NO_ANSWER', 0)} | {c.get('NOT_APPLIED', 0)} "
        f"| {c.get('UNRESOLVED', 0)} |"
    )
if tokens:
    avg = {a: sum(v) / len(v) for a, v in tokens.items() if v and a != "none"}
    if avg:
        lines.append("")
        lines.append(
            "Retrieval arms average "
            + ", ".join(f"`{a}` ~{avg[a]:.0f} tokens" for a in arms if a in avg)
            + "; `none` is 0."
        )
lines.append("")
lines.append("### Pairwise (complete tasks only)")
lines.append("")
lines.append("| comparison | count |")
lines.append("|------------|-------|")
pairs = [
    ("`jp` resolves, `none` does not", lambda o: o.get("jp") == "RESOLVED" and o.get("none") != "RESOLVED"),
    ("`bm25` resolves, `none` does not", lambda o: o.get("bm25") == "RESOLVED" and o.get("none") != "RESOLVED"),
    ("`bm25` resolves, `jp` does not", lambda o: o.get("bm25") == "RESOLVED" and o.get("jp") != "RESOLVED"),
    ("`jp` resolves, `bm25` does not", lambda o: o.get("bm25") != "RESOLVED" and o.get("jp") == "RESOLVED"),
    ("all four arms resolve", lambda o: all(o.get(a) == "RESOLVED" for a in arms)),
    ("only `none` resolves", lambda o: o.get("none") == "RESOLVED" and all(o.get(a) != "RESOLVED" for a in arms[1:])),
]
for name, fn in pairs:
    lines.append(f"| {name} | {pairwise(fn)}/{n} |")

lines.append("")
lines.append("### Per-task outcomes")
lines.append("")
lines.append("| task | `none` | `chunk-bm25` | `bm25` | `jp` |")
lines.append("|------|--------|--------------|--------|------|")
for task in sorted(cert):
    short = task.split("_")[-1]
    o = by_task.get(task, {})
    mark = "✓" if task in complete else ("…" if task in partial else " ")
    lines.append(
        "| "
        + " | ".join(
            [f"{mark} {short}"]
            + [o.get(a, "—") for a in arms]
        )
        + " |"
    )

if pending or partial:
    lines.append("")
    lines.append("### Not yet complete")
    lines.append("")
    lines.append("| task | status |")
    lines.append("|------|--------|")
    for task in partial:
        missing = [a for a in arms if a not in by_task[task]]
        lines.append(f"| {task.split('_')[-1]} | partial — missing {', '.join(f'`{a}`' for a in missing)} |")
    for task in pending:
        lines.append(f"| {task.split('_')[-1]} | not started |")

print("\n".join(lines))
PY
)"

python3 - "$doc" "$marker" "$section" <<'PY'
import sys
path, marker, section = sys.argv[1], sys.argv[2], sys.argv[3]
start = f"<!-- {marker}_START -->"
end = f"<!-- {marker}_END -->"
text = open(path).read()
if start not in text or end not in text:
    print(f"markers {marker} missing in {path}", file=sys.stderr)
    sys.exit(1)
before, rest = text.split(start, 1)
_, after = rest.split(end, 1)
open(path, "w").write(f"{before}{start}\n{section}\n{end}{after}")
print(f"updated {path} ({marker})")
PY
