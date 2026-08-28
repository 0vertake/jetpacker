# Level-2 method

Level 1 asks whether a pack contains the declarations a fix touched. Level 2 asks the question a
reader actually cares about: **does a better pack produce a better patch?** One model, one prompt,
one shot, and the suite's own tests decide.

**Detekt Level 2 is complete** (20/20 certified tasks, `composer-2.5`, 4k, bodies-only). **Ktlint Level 2
is in progress** (43 tasks). Ledgers: `~/.jetpacker-l2/level2.tsv` (detekt),
`~/.jetpacker-l2-ktlint/level2.tsv` (ktlint). Resume is safe — already-scored `(task, arm)` pairs are
skipped. Refresh tables with `scripts/update-level2-doc.sh detekt|ktlint`.

<!-- DETEKT_L2_START -->
## Detekt results (20/20 tasks, bodies-only, 4k)

*Auto-generated 2026-08-28 from `/Users/milos/.jetpacker-l2/level2.tsv` — run `scripts/update-level2-doc.sh detekt` to refresh.*

### Summary

| arm | resolved | no answer | not applied | unresolved |
|-----|----------|-----------|-------------|------------|
| `none` | 14/20 (70%) | 3 | 3 | 0 |
| `chunk-bm25` | 14/20 (70%) | 3 | 2 | 1 |
| `bm25` | 16/20 (80%) | 3 | 1 | 0 |
| `jp` | 14/20 (70%) | 4 | 2 | 0 |

Retrieval arms average `chunk-bm25` ~3992 tokens, `bm25` ~3988 tokens, `jp` ~3987 tokens; `none` is 0.

### Pairwise (complete tasks only)

| comparison | count |
|------------|-------|
| `jp` resolves, `none` does not | 4/20 |
| `bm25` resolves, `none` does not | 3/20 |
| `bm25` resolves, `jp` does not | 3/20 |
| `jp` resolves, `bm25` does not | 1/20 |
| all four arms resolve | 9/20 |
| only `none` resolves | 0/20 |

### Per-task outcomes

| task | `none` | `chunk-bm25` | `bm25` | `jp` |
|------|--------|--------------|--------|------|
| ✓ detekt-4205 | RESOLVED | NOT_APPLIED | RESOLVED | RESOLVED |
| ✓ detekt-4249 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-4728 | NO_ANSWER | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-4733 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-4738 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-4808 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-4818 | RESOLVED | RESOLVED | RESOLVED | NOT_APPLIED |
| ✓ detekt-4994 | NOT_APPLIED | NOT_APPLIED | RESOLVED | RESOLVED |
| ✓ detekt-5006 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-5009 | NOT_APPLIED | UNRESOLVED | NOT_APPLIED | RESOLVED |
| ✓ detekt-5252 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-5352 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-5459 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-5516 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-5577 | NOT_APPLIED | RESOLVED | RESOLVED | RESOLVED |
| ✓ detekt-5684 | RESOLVED | NO_ANSWER | RESOLVED | NO_ANSWER |
| ✓ detekt-6352 | RESOLVED | RESOLVED | RESOLVED | NOT_APPLIED |
| ✓ detekt-6443 | RESOLVED | RESOLVED | NO_ANSWER | NO_ANSWER |
| ✓ detekt-6446 | NO_ANSWER | NO_ANSWER | NO_ANSWER | NO_ANSWER |
| ✓ detekt-7715 | NO_ANSWER | NO_ANSWER | NO_ANSWER | NO_ANSWER |
<!-- DETEKT_L2_END -->

### Reading detekt (final)

**Sample:** 20 certified detekt tasks, `composer-2.5`, 4000-token budget, **bodies-only** packing
(`fullTierShare=1.0`). Not the shipped 15% body share; not a pinned model ID. Treat as one
repository slice, not a Kotlin Benchmark score.

**Headline:** **`bm25` resolves 16/20 (80%)**; `none`, `chunk-bm25`, and `jp` each **14/20 (70%)**.
Declaration-level BM25 without graph expansion leads on patch success here; the full engine does not
beat it on this slice.

**The floor is high.** Fourteen of twenty tasks resolve with the issue text alone. That matches
Level-1: detekt issues name the misbehaving rule, and many fixes are localized. Retrieval is often
optional at this budget on this repository — not useless, but not required for most tasks.

