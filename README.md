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

**The benchmark is the deliverable.** Results will be published on
[Kotlin-SWE-bench](https://github.com/Kotlin/kotlin-swe-bench) tasks against
in-repo baselines (chunk RAG, BM25, tree-sitter/Aider-style pack), with
ablations. See [`docs/plan.md`](docs/plan.md) for the full research and build plan.

## Status

Pre-alpha. Repo scaffolding only — Phase 0 (Analysis API feasibility spike) is next.

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
