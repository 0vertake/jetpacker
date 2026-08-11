# Level-1 results

Retrieval quality only: no model, no API spend (docs/plan.md §5). Every number here comes from
`:eval:run` and can be reproduced with the command at the bottom.

## What is measured

Tasks come from two sources. The first is the **Kotlin Benchmark**
([Kotlin/kotlin-swe-bench](https://github.com/Kotlin/kotlin-swe-bench)), JetBrains' 105-task Harbor
suite: the text is the GitHub issue, written by someone who had not found the code yet. The second
is **mined git history**, SWE-bench's own construction: the text is the commit message. Issue text
is the input a retriever actually gets, so it leads the results below.

Either way a task's **gold** is the set of declarations the patch touched, taken from the index of
the base commit, so nothing in the gold set depends on the fix existing yet. Where a Harbor task
ships its regression test as a separate patch, only the fix patch counts — a test written alongside
the fix was not findable from the issue that preceded both.

Only the innermost declaration counts. A patch that edits one line of one method credits that
method, not the class around it and not the file — otherwise dumping whole files would score as
retrieval.

**Recall@budget** is the fraction of gold declarations that appear in a pack built to the budget.
That is the number to read. Precision is reported for honesty but is close to meaningless here:
gold is one to three declarations and a 4k pack holds around a hundred, so a perfect retriever
still scores about 2%.

## Retrievers

All of them use the same packer, the same tokenizer, the same budget, and the same task text. The
only thing that varies is which declarations get chosen and in what order.

| name | what it is |
|------|-----------|
| `jp:default` | the shipped engine: 15% of the budget on whole bodies, the rest on signatures |
| `jp:all-stubs` | the same, spending nothing on bodies |
| `jp:seed-tests` | ablation: test declarations allowed to seed on their names |
| `seeds-only` | ablation: structural expansion switched off, same seed ranking |
| `bm25:full.00` | BM25 over whole declarations, signatures only |
| `bm25:full.30` | BM25 over whole declarations, 30% of the budget on bodies |
| `chunk-bm25` | chunk RAG: fixed 40-line windows ranked by BM25 |
| `repo-map` | Aider's repo map, ported from `repomap.py` |
| `file-dump` | whole top-ranked files until the budget runs out |

## Results: Kotlin Benchmark issues

All 28 detekt tasks in the suite, base commits spanning 2021 to 2025. Recall@budget:

| retriever | 1k | 2k | 4k | 8k |
|-----------|----|----|----|----|
| `jp:all-stubs` | 43.9% | 54.3% | **78.7%** | **89.0%** |
| `jp:default` | 42.8% | 53.5% | 70.8% | 81.2% |
| `jp:seed-tests` | 39.9% | **54.9%** | 77.6% | 82.9% |
| `seeds-only` | 29.9% | 44.6% | 57.7% | 66.5% |
| `bm25:full.00` | **48.8%** | 55.7% | 63.9% | 70.5% |
| `bm25:full.30` | 39.3% | 51.8% | 36.2% | 20.7% |
| `chunk-bm25` | 11.4% | 22.4% | 26.5% | 37.3% |
| `repo-map` | 0.7% | 0.7% | 1.4% | 1.4% |
| `file-dump` | 18.7% | 10.3% | 7.7% | 4.1% |

**Structure pays more on issue text than on commit messages.** Against BM25 over the same
declarations, `jp:all-stubs` is 14.8 points ahead at 4k here and 7.6 ahead on mined commits. That is
the direction the design predicts and the reason the mined numbers are the conservative ones: a
commit message is written by someone who has already found the code and frequently names it, so
keyword matching is closer to reading the answer off the task.

**Below 2k, matching the words still wins.** BM25 leads by 4.9 points at 1k. With room for a few
dozen signatures, being right about the first handful beats expanding around them, and the engine's
advantage only appears once the budget can hold a neighbourhood. Same shape as on Exposed.

**One test-seeding result flips.** `jp:seed-tests` — test declarations allowed to seed on their own
names — costs 12.8 points at 8k on mined commits but beats the shipped default here at 2k, 4k and
8k. Kotlin Benchmark issues quote failing test code in their reproduction steps, so the test suite
is a legitimate entry point rather than a spec-name coincidence. The penalty stays on: with it,
`jp:all-stubs` beats `jp:seed-tests` at every budget on both suites except 2k here, where it loses
by 0.6 — so keeping it costs almost nothing on issues and is worth 12.8 points on commit messages.

At 4k the gold declaration is somewhere in the ranking for all 28 tasks and survives the budget for
21, at a median rank of 12. The seven misses are the same species as on mined commits: issues about
a rule's *behaviour* whose fix lands in a visitor nobody named, where the top seeds are the rule
class and the API it implements and the edited helper sits a hundred places down.

### A second repository from the suite

ktlint, all 43 of its Kotlin Benchmark tasks — the largest block in the suite, and much harder than
detekt in absolute terms:

| retriever | 1k | 2k | 4k | 8k |
|-----------|----|----|----|----|
| `jp:all-stubs` | **17.2%** | **28.9%** | **41.9%** | **55.0%** |
| `jp:default` | 16.3% | 28.1% | 38.5% | 51.4% |
| `jp:seed-tests` | 13.3% | 21.5% | 32.2% | 36.1% |
| `seeds-only` | 13.0% | 17.0% | 25.4% | 42.7% |
| `bm25:full.00` | 15.5% | 20.1% | 31.5% | 51.4% |
| `bm25:full.30` | 12.1% | 16.6% | 21.6% | 36.1% |
| `chunk-bm25` | 3.4% | 3.4% | 3.4% | 5.8% |
| `repo-map` | 0.6% | 2.5% | 2.8% | 6.8% |
| `file-dump` | 5.1% | 3.8% | 4.8% | 4.3% |

Same ordering as detekt, and this time the engine leads at every budget including 1k. Two things are
different and both are worth reading.

**File recall inverts.** At 4k the engine finds 41.9% of gold declarations while touching 63.9% of
the gold files; BM25 finds 31.5% while touching 82.6%. Expansion concentrates the budget on a
neighbourhood and gets the declarations inside it; keyword ranking scatters signatures across more
files and lands on fewer of the right ones. File-level accuracy would have called that a loss.

**Test seeding is expensive here.** `jp:seed-tests` costs 19 points at 8k, against 12.8 on mined
detekt commits and roughly nothing on detekt issues. ktlint's rule tests are named after the
behaviour they assert, at length, which is exactly the text an issue about that behaviour uses.

The median rank of the best gold declaration is 80, against 12 on detekt: the graph reaches the
answer for all 43 tasks but often only after a hundred other declarations, which is why recall keeps
climbing steeply with the budget rather than saturating.

### A third: ort

12 tasks, on a repository three times detekt's size:

| retriever | 1k | 2k | 4k | 8k |
|-----------|----|----|----|----|
| `jp:all-stubs` | **28.7%** | **42.1%** | **53.6%** | **60.4%** |
| `jp:default` | 28.0% | 37.4% | 51.8% | 59.6% |
| `jp:seed-tests` | 28.0% | 38.0% | 52.4% | 59.6% |
| `seeds-only` | 14.2% | 26.3% | 36.8% | 44.7% |
| `bm25:full.00` | 13.7% | 19.4% | 24.8% | 42.4% |
| `bm25:full.30` | 13.7% | 17.9% | 18.7% | 21.5% |
| `chunk-bm25` | 4.8% | 11.5% | 13.3% | 25.9% |
| `repo-map` | 1.3% | 1.3% | 7.3% | 33.0% |
| `file-dump` | 7.8% | 11.1% | 10.7% | 24.7% |

This is the widest margin in the suite: at 4k the engine finds more than twice what BM25 does, and
at 1k, where keyword search wins on detekt and Exposed, it wins by 15 points. Twelve tasks is a
small sample, but the direction is consistent with size — the bigger the repository, the less a
keyword can localize on its own, and ort is the biggest here.

### A fourth: dataframe

5 tasks, the hardest repository in the suite for every retriever — a compiler-plugin-driven DSL
whose gold patches touch as many as 19 declarations at once:

| retriever | 1k | 2k | 4k | 8k |
|-----------|----|----|----|----|
| `jp:all-stubs` | **11.0%** | **16.0%** | 17.1% | **40.2%** |
| `jp:default` | 10.0% | **16.0%** | 17.1% | **40.2%** |
| `jp:seed-tests` | 10.0% | 15.0% | 16.0% | 37.4% |
| `seeds-only` | 4.2% | 6.3% | 18.4% | 18.4% |
| `bm25:full.00` | 4.2% | 12.4% | **21.3%** | 31.3% |
| `bm25:full.30` | 3.2% | 12.4% | **21.3%** | 31.3% |
| `chunk-bm25` | 0.0% | 0.0% | 10.0% | 10.0% |
| `repo-map` | 0.0% | 0.0% | 0.0% | 5.0% |
| `file-dump` | 0.0% | 0.0% | 0.0% | 1.1% |

The engine leads at three budgets of four and by 9 points at 8k, but the 4k row is a genuine loss to
BM25 and worth more than the win: with gold sets this large, no arm gets most of a patch, and which
third of it each one finds is close to arbitrary at a single budget.

These 5 tasks previously scored nothing at all: the Analysis API throws on one of dataframe's DSL
calls — overload resolution reaches a state it asserts cannot happen — and that single call site
aborted the entire index. Resolution failures are now caught where they happen, so the call is
counted as unresolved and the other 73,000 in the repository still are.

That is also the caveat on the whole table. Only **37.6%** of dataframe's call sites resolve to a
declaration, against over 95% on detekt: it is generated-heavy, compiler-plugin-driven code, and the
graph the engine ranks over is correspondingly thin. The gap over BM25 at 8k is what expansion
manages on a third of the edges.

### A fifth: TeXiFy, where the engine loses

8 tasks, and the one repository in the suite where structural expansion is worse than doing nothing:

| retriever | 1k | 2k | 4k | 8k |
|-----------|----|----|----|----|
| `jp:all-stubs` | 0.0% | 12.5% | 16.7% | 16.7% |
| `jp:default` | 0.0% | 12.5% | 12.5% | 16.7% |
| `jp:seed-tests` | 0.0% | 12.5% | 12.5% | 20.8% |
| `seeds-only` | 8.3% | 8.3% | 25.0% | **36.3%** |
| `bm25:full.00` | **16.7%** | **20.8%** | **25.0%** | 30.8% |
| `bm25:full.30` | **16.7%** | **20.8%** | **25.0%** | 29.6% |
| `chunk-bm25` | 12.5% | 12.5% | 12.5% | 12.5% |
| `repo-map` | 0.0% | 0.0% | 0.0% | 0.0% |
| `file-dump` | 0.0% | 0.0% | 0.0% | 0.0% |

The engine loses to BM25 at every budget and, more damningly, to its own seeds-only ablation at 4k
and 8k. Turning the graph off makes it *better* here, which is the opposite of every other result in
this document.

The cause is upstream of the graph. TeXiFy is an IDE plugin and its issues are written by LaTeX
users describing what they saw — "the autocomplete popup shows the wrong entries" — with no
identifier a keyword or a name match can land on. The seeds are consequently unrelated to the fix:
for three of the eight tasks the top five are `TeXiFyProjectViewNodeDecorator` and
`MendeleyCredentials.ID`. Expansion around a wrong seed is still wrong, and it buys hundreds of
cheap signatures that outbid the seeds for the budget. The median rank of the best gold declaration
is 468, against 12 on detekt.

**A packing fix was tried and rejected.** If cheap neighbours outbid the seeds, giving seeds first
refusal on the budget should bound the engine below by `seeds-only`. It does not: the arm changed
TeXiFy by nothing at 4k and 8k, and cost 13.8 points on detekt and 4.2 on ort at 8k. The loss is in
which declarations the seeding finds, not in which of them the packer keeps, so the change was
dropped rather than kept as a tuning knob.

### A sixth: shadow

Its single task in the suite runs. One task is a coin flip — every arm scores 0% or 100%, and at
4k the shipped default scores 0% while `jp:seed-tests` and `repo-map` score 100%; by 8k the default
finds it too. Reported as coverage, not as a result.

## Results: mined commit messages

detekt, 60 tasks from the last few hundred commits. Recall@budget:

| retriever | 1k | 2k | 4k | 8k |
|-----------|----|----|----|----|
| `jp:all-stubs` | **43.8%** | **50.0%** | **58.8%** | **66.6%** |
| `jp:default` | 41.6% | 49.2% | 55.2% | 60.4% |
| `jp:seed-tests` | 35.8% | 49.3% | 53.4% | 53.8% |
| `seeds-only` | 21.3% | 35.8% | 46.8% | 54.2% |
| `bm25:full.00` | 39.3% | 45.1% | 51.2% | 56.2% |
| `bm25:full.30` | 35.9% | 42.3% | 43.2% | 38.7% |
| `chunk-bm25` | 24.1% | 28.4% | 30.1% | 34.0% |
| `repo-map` | 1.7% | 2.6% | 8.4% | 13.9% |
| `file-dump` | 6.5% | 16.8% | 10.2% | 9.0% |

These tasks are harder than the issue suite in absolute terms — a commit message is often one line
about a repository-wide cleanup — but they agree with it arm for arm.

**The margin holds at every budget, and widens with it.** `jp:all-stubs` is ahead of BM25 over the
same declarations by 4.5 points at 1k and 10.4 at 8k. What does not climb is anything spending the
budget on whole bodies: `bm25:full.30` and `file-dump` *lose* recall as the budget grows, because
the extra lines they buy are not the lines being looked for.

**The retrieval unit matters more than the ranking.** `chunk-bm25` and `bm25:full.00` are the same
BM25 with the same budget and the same tokenizer. One retrieves 40-line windows and the other whole
declarations, and that alone is worth 21 points at 4k. Windows spend the budget on partial
declarations that cannot be credited, and on the halves of neighbours that came along with them.

**Expansion is doing most of the work.** `seeds-only` gets the same seeds and the same number of
candidates, with the graph switched off, and trails by 12 to 22 points. The gap is widest at small
budgets, where being right about the first twenty declarations is all that matters.

**Test names are a trap for keyword ranking.** `jp:seed-tests` differs from the default only in
letting test declarations seed on their own names. That costs 12.8 points at 8k and 8.0 at 1k. Spec
names are English sentences, so they match a commit message better than the code under test does:
the five top seeds for "Don't leak AnalysisApi types" were five variants of one spec name, and
expansion then restarted inside the test suite. Issue text does not punish this nearly as hard,
because its reproduction steps genuinely are test code.

### Where the remaining loss is

At 4k: the gold declaration is somewhere in the ranking for **55 of 60** tasks, and survives the
budget for **38**. Its median rank is 8. So the ranking usually knows where the answer is and the
budget is what loses it — but not by mis-spending it: reordering the knapsack four different ways
(rank order instead of density, a square-root size penalty, guaranteeing the top 20 or 60 by rank a
place) all scored the same or worse.

Reading the individual misses is more useful than the aggregate. They are mostly repository-wide
cleanups — "Remove unchecked casts", "Replace get calls with indexing operators" — whose text names
nothing in particular, and rule-registry edits where the new rule does not exist yet at the base
commit. Test code is no longer a factor: it takes 1.3% of a pack, down from 42% before it was
capped.

### A second repository

Exposed, 17 tasks. It is much easier than detekt — everything that retrieves declarations at all
does well at 4k — but it does separate the arms:

| retriever | 1k | 4k |
|-----------|----|----|
| `jp:default` | 55.9% | **91.2%** |
| `bm25:full.00` | **64.7%** | 82.4% |
| `chunk-bm25` | 11.8% | 22.1% |
| `file-dump` | 2.9% | 8.8% |

At 1k BM25 wins here, which is the honest shape of the result: with room for only a handful of
declarations, matching the words is a good strategy and structure has not paid for itself yet. What
Exposed does establish is that the settings tuned on detekt do not backfire elsewhere — dropping
search fusion was worth 14.7 points here against 1.7 lost on detekt at the same budget — and that
the two collapses, chunks and the repo map, are not a detekt artifact.

### When the task names its target

Splitting the 4k mined run by whether the commit message names a declaration the patch touched:

| retriever | names it (5 tasks) | names none (55 tasks) |
|-----------|-------------------|----------------------|
| `jp:all-stubs` | 94.3% | 55.6% |
| `jp:default` | 94.3% | 51.6% |
| `seeds-only` | 84.7% | 43.3% |
| `bm25:full.00` | 69.7% | 49.6% |
| `chunk-bm25` | 45.3% | 28.8% |

The named slice is five tasks and cannot support a claim on its own. It is here because it is the
case that *should* favour keyword search and does not: even when the task says the name, expanding
around it is worth 20 points over ranking by the words alone.

## Limitations

Read these before quoting any number above.

- **97 of the Kotlin Benchmark's 105 tasks.** detekt, ktlint, ort, dataframe, TeXiFy and shadow all
  run. The remaining 8 are blocked by their build environments rather than by the engine:
  Anki-Android (6) needs an Android SDK, and okhttp (2) needs a GraalVM toolchain its build demands
  by vendor and cannot auto-provision on this machine. Nothing about those 8 is known to favour or
  disfavour the engine.
- **dataframe's graph is thin.** 37.6% of its call sites resolve, against over 95% on detekt, so
  its numbers say less about ranking than the others do. Resolution failures no longer abort an
  index, but a call that does not resolve is still an edge the engine does not have.
- **Exposed predates the source-root fix**, so its table is not directly comparable to the others.
- **ktlint's build model is read at a 2025 commit**, not at HEAD, because the current build needs a
  newer JDK than this harness runs. Every base commit in its 43 tasks is older than that, so the
  model is at worst as stale as it is for any other repository here.
- **The Kotlin Benchmark is used at Level 1 only.** Its Docker environments and test verifiers —
  the part that decides whether a patch actually resolves a task — are ignored here. Nothing in this
  document is a Kotlin Benchmark score.
- **Level 1 only.** Nothing here shows that a better pack produces a better patch. Recall of a
  declaration's *name* is not the same as giving a model what it needs to edit it, which is why the
  shipped default still spends 15% of the budget on whole bodies even though `jp:all-stubs` scores
  0.8 to 7.9 points higher without them. That is a judgement about what an agent needs, not a
  result — the metric says signatures only.
- **The chunk baseline ranks with BM25, not embeddings.** The plan asks for a local embedding
  model. On code of this size the reports it cites put BM25 at or above one, and the gap here is
  large enough that the substitution is unlikely to decide it — but it is a substitution.
- **The repo map is being used as something it is not.** Aider builds a whole-repo overview,
  personalized by the files already in the chat. Scored as a task retriever with no chat files, it
  ranks by global importance instead of task relevance. Its low score is a statement about that
  use, not about Aider.
- **Tuned on detekt.** The seed penalty, the body share and the decision to drop search fusion were
  all chosen against detekt's mined tasks. ktlint, ort, dataframe, TeXiFy and Exposed were never
  tuned on, which is what makes them worth reading — and TeXiFy is what that buys you.
- **The engine needs the task text to name code.** TeXiFy is the counter-example above: where the
  issue describes user-visible behaviour in prose, the seeds are wrong and expansion amplifies
  them. Six repositories is not enough to say where the line falls, only that there is one.
- **One build model stands in for every commit.** The Gradle model is read once at HEAD and its
  classpath reused for every base commit, with source roots taken from the checkout's own layout.
  Both detekt suites now score every task they mine — earlier runs dropped 13 of 60 — but a
  declaration whose type comes from a dependency that has since changed can still resolve
  differently than it did at the time.

## Reproducing

```bash
# Kotlin Benchmark issues. The clone must be full: base commits go back to 2021.
git clone https://github.com/detekt/detekt /tmp/detekt
git clone --depth 1 https://github.com/Kotlin/kotlin-swe-bench /tmp/kotlin-swe-bench
./gradlew :eval:run -Pjetpacker.repo=/tmp/detekt \
  -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=detekt \
  -Pjetpacker.tasks=28 -Pjetpacker.budgets=1000,2000,4000,8000

# ktlint, whose build model has to be read at a commit a JDK 21 toolchain can configure.
git clone https://github.com/pinterest/ktlint /tmp/ktlint
git -C /tmp/ktlint checkout eab1e9dfd37386c417dad06ca9386efb84878c61
./gradlew :eval:run -Pjetpacker.repo=/tmp/ktlint \
  -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=ktlint \
  -Pjetpacker.tasks=43 -Pjetpacker.budgets=1000,2000,4000,8000 \
  -Pjetpacker.cache=$HOME/.jetpacker-ktlint

# The rest of the suite. Each repository needs its own cache directory.
for repo in oss-review-toolkit/ort Kotlin/dataframe Hannah-Sten/TeXiFy-IDEA GradleUp/shadow; do
  name=${repo#*/}
  git clone https://github.com/$repo /tmp/$name
  ./gradlew :eval:run -Pjetpacker.repo=/tmp/$name \
    -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=$name \
    -Pjetpacker.tasks=50 -Pjetpacker.budgets=1000,2000,4000,8000 \
    -Pjetpacker.cache=$HOME/.jetpacker-$name
done

# Mined commit messages from detekt.
./gradlew :eval:run -Pjetpacker.repo=/tmp/detekt -Pjetpacker.tasks=60 \
  -Pjetpacker.budgets=1000,2000,4000,8000
```

Indexes and worktrees are cached under `~/.jetpacker`, keyed by commit and by index schema, so a
change to what the indexer produces cannot be served from a stale file. A commit that has a near
neighbour in the cache is not resolved from scratch: only the files its diff touched are
re-analyzed, which is why the detekt suite costs 4.4 minutes of analysis rather than 18 and
reproduces the same numbers either way.