**Where the floor failed (4 tasks), retrieval usually helped:**

| task | `none` | best retrieval | note |
|------|--------|----------------|------|
| 4728 | NO_ANSWER | all three RESOLVED | Issue targets `OutdatedDocumentation`; `jp` pack seeds the rule plus related declarations; chunk pack was mostly unrelated 40-line windows |
| 4994 | NOT_APPLIED | `bm25`, `jp` RESOLVED | Chunk and floor produced unapplyable diffs; BM25 pack led with `ExplicitCollectionElementAccessMethod` |
| 5009 | NOT_APPLIED | **`jp` only** RESOLVED | Only graph pack included `UnnecessaryAbstractClass` + test spec; floor patch refactored the wrong layer and did not apply cleanly; chunk patched but **tests failed** |
| 5577 | NOT_APPLIED | all three RESOLVED | Floor diff did not apply; retrieval packs named the naming rule under test |

**5009** is the one task where **`jp` beat flat `bm25`** on patch success: the fix needed the rule
implementation and its spec together; BM25 and chunk did not deliver an applyable correct patch.

**Failure modes are not “wrong fixes”:**

| outcome | count (80 arms) | meaning |
|---------|-------------------|---------|
| `NOT_APPLIED` | 8 | diff did not apply after three attempts |
| `NO_ANSWER` | 13 | no diff (includes Cursor API `Network request failed`) |
| `UNRESOLVED` | 1 | patch applied, verifier failed (`5009` / `chunk-bm25`) |

Tasks **6446** and **7715** are all-`NO_ANSWER` on every arm — backend silence, not retrieval quality.
**5684** and **6443** show the same flakiness on individual arms while other arms on the same task
resolved.

**What not to claim from detekt alone:** that `jp` beats RAG on patch success, that Level-1 recall
transfers to patches, or that bodies-only numbers represent the shipped packer. **Ktlint (43 tasks)**
is the harder regime (issues name behaviour, median gold rank 80 at L1) and is required before any
cross-repository L2 conclusion.

Context packs for every arm are under `~/.jetpacker-l2/detekt_detekt-<id>/<arm>.context.md` for
qualitative replay.

<!-- KTLINT_L2_START -->
## Ktlint results (2/43 tasks, bodies-only, 4k)

*Auto-generated 2026-08-28 from `/Users/milos/.jetpacker-l2-ktlint/level2.tsv` — run `scripts/update-level2-doc.sh ktlint` to refresh.*

**Ktlint Level 2 is in progress.** 2 of 43 certified tasks have all four arms scored; 40 not started, 1 partial.

### Summary

| arm | resolved | no answer | not applied | unresolved |
|-----|----------|-----------|-------------|------------|
| `none` | 1/2 (50%) | 0 | 1 | 0 |
| `chunk-bm25` | 1/2 (50%) | 0 | 1 | 0 |
| `bm25` | 1/2 (50%) | 1 | 0 | 0 |
| `jp` | 1/2 (50%) | 1 | 0 | 0 |

Retrieval arms average `chunk-bm25` ~3990 tokens, `bm25` ~3981 tokens, `jp` ~3990 tokens; `none` is 0.

### Pairwise (complete tasks only)

| comparison | count |
|------------|-------|
| `jp` resolves, `none` does not | 0/2 |
| `bm25` resolves, `none` does not | 0/2 |
| `bm25` resolves, `jp` does not | 0/2 |
| `jp` resolves, `bm25` does not | 0/2 |
| all four arms resolve | 1/2 |
| only `none` resolves | 0/2 |

### Per-task outcomes

