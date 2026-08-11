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
| Chunk RAG (40-line windows) | 11.4% | 22.4% | 26.5% | 37.3% |
| Aider-style repo map | 0.7% | 0.7% | 1.4% | 1.4% |
| Same seeds, no graph expansion | 29.9% | 44.6% | 57.7% | 66.5% |

Retrieving whole declarations instead of windows is worth more than any ranking
change, and structural expansion is worth 10–23 points over the seeds alone. The
same ordering holds on ktlint's 43 tasks and ort's 12 — where the margin is
widest, more than double BM25 at 4k — and on 60 tasks mined from detekt's commit
history. Read [`docs/results.md`](docs/results.md) before quoting any of this: 83
of the suite's 105 tasks run, keyword search still wins below 2k on the smaller
repositories, and Level 2 (does a better pack produce a better patch?) is not
measured yet.

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
