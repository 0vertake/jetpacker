# Level-1 results

Retrieval quality only: no model, no API spend (docs/plan.md §5). Every number here comes from
`:eval:run` and can be reproduced with the command at the bottom.

## What is measured

A task is one commit mined from a repository's history. Its **text** is the commit subject and
body — what someone wrote about the change before making it. Its **gold** is the set of
declarations the patch touched, taken from the index of the *parent* commit, so nothing in the
gold set depends on the fix existing yet.

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

## Results

detekt, 47 tasks (60 mined, 13 without a resolvable gold declaration). Recall@budget:

| retriever | 1k | 2k | 4k | 8k |
|-----------|----|----|----|----|
| `jp:all-stubs` | **54.0%** | **60.1%** | **70.4%** | **79.1%** |
| `jp:default` | 51.2% | 59.1% | 66.8% | 73.4% |
| `jp:seed-tests` | 43.9% | 59.7% | 63.6% | 64.8% |
| `seeds-only` | 23.9% | 42.7% | 56.4% | 61.3% |
| `bm25:full.00` | 46.5% | 54.2% | 61.7% | 67.2% |
| `bm25:full.30` | 44.6% | 50.4% | 50.8% | 46.0% |
| `chunk-bm25` | 20.1% | 23.5% | 28.4% | 32.7% |
| `repo-map` | 3.2% | 3.2% | 11.8% | 19.0% |
| `file-dump` | 6.6% | 19.6% | 14.8% | 8.2% |

Four things in that table are worth more than the headline.

**The margin holds at every budget, and widens slowly.** The engine is ahead of BM25 over the same
declarations by 4.7 points at 1k and 6.2 at 8k; `jp:all-stubs` by 7.5 and 11.9. Both climb with the
budget. What does not is anything that spends the budget on whole bodies: `bm25:full.30` and
`file-dump` *lose* recall as the budget grows, because the extra lines they buy are not the lines
being looked for.

**The retrieval unit matters more than the ranking.** `chunk-bm25` and `bm25:full.00` are the same
BM25 with the same budget and the same tokenizer. One retrieves 40-line windows and the other whole
declarations, and that alone is worth 33 points at 4k. Windows spend the budget on partial
declarations that cannot be credited, and on the halves of neighbours that came along with them.

**Expansion is doing most of the work.** `seeds-only` gets the same seeds and the same number of
candidates, with the graph switched off, and trails by 10 to 27 points. The gap is widest at small
budgets, where being right about the first twenty declarations is all that matters.

**Test names are a trap for keyword ranking.** `jp:seed-tests` differs from the default only in
letting test declarations seed on their own names. That costs 8.6 points at 8k, 7.3 at 1k and 3.2
at 4k, and gains 0.6 at 2k. Spec-style names
are English sentences, so they match a task description better than the code under test does. The
five top seeds for "Don't leak AnalysisApi types" were five variants of one spec name, and
expansion then restarted inside the test suite.

### Where the remaining loss is

At 4k, over the same 47 tasks: the gold declaration is somewhere in the ranking for **47 of 47**
tasks, and survives the budget for **35**. Its median rank is 8. So the ranking usually knows where
the answer is and the budget is what loses it — but not by mis-spending it: reordering the knapsack
four different ways (rank order instead of density, a square-root size penalty, guaranteeing the
top 20 or 60 by rank a place) all scored the same or worse.

Reading the individual misses is more useful than the aggregate. They are mostly repository-wide
cleanups — "Remove unchecked casts", "Replace get calls with indexing operators" — whose text names
nothing in particular, and rule-registry edits where the new rule does not exist yet at the base
commit. Test code is no longer a factor: it takes 1.6% of a pack, down from 42% before it was
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

Splitting the 4k run by whether the commit message names a declaration the patch touched:

| retriever | names it (5 tasks) | names none (42 tasks) |
|-----------|-------------------|----------------------|
| `jp:all-stubs` | 94.3% | 67.6% |
| `jp:default` | 94.3% | 63.5% |
| `seeds-only` | 84.7% | 53.0% |
| `bm25:full.00` | 74.7% | 60.1% |
| `chunk-bm25` | 45.3% | 26.4% |

The named slice is five tasks and cannot support a claim on its own. It is here because it is the
case that *should* favour keyword search and does not: even when the task says the name, expanding
around it is worth 20 points over ranking by the words alone.

## Limitations

Read these before quoting any number above.

- **Two repositories, one of them easy.** detekt carries the result. Exposed agrees at 4k and
  disagrees at 1k, where BM25 beats the engine.
- **Commit messages are not issue reports.** A mined commit message often describes the fix rather
  than the symptom, which flatters every keyword-based method, including ours. Kotlin-SWE-bench
  issue text is the suite the plan calls for and is not wired up yet.
- **Level 1 only.** Nothing here shows that a better pack produces a better patch. Recall of a
  declaration's *name* is not the same as giving a model what it needs to edit it, which is why the
  shipped default still spends 15% of the budget on whole bodies even though `jp:all-stubs` scores
  1.0 to 5.7 points higher without them. That is a judgement about what an agent needs, not a
  result — the metric says signatures only.
- **The chunk baseline ranks with BM25, not embeddings.** The plan asks for a local embedding
  model. On code of this size the reports it cites put BM25 at or above one, and the gap here is
  large enough that the substitution is unlikely to decide it — but it is a substitution.
- **The repo map is being used as something it is not.** Aider builds a whole-repo overview,
  personalized by the files already in the chat. Scored as a task retriever with no chat files, it
  ranks by global importance instead of task relevance. Its low score is a statement about that
  use, not about Aider.
- **Tuned on detekt.** The seed penalty, the body share and the decision to drop search fusion were
  all chosen against these tasks, then re-checked on Exposed. Two repositories is not a suite.
- **The task set is not perfectly stable.** Reading a historical commit's build model through the
  Gradle Tooling API occasionally fails, and a task that cannot be indexed is skipped. Two runs of
  the same command scored 46 and 47 of the same 60 tasks. Numbers here are one run, not a mean.

## Reproducing

```bash
git clone https://github.com/detekt/detekt /tmp/detekt
./gradlew :eval:run -Pjetpacker.repo=/tmp/detekt -Pjetpacker.tasks=60 \
  -Pjetpacker.budgets=1000,2000,4000,8000
```

Indexes and worktrees are cached under `~/.jetpacker`, keyed by commit and by index schema, so a
change to what the indexer produces cannot be served from a stale file. The first run costs a few
minutes per commit; later runs reuse both.
