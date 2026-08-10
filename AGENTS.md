# AGENTS.md

## What this is

Jetpacker (working name): a Kotlin engine that builds **token-budgeted context packs**
for AI coding agents from **compiler-grade code structure** (Kotlin Analysis API / PSI),
validated by a published benchmark against chunk-RAG and tree-sitter baselines.

**The eval is the product.** The pack engine is how we get the numbers.
Read `docs/plan.md` before any non-trivial work — it holds the full architecture,
benchmark design, milestones, and kill-tests.

## Stack

- Kotlin 2.4.x on JVM 21, Gradle (Kotlin DSL), version catalog in `gradle/libs.versions.toml`
- Semantic resolution: Kotlin Analysis API Standalone (`analysis-api-standalone-for-ide`
  from the JetBrains `intellij-dependencies` Maven repo). Experimental API; its releases
  lag Kotlin versions.
- Storage: SQLite (sqlite-jdbc) + FTS5. Token counting: jtokkit.
- Eval tasks: Kotlin-SWE-bench (JetBrains' Kotlin Benchmark, Harbor task format).

## Modules

- `core/` — indexer, seed finder, graph expander, ranker, packer, renderer
- `cli/` — `packer pack` command-line interface
- `baselines/` — chunk-RAG, BM25, tree-sitter/Aider-style packs for the benchmark
- `eval/` — benchmark harness, gold-symbol extraction from patches, metrics

## Commands

- Build + test everything: `./gradlew build`
- Tests only: `./gradlew test`
- One module: `./gradlew :core:test`

## Conventions

- **Determinism is a hard requirement:** same repo state + task ⇒ byte-identical pack.
  Stable sort keys everywhere; never iterate unordered collections into output.
- Every packed item carries a `why` provenance string (`seed | caller-of:X | impl-of:Y | test-of:Z`).
- Retrieval units are whole declarations, never character windows.
- Analysis API usage stays behind an interface in `core` (experimental API;
  IntelliJ-headless is the fallback host if standalone resolution fails).
- Tests are fixture-based: resolve/pack against small fixture projects in
  `src/test/fixtures`, assert against golden files.

## Boundaries

- Never commit or open PRs unless explicitly asked.
- Never bump the Kotlin or Analysis API versions independently — they must stay
  compatible, and upgrades break resolution. Treat version bumps as their own task.
- Never use tree-sitter in `core` — it belongs in `baselines/` only. If PSI resolution
  fails, the fallback is IntelliJ-headless, not tree-sitter (see plan §7 Phase 0).
- Baselines run in the same harness with the same model and budget as the main engine;
  never special-case them.
- Never claim results beyond what the eval table shows (see plan §2 "What NOT to claim").