| task | `none` | `chunk-bm25` | `bm25` | `jp` |
|------|--------|--------------|--------|------|
| ✓ ktlint-2029 | RESOLVED | RESOLVED | RESOLVED | RESOLVED |
| ✓ ktlint-2053 | NOT_APPLIED | NOT_APPLIED | NO_ANSWER | NO_ANSWER |
| … ktlint-2091 | NO_ANSWER | NO_ANSWER | RESOLVED | — |
|   ktlint-2127 | — | — | — | — |
|   ktlint-2134 | — | — | — | — |
|   ktlint-2141 | — | — | — | — |
|   ktlint-2193 | — | — | — | — |
|   ktlint-2216 | — | — | — | — |
|   ktlint-2263 | — | — | — | — |
|   ktlint-2273 | — | — | — | — |
|   ktlint-2275 | — | — | — | — |
|   ktlint-2287 | — | — | — | — |
|   ktlint-2300 | — | — | — | — |
|   ktlint-2362 | — | — | — | — |
|   ktlint-2365 | — | — | — | — |
|   ktlint-2375 | — | — | — | — |
|   ktlint-2381 | — | — | — | — |
|   ktlint-2386 | — | — | — | — |
|   ktlint-2395 | — | — | — | — |
|   ktlint-2400 | — | — | — | — |
|   ktlint-2405 | — | — | — | — |
|   ktlint-2407 | — | — | — | — |
|   ktlint-2431 | — | — | — | — |
|   ktlint-2432 | — | — | — | — |
|   ktlint-2442 | — | — | — | — |
|   ktlint-2493 | — | — | — | — |
|   ktlint-2505 | — | — | — | — |
|   ktlint-2516 | — | — | — | — |
|   ktlint-2541 | — | — | — | — |
|   ktlint-2553 | — | — | — | — |
|   ktlint-2554 | — | — | — | — |
|   ktlint-2555 | — | — | — | — |
|   ktlint-2617 | — | — | — | — |
|   ktlint-2715 | — | — | — | — |
|   ktlint-2727 | — | — | — | — |
|   ktlint-2774 | — | — | — | — |
|   ktlint-2785 | — | — | — | — |
|   ktlint-2823 | — | — | — | — |
|   ktlint-2873 | — | — | — | — |
|   ktlint-2891 | — | — | — | — |
|   ktlint-2895 | — | — | — | — |
|   ktlint-2950 | — | — | — | — |
|   ktlint-2983 | — | — | — | — |

### Not yet complete

| task | status |
|------|--------|
| ktlint-2091 | partial — missing `jp` |
| ktlint-2127 | not started |
| ktlint-2134 | not started |
| ktlint-2141 | not started |
| ktlint-2193 | not started |
| ktlint-2216 | not started |
| ktlint-2263 | not started |
| ktlint-2273 | not started |
| ktlint-2275 | not started |
| ktlint-2287 | not started |
| ktlint-2300 | not started |
| ktlint-2362 | not started |
| ktlint-2365 | not started |
| ktlint-2375 | not started |
| ktlint-2381 | not started |
| ktlint-2386 | not started |
| ktlint-2395 | not started |
| ktlint-2400 | not started |
| ktlint-2405 | not started |
| ktlint-2407 | not started |
| ktlint-2431 | not started |
| ktlint-2432 | not started |
| ktlint-2442 | not started |
| ktlint-2493 | not started |
| ktlint-2505 | not started |
| ktlint-2516 | not started |
| ktlint-2541 | not started |
| ktlint-2553 | not started |
| ktlint-2554 | not started |
| ktlint-2555 | not started |
| ktlint-2617 | not started |
| ktlint-2715 | not started |
| ktlint-2727 | not started |
| ktlint-2774 | not started |
| ktlint-2785 | not started |
| ktlint-2823 | not started |
| ktlint-2873 | not started |
| ktlint-2891 | not started |
| ktlint-2895 | not started |
| ktlint-2950 | not started |
| ktlint-2983 | not started |
<!-- KTLINT_L2_END -->

### Reading ktlint (template — fill when 43/43 complete)

**Do not fill until the ledger has all 43 tasks × 4 arms.** Use `scripts/update-level2-doc.sh ktlint`
for the table, then replace this section.

**Decision tree after ktlint completes:**

| If… | Then write… |
|-----|-------------|
| `jp` resolved >> `bm25` >> `none` (e.g. +10 pts on `jp`) | Level-1 recall gap **carries to patches** on the hard suite; graph expansion pays at patch time |
| `bm25` ≈ `jp` >> `none` | Declaration retrieval helps; **graph expansion does not** add patch success on ktlint |
| All arms ≈ `none` (high floor) | Same shape as detekt — issues/rules carry enough signal; packs matter less at 4k |
| `none` often best | Retrieval **hurts** or adds noise — investigate pack content on those tasks |
| Many `NO_ANSWER` / `NOT_APPLIED` | Report delivery failure rate separately; do not treat as verifier losses |

