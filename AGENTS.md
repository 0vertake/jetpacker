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
- Semantic resolution: Kotlin Analysis API Standalone (`analysis-api-standalone-for-ide`).
  Experimental API; its releases lag Kotlin versions.
- Storage: SQLite (sqlite-jdbc) + FTS5. Token counting: jtokkit.
- Eval tasks: Kotlin-SWE-bench (JetBrains' Kotlin Benchmark, Harbor task format).

## Modules

- `core/` — indexer, seed finder, graph expander, ranker, packer, renderer
- `cli/` — `packer pack` command-line interface, and `packer serve`, the MCP server
- `baselines/` — chunk-RAG, BM25, tree-sitter/Aider-style packs for the benchmark
- `eval/` — benchmark harness, gold-symbol extraction from patches, metrics

## Commands

- Build + test everything: `./gradlew build`
- Tests only: `./gradlew test`
- One module: `./gradlew :core:test`
- Resolve a real repository (opt-in, needs network, minutes):
 `./gradlew :core:test -Djetpacker.repo=/path/to/repo --tests '*RealRepository*'`
- Run the embedding baseline (opt-in, downloads a 90MB model): add `-Pjetpacker.embed=true`
 to `:eval:run`, or `-Djetpacker.embed=true` to `:baselines:test`. Off by default because it
 roughly doubles a benchmark run.
- Certify Level-2 tasks (needs Docker, ~18 min and ~6GB of image per task):
 `./gradlew :eval:certify -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=detekt`
 Base images must exist first: `scripts/build_bases.sh` in the benchmark repo, or one
 `docker build -f bases/<repo>/Dockerfile.base -t kotlin-bench/<repo>:base bases/<repo>`.
- Run Level 2 on the certified tasks (needs Docker and `CURSOR_API_KEY`, one model call and one
 container per arm per task): `./gradlew :eval:level2 -Pjetpacker.repo=/tmp/detekt
 -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=detekt`.
 `-Djetpacker.patcher=<script.py>` swaps the model backend for another one, or for a stub, which
 is how to exercise the loop without spending calls. Both of these outlive a shell — run them
 under `screen`, not `nohup`, and never concurrently with a Gradle build of this repo.

## Analysis API dependency wiring (hard-won; don't "simplify" it)

The Analysis API is not on Maven Central ([KT-56203](https://youtrack.jetbrains.com/issue/KT-56203)),
and its dependency set is not self-describing:

- `org.jetbrains.kotlin:*-for-ide` come from `packages.jetbrains.team/.../intellij-dependencies`;
  `com.jetbrains.intellij.platform:*` and `.java:*` come from `intellij-repository/releases`.
  Two different repos — a missing one shows up as unresolved `com.jetbrains.intellij.*`.
- Those artifacts are declared `isTransitive = false` on purpose: their poms reference
  unpublished internal coordinates. Every runtime dep is therefore listed by hand, mirroring
  `google/ksp`'s `kotlin-analysis-api/build.gradle.kts` (the canonical production consumer).
- Coroutines must be `org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm`
 (patched); vanilla coroutines lacks `kotlinx.coroutines.internal.intellij.IntellijCoroutines`
 and fails only at runtime. The fork is also built without interface `DefaultImpls`, so a
 library compiled against vanilla coroutines cannot share a JVM with it — it dies on calls
 like `SendChannel.close$default`. That is why `packer serve` speaks JSON-RPC by hand
 instead of using the Kotlin MCP SDK; adding that SDK back will not work.
- Missing deps surface as `NoClassDefFoundError` during a test run, never at compile time.
  Read the class name out of `core/build/test-results/` and add the artifact.

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
