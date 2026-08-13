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

Two further metrics from plan §5 are reported by the harness and discussed
[below](#do-the-other-metrics-say-anything-different): **+callers**, which gives the direct callers
of gold half credit, and **nDCG**, which scores where in the pack the gold landed rather than only
whether it is there.

## Retrievers

All of them use the same packer, the same tokenizer, the same budget, and the same task text. The
only thing that varies is which declarations get chosen and in what order.

| name | what it is |
|------|-----------|
| `jp:default` | the shipped engine: 15% of the budget on whole bodies, the rest on signatures |
| `jp:all-stubs` | the same, spending nothing on bodies |
| `jp:seed-tests` | ablation: test declarations allowed to seed on their names |
| `seeds-only` | ablation: structural expansion switched off, same seed ranking |
| `names-only` | ablation: resolution off — call edges rebuilt by matching bare names |
| `jp:-calls` … `jp:-testcode` | ablations: one relation removed at a time, tabled [below](#which-relation-earns-the-win) |
| `bm25:full.00` | BM25 over whole declarations, signatures only |
| `bm25:full.30` | BM25 over whole declarations, 30% of the budget on bodies |
| `chunk-bm25` | chunk RAG: fixed 40-line windows ranked by BM25 |
| `chunk-embed` | the same windows ranked by `all-MiniLM-L6-v2` embeddings, run locally |
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
| `chunk-bm25` | 15.3% | 32.5% | 34.1% | 48.1% |
| `chunk-embed` | 3.1% | 10.4% | 14.0% | 14.9% |
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

**Embedding the same windows makes them worse, not better.** `chunk-embed` and `chunk-bm25` differ
in one thing — cosine similarity against a sentence-transformer instead of keyword scoring — and
the dense ranking costs 20.1 points at 4k. It is not that the model cannot see the right code: on
task 4205 the gold file's best window ranks 42nd of 2275, inside the top 2%, and 4k holds about ten
windows. A general-purpose embedding spreads its confidence across everything topically alike, and
in a linter every rule and every rule spec is topically alike, while BM25 keys on the rare
identifiers an issue quotes verbatim. This is the arm that answers "your chunk baseline lost
because BM25 is not real RAG"; on this corpus, real RAG does worse.

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
declaration, against 96% on detekt at a recent commit: it is generated-heavy,
compiler-plugin-driven code, and the graph the engine ranks over is correspondingly thin. The gap
over BM25 at 8k is what expansion manages on a third of the edges.

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

## Do the other metrics say anything different?

Recall@budget is indifferent to two things a reader might care about: whether the pack also holds
the code that *calls* what needs changing, and whereabouts in the pack any of it landed. Plan §5
asks for both, so the harness reports **+callers** (gold at full weight, its direct callers at half)
and **nDCG** over the pack's own order. At 4k:

| retriever | detekt recall | detekt +callers | detekt nDCG | ktlint recall | ktlint +callers | ktlint nDCG |
|-----------|---------------|-----------------|-------------|---------------|-----------------|-------------|
| `jp:all-stubs` | 78.7% | 79.4% | **0.342** | **41.9%** | **39.5%** | **0.152** |
| `jp:default` | 70.8% | 72.2% | 0.313 | 38.5% | 35.9% | 0.139 |
| `bm25:full.00` | 63.9% | 64.7% | 0.294 | 31.5% | 28.8% | 0.146 |
| `names-only` | 59.7% | 58.8% | 0.240 | 34.8% | 32.9% | 0.122 |
| `seeds-only` | 57.7% | 58.6% | 0.232 | 25.4% | 23.7% | 0.113 |
| `chunk-bm25` | 34.1% | 34.0% | 0.176 | 3.4% | 3.1% | 0.034 |
| `chunk-embed` | 14.0% | 12.5% | 0.081 | — | — | — |

**Half credit for callers changes nothing.** Every arm moves by a point or two and no ordering
changes. It does show a small difference between the repositories: on detekt the packs tend to hold
callers of gold, so +callers sits *above* recall, and on ktlint they do not. Neither effect is large
enough to build on.

**nDCG is where the engine looks worst, and that is worth saying.** On ktlint `jp:default` beats
BM25 on recall by 7 points and *loses* to it on nDCG, 0.139 against 0.146. BM25 ranks by direct
text match, so on the tasks where it finds gold at all it tends to put it near the top of the pack;
the engine finds gold on more tasks but places it deeper, behind whatever the graph ranked above it.
Recall says the engine is 22% better here; a model that reads only the first part of its context
would not experience it that way.

That is a packer-ordering problem rather than a retrieval one — the pack renders bodies first, then
signatures grouped by file, so a gold signature can land far down a long section — and it is a
concrete thing to fix that the previous metrics could not see. It is left open rather than tuned
away, because whether pack position matters at all is a Level-2 question.

## Is resolution worth it?

This is the ablation the project exists for (docs/plan.md §5), and it had been missing: `seeds-only`
asks whether expanding the graph pays, not whether *resolved* edges beat the name-matched ones a
parser produces without a compiler.

`names-only` is the same engine with its call edges rebuilt from bare names — `format(x)` links to
every `format` in the repository. Same declarations, same seeds, same ranker, same packer, same
budget; the only difference is whether an edge knows which declaration was meant.

| repository | 1k | 2k | 4k | 8k |
|------------|----|----|----|----|
| detekt: resolved | **42.8** | **53.5** | **70.8** | **81.2** |
| detekt: names only | 41.3 | 50.1 | 59.7 | 63.4 |
| ktlint: resolved | **16.3** | **28.1** | **38.5** | **51.4** |
| ktlint: names only | 15.3 | 25.6 | 34.8 | 46.3 |
| ort: resolved | **28.0** | **37.4** | **51.8** | **59.6** |
| ort: names only | 23.7 | 33.7 | 42.9 | 51.2 |

**Resolution wins in all twelve columns, by 1.0 to 17.8 points, and the margin grows with the
budget.** At 1k it is worth 1 to 4 points — with room for a few dozen signatures, the first handful
of seeds dominate and edge quality barely matters. By 8k it is worth 5.1 on ktlint, 8.4 on ort and
17.8 on detekt, because a larger budget means following more edges and a name-matched edge is wrong
more often the further you walk it.

**On detekt, name-matched expansion is worse than no expansion at all**: 63.4% against
`seeds-only`'s 66.5% at 8k. Expanding along ambiguous edges is not a weaker version of expanding
along resolved ones, it is actively harmful — it spends the budget on the wrong `visit`. On ktlint
and ort surface structure still beats no structure, so this is a property of the repository, not a
universal law.

Three things make `names-only` stronger than a real tree-sitter pack, all deliberate: it takes its
declaration list from the PSI index, so its nodes are exactly right; only calls are degraded, while
containment and supertypes stay resolved; and a name declared in more than 20 places is dropped
rather than linked to all of them, which spares it the worst of what ambiguity does. The 1.0-to-17.8
range is therefore a floor on what resolution buys, not a ceiling.

## Which relation earns the win

`seeds-only` says expansion pays. These say what it is paying for: one relation removed at a time
from `jp:default`, on the three repositories with a sample worth reading. Each cell is
recall@budget and its change against the default.

| removed | detekt 4k | detekt 8k | ktlint 4k | ktlint 8k | ort 4k | ort 8k |
|---------|-----------|-----------|-----------|-----------|--------|--------|
| nothing (`jp:default`) | 70.8 | 81.2 | 38.5 | 51.4 | 51.8 | 59.6 |
| callers of a declaration | 71.9 (+1.1) | 81.2 (0.0) | 24.9 (**−13.6**) | 45.1 (−6.3) | 43.8 (−8.0) | 50.5 (−9.1) |
| the call relation entirely | 75.7 (+4.9) | 82.1 (+0.9) | 35.3 (−3.2) | 48.5 (−2.9) | 47.2 (−4.6) | 51.0 (−8.6) |
| implementations and supertypes | 78.9 (+8.1) | 74.0 (−7.2) | 39.6 (+1.1) | 53.4 (+2.0) | 53.0 (+1.2) | 61.2 (+1.6) |
| containment (class ↔ member) | 64.4 (−6.4) | 75.4 (−5.8) | 32.2 (−6.3) | 49.5 (−1.9) | 51.8 (0.0) | 61.0 (+1.4) |
| co-location (same file) | 68.4 (−2.4) | 76.2 (−5.0) | 34.5 (−4.0) | 49.6 (−1.8) | 49.0 (−2.8) | 48.6 (**−11.0**) |
| test code, refused entirely | 72.6 (+1.8) | 77.6 (−3.6) | 38.5 (0.0) | 51.4 (0.0) | 51.8 (0.0) | 59.6 (0.0) |

One task is worth about 2.3 points on ktlint's 43, 3.6 on detekt's 28 and 8.3 on ort's 12, so read
anything under about 3 points as noise on the first two and under 8 on ort.

**Callers are the relation that pays.** Removing the edge from a declaration to the code that calls
it costs 13.6 points on ktlint at 4k and 6 to 9 on ort at both budgets — the largest single loss in
the table. This is also the relation that most needs resolution: finding the callers of an
overloaded or extension function is exactly where surface-name matching gets it wrong, and it is the
part of the thesis the ablation supports most directly.

**Implementations do not pay, which the plan did not predict.** Removing `extends` and `overrides`
in both directions is neutral or a small gain on five of the six columns; only detekt at 8k loses
by 7.2. The design argued that given an interface method, its implementations are usually the code
that needs changing — the Spring-style dependency-injection case. On these three repositories that
is not where the fixes are, and the edge is carrying its weight in one column out of six. It stays
on because one clear loss on detekt is a reason for caution, not because the evidence favours it.

**Co-location is worth keeping.** The file-node star is the one relation invented here rather than
taken from the code graph, and removing it costs points in five of six columns, including 11 on ort
at 8k. It was added because the benchmark kept finding the right *file* and the wrong declaration
inside it, and it is still doing that job.

**The test cap already did the work.** Refusing test code entirely changes nothing at all on ktlint
and ort, and moves detekt by less than the noise floor. Once tests are capped at 10% of the budget,
whether they are there at all is not what decides a run.

**detekt disagrees about calls, and that is the honest shape of it.** Removing the call relation
*gains* 4.9 points there at 4k while costing 3 to 9 on ktlint and ort. detekt's tasks are rule
implementations whose fix lives in one class with its members — which is why containment is what it
loses most from — while ort's are spread across a much larger codebase where reaching the right
neighbourhood requires following calls. No single relation is load-bearing everywhere.

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
- **dataframe's graph is thin.** 37.6% of its call sites resolve, against 96% on recent detekt, so
  its numbers say less about ranking than the others do. Resolution failures no longer abort an
  index, but a call that does not resolve is still an edge the engine does not have.
- **Exposed predates the source-root fix**, so its table is not directly comparable to the others.
- **ktlint's build model is read at a 2025 commit**, not at HEAD, because the current build needs a
  newer JDK than this harness runs. Every base commit in its 43 tasks is older than that, so the
  model is at worst as stale as it is for any other repository here.
- **The Kotlin Benchmark is used at Level 1 only.** Its Docker environments and test verifiers —
  the part that decides whether a patch actually resolves a task — are ignored here. Nothing in this
  document is a Kotlin Benchmark score.
- **nDCG is computed over the pack, not over a ranking.** Baselines do not all expose an internal
  ranking, so position is taken from the rendered order every arm actually hands a model. That
  makes it a property of packing as much as of retrieval.
- **Level 1 only.** Nothing here shows that a better pack produces a better patch. Recall of a
  declaration's *name* is not the same as giving a model what it needs to edit it, which is why the
  shipped default still spends 15% of the budget on whole bodies even though `jp:all-stubs` scores
  0.8 to 7.9 points higher without them. That is a judgement about what an agent needs, not a
  result — the metric says signatures only.
- **The embedding baseline has run on detekt only.** `chunk-embed` costs about half an hour a
  repository, so the other five tables have no dense arm yet. One repository is enough to answer
  the objection and not enough to generalise: a corpus where topical similarity is more
  discriminating than a linter's is exactly where the result could go the other way.
- **The embedding baseline uses one general-purpose model.** `all-MiniLM-L6-v2` is what most RAG
  stacks reach for, not what a team optimising for code retrieval would pick. A code-trained
  embedding is the obvious next arm, and the honest reading of `chunk-embed` today is "the default
  choice does badly here", not "dense retrieval does badly here".
- **Chunk arms rank on the window plus its file path**, which is what chunking pipelines index and
  what the engine already had through `fqName`. It is worth 7.6 points to `chunk-bm25` at 4k on
  detekt. The tables for the other five repositories, and both mined-commit tables, were measured
  before that change and understate their chunk arm; they are re-run as a block, not patched.
- **The repo map is being used as something it is not.** Aider builds a whole-repo overview,
  personalized by the files already in the chat. Scored as a task retriever with no chat files, it
  ranks by global importance instead of task relevance. Its low score is a statement about that
  use, not about Aider — which is why `names-only`, and not the repo map, is the arm that carries
  the surface-versus-resolved comparison.
- **No tree-sitter is involved in measuring resolution.** `names-only` degrades the resolved index
  rather than parsing the repository again, so it isolates resolution from parser technology. A real
  tree-sitter pack would also have an approximate declaration list, which this arm does not: it is
  the stronger opponent, not the faithful one.
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
  differently than it did at the time. **That cost is measurable and it falls on the oldest tasks:**
  across detekt's cached indexes, whole-repository resolution runs 96–97% at 2023–2025 commits and
  76% at the oldest one, October 2021. The graph the ranker sees is thinner the further a task is
  from HEAD, so detekt's older tasks are handicapped rather than flattered by this shortcut.
- **Two kinds of dependency are invisible to the build model.** The IDEA model is read without
  running tasks, and it enumerates the standard configurations only — asked module by module on
  detekt it reports 1,272 dependency entries in every scope, which dedupe to the 64 jars the
  indexer gets. What it cannot report is a dependency declared in a *custom* configuration, or a
  class that exists only as a *task output*. detekt does both: its Analysis API packaging modules
  declare that dependency in an `aaDependency` configuration and republish it as a shadow jar, so
  the Analysis API types 103 of its files import are missing from the classpath and calls into them
  are counted unresolved. Those files postdate every base commit in the task set, so the cost here
  is near zero — and a missing jar can only remove edges, never invent them.

## Reproducing

```bash
# Kotlin Benchmark issues. The clone must be full: base commits go back to 2021.
git clone https://github.com/detekt/detekt /tmp/detekt
git clone --depth 1 https://github.com/Kotlin/kotlin-swe-bench /tmp/kotlin-swe-bench
./gradlew :eval:run -Pjetpacker.repo=/tmp/detekt \
  -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=detekt \
  -Pjetpacker.tasks=28 -Pjetpacker.budgets=1000,2000,4000,8000 \
  -Pjetpacker.embed=true   # adds chunk-embed; downloads a 90MB model, roughly doubles the run

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
