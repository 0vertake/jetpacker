# Analysis API notes

Jetpacker indexes Kotlin with the **Kotlin Analysis API Standalone** host
(`analysis-api-standalone-for-ide`), not with tree-sitter and not inside a running IDE.
Resolution quality is the product claim: call edges come from the compiler’s answer to “what does
this call?”, and the Level-1 `names-only` ablation rebuilds those edges from bare names to measure
what resolution buys.

## Why Standalone

- Headless: suitable for CI and for `packer serve` without an IntelliJ process.
- Same semantic model KSP and the detekt Analysis API migration use.
- Experimental API: versions lag Kotlin stable and must stay pinned together
  (see `gradle/libs.versions.toml`). Independent bumps break resolution.

## Dependency wiring (hard-won)

The Analysis API is not on Maven Central ([KT-56203](https://youtrack.jetbrains.com/issue/KT-56203)).
Two JetBrains repos are required:

- `packages.jetbrains.team/.../intellij-dependencies` for `org.jetbrains.kotlin:*-for-ide`
- `intellij-repository/releases` for `com.jetbrains.intellij.platform:*` and `.java:*`

`*-for-ide` artifacts are declared `isTransitive = false` on purpose: their POMs reference
unpublished coordinates. Runtime deps are listed by hand (mirroring `google/ksp`’s
`kotlin-analysis-api` module). Missing classes surface as `NoClassDefFoundError` at test time,
never at compile time.

Coroutines must be `org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm` (patched).
Vanilla coroutines lack `kotlinx.coroutines.internal.intellij.IntellijCoroutines` and fail only at
runtime. The fork is also built without interface `DefaultImpls`, so a library compiled against
vanilla coroutines cannot share a JVM with it. That is why `packer serve` speaks JSON-RPC by hand
instead of using the Kotlin MCP SDK.

## What the index records

Per declaration: id (signature-derived, so overloads are distinct), FQN, name, kind, file, line
range, signature, first doc line, token count, test flag. Edges: contains, calls, extends,
overrides, plus optional imports / type-references (default weight zero in the ranker). Coverage
counters (`callSites`, `resolvedCallees`, `attributedToCaller`, `failedFiles`) are printed in every
pack header.

Bodies are not stored in the index; the packer re-reads source for `FULL` fidelity. The on-disk
cache under `~/.jetpacker/indexes/v*` is keyed by Kotlin source stamps and classpath identity and
is patched when a few files change.

## Where resolution is thin

On the published Level-1 suites, call resolution is strong on detekt / ktlint / ort and weak on
dataframe (~38% of call sites resolved in the reported run — a KDoc duplicate of `core` collapsed
into one Analysis API module). Where the graph is thin, embeddings over chunks beat the engine on
some budgets. That is a measured loss case, not a surprise failure mode.

## Fallback policy

If Standalone cannot host a repository, the planned fallback is IntelliJ-headless with the real
project model — not tree-sitter. Tree-sitter baselines live only in `baselines/` for the eval.
