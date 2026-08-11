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

The pipeline runs end to end and the Level-1 benchmark is wired up. On 47 tasks
mined from detekt's history, recall of the declarations each commit changed:

| | 1k | 2k | 4k | 8k |
|---|----|----|----|----|
| Jetpacker | **51.2%** | **59.1%** | **66.8%** | **73.4%** |
| BM25 over declarations | 46.5% | 54.2% | 61.7% | 67.2% |
| Chunk RAG (40-line windows) | 20.1% | 23.5% | 28.4% | 32.7% |
| Aider-style repo map | 3.2% | 3.2% | 11.8% | 19.0% |
| Same seeds, no graph expansion | 23.9% | 42.7% | 56.4% | 61.3% |

Retrieving whole declarations instead of windows is worth more than any ranking
change, and structural expansion is worth 10–27 points over the seeds alone.
Read [`docs/results.md`](docs/results.md) before quoting any of this: two
repositories carry the whole result, one of them disagrees at 1k, and Level 2
(does a better pack produce a better patch?) is not measured yet.

## Layout

| Module | Purpose |
|---|---|
| `core/` | index, seed, expand, rank, pack, render |
| `cli/` | `packer pack --repo . --task task.md --budget 4000` |
| `baselines/` | chunk-RAG / BM25 / tree-sitter baselines |
| `eval/` | benchmark harness and metrics |

## Build

Requires JDK 21.

```sh
./gradlew build
```