**Compare to detekt (20/20, bodies-only):** detekt `bm25` 16/20, `jp` 14/20, floor 14/20. ktlint Level-1
has **lower recall and deeper gold ranks** — if `jp` wins here after losing on detekt, that supports
“engine helps when keyword seeds miss.” If `bm25` wins again, prefer “declaration retrieval > graph”
as the L2 story.

**Placeholder summary (update counts):**

| arm | resolved | notes |
|-----|----------|-------|
| `none` | _/_ | floor |
| `chunk-bm25` | _/_ | chunk-RAG baseline |
| `bm25` | _/_ | declaration BM25 |
| `jp` | _/_ | full engine |

Everything below was fixed before the first run, which is the point of writing it down first.

## The judge is not ours

Every Kotlin Benchmark task ships a Docker environment and a verifier. The verifier applies the
task's test patch, runs the suite, classifies each expected test by how its status moved —
fail-to-pass for the bug, pass-to-pass for everything that must not regress — and writes `1` or `0`
to `reward.txt`. That script is the scorer, run exactly as the suite defines it. A Level-2 number
where the judge is ours would be worth nothing.

Each arm runs in a container started from the task image, which sits at the base commit with a warm
Gradle cache, and the container is destroyed afterwards, so one arm's patch cannot reach the next.

## Certification: which tasks are allowed to count

A resolved-percentage means nothing unless a task can be resolved *and* can be failed. Each
candidate is therefore run twice against its own verifier before any model is called: once with the
gold patch, which must come out resolved, and once with no patch at all, which must not. This is not
a formality — all 105 tasks in the suite declare `[verifier] implemented = false`.

**63 tasks certified** across two repositories — every task whose image built and whose gold patch
resolved: 20 of detekt's 28, and all 43 of ktlint. The ledger is [`docs/certified.tsv`](certified.tsv).
Gold resolved and empty failed on every one of them.

What is missing, and why, is also measured:

- `detekt_detekt-4628` cannot be built on this machine: its build runs detekt's Gradle-plugin tests,
  which include Android functional tests, and AAPT2's native daemon fails to start in the container.
  Recorded as `NO_IMAGE` and excluded rather than scored as anything.
- `detekt_detekt-7212` and `7635` built, but the gold patch did not resolve. They are in the ledger
  as unusable, not as model failures.
- Five later detekt tasks (`7625`, `7667`, `7718`, `7871`, `7888`) OOM'd during `prepare.sh` inside
  Docker Desktop's 8GB VM. Image-build failures are not written to the ledger, so they are retried
  rather than silently dropped. They are not in the sample until they certify.

Certification cost between 4 minutes and an hour per task, almost all of it the image build, which
runs the repository's whole test suite as a build step. ktlint's later tasks landed around 4 minutes
once their base image was warm.

## What the model is told

The prompt is identical for every arm except one block: the retrieved context. A missing block,
rather than an empty one, is the no-context floor. The model is asked for a unified diff and nothing
else, and told the diff in its reply is the whole answer.

The agent runs in an **empty directory**. It is an agent rather than a completion endpoint, so it can
read and search files, and if it could see the repository it would retrieve its own context and every
arm would be measuring the same thing.

A reply's diff gets three attempts to apply: strict, then a 3-way merge, then a recount that rebuilds
hunk line counts from the file. A pack names a declaration's file and first line but not the line
numbers inside it, so hunk headers are the model's arithmetic rather than something it can read off.
Every arm gets the same three attempts, and a patch that never applies is recorded as `NOT_APPLIED`,
never as a failed fix. A reply with no diff in it at all is `NO_ANSWER`, which is a different failure
and counted separately.

## The arms

| arm | what it is |
|-----|------------|
| `none` | the issue alone — the floor that says whether any retrieval was worth doing |
| `chunk-bm25` | 40-line windows, BM25-ranked: what chunk-RAG does |
| `bm25` | whole declarations, BM25-ranked, no graph expansion |
| `jp` | the engine: seeds, resolved-graph expansion, ranking, knapsack packing |

Budget is 4000 tokens for every arm.

**No arm runs the shipped body share.** Both retrieval arms spend the entire budget on full bodies,
because a model cannot edit a signature, while the packer ships 15% bodies because that is what
maximized Level-1 recall. Whether recall-optimal packing is also patch-optimal is a real question
and this sample cannot answer it: it runs one body share, so packing policy and retrieval stay
confounded. These numbers are therefore not the Level-1 engine's score carried forward.

