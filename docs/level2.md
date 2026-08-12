# Level-2 method

Level 1 asks whether a pack contains the declarations a fix touched. Level 2 asks the question a
reader actually cares about: **does a better pack produce a better patch?** One model, one prompt,
one shot, and the suite's own tests decide.

Results are not in this document yet. Everything below is fixed before any model runs, which is the
point of writing it down first.

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

Of the first ten detekt tasks, **nine certified**. The exception is `detekt_detekt-4628`, whose image
cannot be built on this machine: its build runs detekt's Gradle-plugin tests, which include Android
functional tests, and AAPT2's native daemon fails to start in the container. That is an environment
limit, recorded as such, and the task is excluded rather than scored as anything.

Certification cost between 4 minutes and an hour per task, almost all of it the image build, which
runs the repository's whole test suite as a build step.

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
and a nine-task sample cannot answer it. These numbers are therefore not the Level-1 engine's score
carried forward.

## What this sample can and cannot say

Nine tasks, one repository, four arms. A one-task difference is not a result, and nothing here will
be reported as a Kotlin Benchmark score.

Two properties of the sample are worth knowing, both checked rather than assumed:

- **No issue text leaks the fix.** None of the nine contains a diff, and not one names the file its
  fix touches. Where an issue does name a Kotlin file, it belongs to the reporter's own project — an
  AWS toolkit, a screenshot library, a chat SDK — whose code triggered the false positive. Locating
  detekt's rule implementation is left entirely to retrieval.
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
./gradlew :eval:certify -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks \
  -Pjetpacker.harbor.repo=detekt -Pjetpacker.tasks=10

# Then score the certified tasks. Needs CURSOR_API_KEY.
./gradlew :eval:level2 -Pjetpacker.repo=/tmp/detekt \
  -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=detekt
```

Both outlive a shell; run them under `screen`, never concurrently with each other or with a Gradle
build of this repository. `-Djetpacker.patcher=<script.py>` swaps the model backend, or a stub, which
is how the loop is exercised without spending calls. Each arm's context is written to
`~/.jetpacker-l2/<task>/<arm>.context.md`, beside the verifier's logs, so any resolved-count can be
read back to the pack that caused it.
