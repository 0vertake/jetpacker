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
| `jp:default` | the shipped engine: fused ranking, 15% of the budget on bodies |
| `jp:all-stubs` | the same, spending nothing on bodies |
| `jp:graph-only` | seeds → PageRank, without the keyword ranking fused in |
| `seeds-only` | the headline ablation: structural expansion switched off entirely |
| `bm25:full.00` | BM25 over whole declarations, signatures only |
| `bm25:full.30` | BM25 over whole declarations, 30% of the budget on bodies |
| `chunk-bm25` | chunk RAG: fixed 40-line windows ranked by BM25 |
| `repo-map` | Aider's repo map, ported from `repomap.py` |
| `file-dump` | whole top-ranked files until the budget runs out |

## Results

detekt, 47 tasks (60 mined, 13 without a resolvable gold declaration). Recall@budget:

| retriever | 1k | 2k | 4k | 8k |
|-----------|----|----|----|----|
| `jp:all-stubs` | **50.9%** | 57.4% | **70.4%** | **72.8%** |
| `jp:default` | 50.6% | 55.3% | 67.6% | 69.5% |
| `jp:graph-only` | 43.7% | **58.3%** | 62.7% | 64.4% |
| `seeds-only` | 44.4% | 50.2% | 57.8% | 55.7% |
| `bm25:full.00` | 46.0% | 56.2% | 60.0% | 60.4% |
| `bm25:full.30` | 40.6% | 51.1% | 48.0% | 44.8% |
| `chunk-bm25` | 20.1% | 23.5% | 28.4% | 32.7% |
| `repo-map` | 3.2% | 3.4% | 13.0% | 19.0% |
| `file-dump` | 6.6% | 20.2% | 15.9% | 8.0% |

Four things in that table are worth more than the headline.

**Keyword search stops paying for context; structure does not.** BM25 gains four points between
2k and 8k and then stops at 60%: past the first few hundred candidates its ranking has nothing
left to say. The engine keeps converting budget into recall through 8k. The gap is 3 points at 1k
and 12 points at 8k, and it is widening, not shrinking.

**The retrieval unit matters more than the ranking.** `chunk-bm25` and `bm25:full.00` are the same
BM25 with the same budget and the same tokenizer. One retrieves 40-line windows and the other
whole declarations, and that alone is worth 32 points at 4k. Windows spend the budget on partial
declarations that cannot be credited and on the halves of neighbours that came along with them.

**Structure earns its keep only above 2k.** At 1k a pack holds so few declarations that being
right about the first twenty is all that matters, and keyword ranking is nearly as good at that.
`jp:graph-only` even wins at 2k. The two rankings fused are the best or within a point everywhere,
which is why fusion ships rather than either alone.

**Expansion is doing the work, not the seeds.** `seeds-only` — the same seeds, structural
expansion switched off — trails the full engine by 13 points at 4k and 17 at 8k, and it *loses*
recall going from 4k to 8k, because with nowhere to expand to it spends the extra budget on
lower-ranked keyword matches.

### Where the remaining loss is

At 4k, over the same 47 tasks: the gold declaration is somewhere in the ranking for **47 of 47**
tasks, and survives the budget for **36**. Its median rank is 33. So the ranking already knows
where the answer is; roughly a quarter of the loss is the packer choosing to spend the budget
elsewhere. Test code is no longer the culprit — it takes 4.8% of a pack, down from 42% before it
was capped.

### A second repository

Exposed, 17 tasks, 4k budget. Everything that retrieves declarations at all scores in the high
eighties, so the suite cannot separate them:

| retriever | recall@4k |
|-----------|-----------|
| `jp:default` | 89.2% |
| `seeds-only` | 87.7% |
| `bm25:full.00` | 83.3% |
| `repo-map` | 20.6% |
| `chunk-bm25` | 22.1% |

The engine is ahead and the ablation is ordered the same way, but a 6-point margin on 17 saturated
tasks is not evidence of anything. What Exposed does establish is that the settings tuned on detekt
do not backfire elsewhere, and that the two collapses — chunks and the repo map — are not a detekt
artifact.

### When the task names its target

Splitting the 4k run by whether the commit message names a declaration the patch touched:

| retriever | names it (5 tasks) | names none (42 tasks) |
|-----------|-------------------|----------------------|
| `jp:all-stubs` | 88.7% | 68.3% |
| `jp:default` | 80.7% | 66.0% |
| `bm25:full.00` | 64.7% | 59.4% |
| `chunk-bm25` | 45.3% | 26.4% |

The named slice is five tasks and cannot support a claim on its own. It is here because it is the
case that *should* favour keyword search, and does not: even when the task says the name, finding
the declaration around it is worth 24 points over BM25.

## Limitations

Read these before quoting any number above.

- **One repository decides everything.** detekt is the only suite here that discriminates between
  retrievers at all. On Exposed everything that retrieves declarations scores 83–89%, and the comparison says nothing.
- **Commit messages are not issue reports.** A mined commit message often describes the fix rather
  than the symptom, which flatters every keyword-based method, including ours. Kotlin-SWE-bench
  issue text is the suite the plan calls for and is not wired up yet.
- **Level 1 only.** Nothing here shows that a better pack produces a better patch. Recall of a
  declaration's *name* is not the same as giving a model what it needs to edit it, which is why
  the shipped default still spends part of the budget on bodies that Level-1 recall does not
  reward.
- **The chunk baseline ranks with BM25, not embeddings.** The plan asks for a local embedding
  model. On code of this size the reports it cites put BM25 at or above one, and the gap here is
  large enough that the substitution is unlikely to decide it — but it is a substitution.
- **The repo map is being used as something it is not.** Aider builds a whole-repo overview,
  personalized by the files already in the chat. Scored as a task retriever with no chat files, it
  ranks by global importance instead of task relevance. Its low score is a statement about that
  use, not about Aider.
- **Tuned on detekt.** The RRF constant and the body share were both chosen against these tasks.
  They were re-checked on Exposed, which is saturated and could not have contradicted them.
- **The task set is not perfectly stable.** Reading a historical commit's build model through the
  Gradle Tooling API occasionally fails, and a task that cannot be indexed is skipped. Two runs of
  the same command scored 46 and 47 of the same 60 tasks. Numbers here are one run, not a mean.

## Reproducing

```bash
git clone https://github.com/detekt/detekt /tmp/detekt
./gradlew :eval:run -Pjetpacker.repo=/tmp/detekt -Pjetpacker.tasks=60 \
  -Pjetpacker.budgets=1000,2000,4000,8000
```

Indexes and worktrees are cached under `~/.jetpacker`, keyed by commit. The first run costs a few
minutes per commit; later runs reuse both.