## What this sample can and cannot say

63 tasks, two repositories, four arms. A one-task difference is still not a result. A ten-task gap
between arms on ktlint's 43 would be, and nothing here will be reported as a Kotlin Benchmark score
— one unpinned model, one shot, and bodies-only packing that the product does not ship.

Two properties of the sample are worth knowing, both checked rather than assumed:

- **No issue text contains a diff.** Of the 63, 61 name none of the Kotlin/Java files the gold patch
  touches. The two exceptions are ktlint crash reports (`2029`, `2541`) whose stack traces mention a
  gold file the way a JVM dump does, not by describing the change. The rest of those patches, and
  every detekt fix, still have to be found. Where a detekt issue names a Kotlin file, it belongs to
  the reporter's own project — an AWS toolkit, a screenshot library, a chat SDK — whose code
  triggered the false positive.
- **The issues name code.** They name the rule that misbehaved, which is what the seed finder keys
  on. That is the regime the engine is built for, and Level 1 already reports a repository where
  the text describes user-visible behaviour instead and the engine loses.

The model is a Cursor agent on `composer-2.5`, which is not pinned the way `gpt-x-2026-01-01` is
pinned. It costs nothing to run against an existing subscription, and the trade is that this
measures whether the Level-1 gap survives into patches — not a number to set beside published
SWE-bench scores.

## Reproducing

```bash
# Certify first: two full suite runs per task, hours, resumable.
# Base images must exist (`scripts/build_bases.sh` in the benchmark repo).
./gradlew :eval:certify -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks \
  -Pjetpacker.harbor.repo=detekt
./gradlew :eval:certify -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks \
  -Pjetpacker.harbor.repo=ktlint

# Then score, one repository at a time. Needs CURSOR_API_KEY.
./gradlew :eval:level2 -Pjetpacker.repo=/tmp/detekt \
  -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=detekt
./gradlew :eval:level2 -Pjetpacker.repo=/tmp/ktlint \
  -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=ktlint
```

Gradle-free resume (same classpath as overnight runs), after `installDist` synced
to `~/.jetpacker-l2/lib/`:

```bash
# detekt — resumes skipped (task, arm) pairs; uses fixed cursor_patch.py from checkout
screen -dmS jetpacker-l2 bash -c 'caffeinate -i scripts/resume-detekt-l2.sh >> ~/.jetpacker-l2/level2.log 2>&1'

# auto-resume detekt, refresh docs/level2.md, then start ktlint when detekt hits 20/20
screen -dmS jetpacker-l2-chain bash -c 'caffeinate -i scripts/chain-ktlint-after-detekt.sh'

# ktlint manually — separate ledger, needs /tmp/ktlint cloned first
git clone https://github.com/pinterest/ktlint /tmp/ktlint
screen -dmS jetpacker-l2-ktlint bash -c 'caffeinate -i scripts/resume-ktlint-l2.sh >> ~/.jetpacker-l2/level2-ktlint.log 2>&1'

# auto-restart ktlint on API crash + refresh docs/level2.md every 2 min
screen -dmS jetpacker-l2-ktlint-watch bash -c 'caffeinate -i scripts/resume-ktlint-l2-watch.sh'

# snapshot the ledger as markdown
scripts/level2-report.sh
scripts/update-level2-doc.sh detekt
scripts/update-level2-doc.sh ktlint   # once ktlint ledger exists
```

Both outlive a shell; run them under `screen`, never concurrently with each other or with a Gradle
build of this repository. The runner skips `(task, arm)` pairs already in the ledger, so resume
after a Cursor API blip is safe. Transient SDK failures score as `NO_ANSWER` rather than aborting
the run; four consecutive silences still stop it, which means the backend rather than one pack. `-Djetpacker.patcher=<script.py>` swaps the model backend, or a stub, which
is how the loop is exercised without spending calls. `-Djetpacker.fullTierShare=0.15` with a separate
`-Djetpacker.l2=~/.jetpacker-l2-bodies15` runs the shipped body share without overwriting the
bodies-only ledger. Each arm's context is written to
`~/.jetpacker-l2/<task>/<arm>.context.md`, beside the verifier's logs, so any resolved-count can be
read back to the pack that caused it.
