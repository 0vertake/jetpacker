# Jetpacker

> Working name. A context packer for AI coding agents, built on compiler-grade
> Kotlin code structure — plus the benchmark that proves (or disproves) it helps.

## What

Given a repository and a task description, Jetpacker builds a **token-budgeted
context pack**: resolved symbol definitions, callers, implementations, type
hierarchies, and affected tests — instead of similar-looking text chunks.

Under the hood: Kotlin Analysis API (PSI-level resolution) → typed code graph →
personalized PageRank from task seeds → density knapsack under a hard token
budget → deterministic Markdown/JSON pack. Delivered as a CLI and an MCP server.

## Why

Vector RAG retrieves text that *looks like* the task. Coding tasks need code
that is *connected to* the task: the callers of the function you're changing,
the implementations of the interface you're touching, the tests that will
break. The hypothesis — backed by recent retrieval literature — is that
compiler-resolved structural packing beats chunk RAG *and* surface-level
(tree-sitter) structural packing on resolve-heavy Kotlin/Java tasks.

**The benchmark is the deliverable.** See [`docs/results.md`](docs/results.md) for
what it currently says and what it does not, and [`docs/plan.md`](docs/plan.md)
for the full research and build plan.

## Status

The pipeline runs end to end and the Level-1 benchmark is wired up. On the 28
detekt tasks of JetBrains' [Kotlin Benchmark](https://github.com/Kotlin/kotlin-swe-bench),
scored from the issue text alone, recall of the declarations each fix changed:

| | 1k | 2k | 4k | 8k |
|---|----|----|----|----|
| Jetpacker | 42.8% | 53.5% | **70.8%** | **81.2%** |
| BM25 over declarations | **48.8%** | **55.7%** | 63.9% | 70.5% |
| Chunk RAG (40-line windows, BM25) | 15.3% | 32.5% | 34.1% | 48.1% |
| Chunk RAG, same windows, embeddings | 3.1% | 10.4% | 14.0% | 14.9% |
| Aider-style repo map | 0.7% | 0.7% | 1.4% | 1.4% |
| Same seeds, no graph expansion | 29.9% | 44.6% | 57.7% | 66.5% |

Retrieving whole declarations instead of windows is worth more than any ranking
change — swapping BM25 for a local `all-MiniLM-L6-v2` over the *same* windows
costs 20 points rather than closing the gap — and structural expansion is worth
10–23 points over the seeds alone. The
same ordering holds on ktlint's 43 tasks and ort's 12 — where the margin is
widest, more than double BM25 at 4k — and on 60 tasks mined from detekt's commit
history. Removing one relation at a time says the callers of a declaration are
what earns that — worth up to 13.6 points — while implementations and supertypes,
which the design expected to be the wedge, pay in one column out of six.

**Resolution itself is worth 1 to 17.8 points**, and the margin grows with the
budget. The same engine over call edges rebuilt from bare names — what a parser
can produce without a compiler — loses in all twelve repository-and-budget
columns, and on detekt at 8k it does worse than not expanding the graph at all.
Following an ambiguous edge is not a weaker version of following a resolved one.

It does not hold everywhere: on TeXiFy, an IDE plugin whose issues describe what
a LaTeX user saw rather than any code, the engine loses to BM25 at every budget
and to its own no-expansion ablation. Read
[`docs/results.md`](docs/results.md) before quoting any of this: 97 of the
suite's 105 tasks run, keyword search still wins below 2k on the smaller
repositories, and Level 2 (does a better pack produce a better patch?) is not
measured yet.

## Layout

| Module | Purpose |
|---|---|
| `core/` | index, seed, expand, rank, pack, render |
| `cli/` | `packer pack`, and `packer serve` for the MCP surface |
| `baselines/` | chunk-RAG (BM25 and embedding) / BM25 / tree-sitter baselines |
| `eval/` | benchmark harness and metrics |

## Build

Requires JDK 21.

```sh
./gradlew build
```

## Use

```sh
./gradlew :cli:installDist
cli/build/install/cli/bin/cli pack --repo /path/to/project --task task.md --budget 4000
```

Or as an MCP server over stdio, which indexes the repository once at startup and
then answers `get_context_pack(task, budget)` per request:

```json
{
  "mcpServers": {
    "jetpacker": {
      "command": "/path/to/jetpacker/cli/build/install/cli/bin/cli",
      "args": ["serve", "--repo", "/path/to/project"]
    }
  }
}
```
