# PSI Context Packer — Research & Build Plan

*Working name ideas: `packrat`, `briefcase`, `kontext`, `psi-pack`. Pick later; the eval matters, not the name.*

## 1. One-liner

A Kotlin engine that builds a **token-budgeted context pack** for AI coding agents from **compiler-grade code structure** (Kotlin Analysis API / PSI): resolved symbols, callers, implementations, type hierarchies, and affected tests — instead of similar-looking text chunks. Shipped as a CLI + MCP server, validated by a **published benchmark** against chunk-RAG and a tree-sitter/Aider-style baseline.

**The eval is the product.** The pack engine is how you get the numbers.

---

## 2. Thesis and evidence (why this is worth building)

The claim: for resolve-heavy Java/Kotlin tasks, deterministic structural retrieval beats similarity search, and compiler-grade resolution beats surface-level (tree-sitter) structure.

Evidence collected (keep these for the README/related-work section):

- **Vector RAG fails multi-hop architectural reasoning.** A Jan 2026 benchmark on Java repos (Shopizer, ThingsBoard, OpenMRS) found deterministic AST-derived graph RAG beats both vector-only RAG and LLM-extracted knowledge graphs on correctness, coverage, indexing cost ([arXiv:2601.08773](https://arxiv.org/abs/2601.08773)).
- **Structural vs semantic queries are different subsystems.** The LongMemCode taxonomy paper argues text/vector search covers only 6–54% of structural queries while graph traversal covers 99–100% ([argosbrain taxonomy](https://argosbrain.com/papers/structural-semantic-taxonomy)). Route, don't unify.
- **Embedding models drop sharply on agentic retrieval.** CORE-Bench (180K queries built from SWE-bench-style tasks) shows a large gap between classic code search and issue-to-edit localization ([arXiv:2606.11864](https://arxiv.org/html/2606.11864v1)).
- **Graph structure measurably helps localization.** LocAgent (ACL 2025): heterogeneous code graph (contain/import/invoke/inherit) + multi-hop traversal reaches 92.7% file-level Acc; ablations show `invoke/import/inherit` edges and multi-hop each matter several points ([arXiv:2503.09089](https://arxiv.org/abs/2503.09089)). SpIDER: graph-aware retrieval improves strong dense retrievers ≥13% Recall@20 ([arXiv:2512.16956](https://arxiv.org/pdf/2512.16956)).
- **One-shot retrieval underperforms exploration; packs must compensate.** SWE-Explore shows BM25/TF-IDF/light dense retrievers sit near Random for repository exploration while agentic explorers are far higher ([arXiv:2606.07297](https://arxiv.org/html/2606.07297v1)). Implication: a *good pack* must approximate what an agent finds by exploring — callers, callees, tests — not just top-k matches. This is the packer's job description.
- **Practitioner consensus (HN/blogs):** grep beats embeddings on small repos (Augment/SWE-bench experience), embeddings win cross-vocabulary, hybrid + rerank is the production answer; staleness is the tax on all indexes. Aider's repo map is the acknowledged structural baseline; its own docs/community note it has *no cross-file resolution, no type info, surface names only* ([aider repomap](https://aider.chat/docs/repomap.html), [argosbrain vs aider](https://argosbrain.com/vs/aider)).

The open wedge nobody has published: **compiler-resolved (PSI/Analysis API) packing vs tree-sitter packing, ablated on a fixed Kotlin/Java suite.** Kotlin's strengths make the gap visible: extension functions, interface injection (Spring), overloads, `expect/actual`, synthetic accessors — cases where surface-name graphs mislead.

### What NOT to claim
- Not "first structural context tool" (Aider, Serena, LocAgent, spy-code, knowing, ContextOS exist).
- Not "beats Cursor/Claude Code" — different category (one-shot pack vs interactive agent).
- Claim only what the table shows: PSI pack vs chunk RAG vs tree-sitter pack, same model, same budget, fixed suite.

---

## 3. Prior art map (differentiation cheat sheet)

| Tool | What it does | Why we're different |
|---|---|---|
| **Aider RepoMap** | tree-sitter tags → file-level PageRank → ~1k token map | file-level nodes, surface names, no resolution, no callers-of-symbol; we do symbol-level resolved graph + task-directed packing |
| **Serena** (+ paid JB plugin) | MCP *tools* (find_symbol, references) the agent calls iteratively | tools ≠ packs; we produce a one-shot budgeted briefing; also we publish an eval |
| **LocAgent** | agent explores a tree-sitter-ish graph with 3 tools | agent-in-the-loop, Python-centric; we are pack-first, JVM-first, compiler-resolved |
| **knowing / spy-code / ContextOS / Mimir** | tree-sitter graphs + BM25/embeddings, some packing | none use compiler-grade resolution; none publish PSI-vs-tree-sitter ablations |
| **JetBrains built-in MCP / AI Assistant** | exposes IDE search tools to agents | again tools, not budgeted packs; our niche is the packing policy + eval |

Design ideas worth stealing (with attribution):
- **knowing**: seeds → Random Walk with Restart → density-ranked greedy knapsack (`score/token_cost`) under budget; caching per task.
- **Aider**: personalized PageRank biased by "chat files" (for us: task-mentioned symbols); binary search to fit budget.
- **LocAgent**: 4-edge heterogeneous graph is enough (contain/import/invoke/inherit); multi-hop matters; sparse name index as entry point.
- **cAST/AST chunking**: retrieval units must be whole declarations, never char windows.
- **Tiered fidelity** (ContextOS "compiler"): full body for top items, signature stubs for the tail — stretches the budget.

---

## 4. Architecture

```
task text ──► [1 Seed finder] ──► [2 Graph expander] ──► [3 Ranker] ──► [4 Packer] ──► [5 Renderer] ──► pack
                    │                    │
             name index (BM25)    resolved code graph
             + optional embeddings   (Analysis API)
```

### Module breakdown

1. **Indexer** (offline, incremental later)
   - Parse project once with **Kotlin Analysis API Standalone** (`org.jetbrains.kotlin:analysis-api-standalone-for-ide`, published on the JetBrains `intellij-dependencies` Maven repo — verified Aug 2026) — headless, no IDE. Used in production by KSP; detekt is migrating to it. Experimental API, pin the version. **Releases lag Kotlin**: latest proper release is 2.3.20 while Kotlin stable is 2.4.10; whether AA 2.3.x reliably analyzes 2.4-language repos is a Phase 0 checkpoint.
   - Extract: declarations (class/fun/property), FQNs, signatures, KDoc first line, file/line ranges, and edges: `contains`, `imports`, `calls` (resolved call targets), `overrides/implements`, `extends`, `references-type`, `tested-by` (JUnit class/method naming + `@Test` scan).
   - Store in SQLite (symbols, edges, fts5 table for names/doc text). Token cost per node precomputed (see §6).
   - Java interop: Analysis API resolves Java symbols referenced from Kotlin; for Java-only files use IntelliJ's Java PSI in the same standalone environment or (fallback) accept Kotlin-first scope for v1.

2. **Seed finder** (per task)
   - Input: task text (issue description / user prompt) + optional focus files.
   - Channels: (a) FTS/BM25 over symbol names + identifiers-split-by-camelCase + KDoc; (b) exact identifier hits from backtick/code spans in task text; (c) optional embedding channel later. Fuse with Reciprocal Rank Fusion (RRF).
   - Output: 5–20 seed symbols with scores.

3. **Graph expander**
   - From seeds, bounded traversal (depth 2–3) over edges with per-edge-type weights: callers, callees, overrides/implementations (critical for interface-injected code), supertypes, tests of touched symbols.
   - Personalized PageRank or RWR over the subgraph, seeds as restart set (this is where Aider/knowing converge; either works, PPR simpler with jgrapht or hand-rolled power iteration).

4. **Packer**
   - Greedy knapsack ranked by `score / token_cost`, with **tiered fidelity**: rank ≤ N → full declaration body; below → signature + doc line stub; below → FQN mention only.
   - Hard token budget (default 4k; configurable). Dedup nested declarations (don't pack method + its whole class body twice).
   - Always reserve a fixed slice for: file tree skeleton of touched packages + build coordinates (Gradle module names).

5. **Renderer**
   - Deterministic, cache-friendly layout (stable ordering!) so agent-side prompt caching works:
     ```
     ## Task focus
     <seeds with 1-line why>
     ## Definitions (full)
     <code with file:line headers>
     ## Related signatures
     <stubs>
     ## Tests likely affected
     ## Module map
     ```
   - Also a JSON format for programmatic consumers / the eval harness.

6. **Delivery surfaces**
   - **CLI**: `packer pack --repo . --task task.md --budget 4000 --format md|json`
   - **MCP server**: single tool `get_context_pack(task, budget)` — agents (Claude Code, Cursor) call it once at task start. One tool, tiny schema — MCP tool bloat is a known tax.
   - **IntelliJ plugin**: optional, last. Thin action that runs the engine in-IDE with the *real* project model. Nice for the JetBrains story, not required for the eval.

### Tech stack
- Kotlin 2.4.x / JVM 21, Gradle 9.5 (repo scaffolded Aug 2026: modules `core`, `cli`, `baselines`, `eval`; CI green). `analysis-api-standalone-for-ide` for resolution; SQLite (sqlite-jdbc) + FTS5; jtokkit (or similar) for token counting matching the eval model's tokenizer; kotlinx-serialization for pack JSON; official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) for the server.
- Baseline implementations live in the same repo under `baselines/` (fair comparison, same harness).

---

## 5. Benchmark plan (the actual product)

### Task suites
1. **Kotlin-SWE-bench** ([Kotlin/kotlin-swe-bench](https://github.com/Kotlin/kotlin-swe-bench)) — as of July 2026 this is **JetBrains' official "Kotlin Benchmark"** with a public leaderboard at [kotlinlang.org/benchmark](https://kotlinlang.org/benchmark), built on Multi-SWE-bench infra and run with the Harbor CLI (`uv tool install harbor`). 105 tasks, 8 OSS Kotlin repos (ktlint 43, detekt 28, ort 12, TeXiFy, AnkiDroid, okhttp…), each with base commit, issue text, gold patch, regression tests. This is the jackpot: gold patches give **ground-truth relevant symbols** (the declarations touched by the fix). Bonus: Level-2 agent-assisted runs through Harbor make our numbers directly comparable to official leaderboard entries — distribution and credibility for free.
2. **Multi-SWE-bench Java** subset ([multi-swe-bench](https://github.com/multi-swe-bench/multi-swe-bench)) — optional second language once Kotlin works.
3. **Hand-authored micro-suite (30–50 tasks)** on 1–2 repos for the retrieval-only eval: locate/explain/edit questions with hand-labeled gold symbol sets. Needed because SWE-bench tasks only give edit-location ground truth, not "what context is sufficient."
4. *(optional)* **firebenders/Kotlin-bench** ([repo](https://github.com/firebenders/Kotlin-bench)) — 100 Kotlin/Android tasks with published "oracle retrieval context" dataset variants on HuggingFace; useful as a second Kotlin suite and as a sanity check for our gold-context extraction.

### Two evaluation levels

**Level 1 — Retrieval quality (cheap, no LLM, run constantly):**
- Gold = set of declarations modified by the fix patch (+ their direct callers as partial credit, weight 0.5).
- Metrics: Recall@budget, Precision@budget, nDCG, file-level and function-level Acc (LocAgent-style), tokens used.
- This is your inner-loop metric during development.

**Level 2 — End-to-end task success (expensive, run at milestones):**
- Harness: same model (pin one, e.g. current Sonnet), same prompt template, same budget; only the context block varies. Modes:
  - (a) **one-shot patch**: model sees task + pack, emits patch → run suite verifier (Harbor for Kotlin-SWE-bench);
  - (b) **agent-assisted**: agent (e.g. mini-SWE-agent or Claude Code) with pack injected at turn 1 vs without → resolved rate, tokens, turns.
- Metrics: resolved %, tokens per resolved task, turns/tool-calls per task.

### Baselines (all in-repo, same harness — non-negotiable)
1. **No context** (floor)
2. **Naive chunk RAG**: fixed-size chunks + local embedding model + cosine top-k to budget
3. **BM25 file/function retrieval** (strong cheap baseline per literature)
4. **Tree-sitter structural pack**: Aider-style — tree-sitter tags, file-level PageRank, budgeted map. Implement faithfully; this is the comparison that makes or breaks the claim.
5. *(stretch)* Full-file dump of top BM25 files (what lazy tooling does)

### Ablations (this is what makes it research-grade)
- PSI resolution ON vs OFF (fall back to name matching) — the headline ablation
- Edge types: −callers, −implementations, −tests, −imports
- Tiered fidelity vs full-bodies-only
- Budget sweep: 1k / 2k / 4k / 8k
- Seed channel: BM25 only vs +exact identifiers vs +embeddings

### Where PSI should visibly win (curate tasks that show it)
- Interface with multiple implementations (Spring DI style) — tree-sitter can't pick the right impl
- Extension functions / overloads — surface names collide
- Callers-of-symbol across files — requires resolution
- Renamed-import / aliased references

Honesty rule: publish the cases where tree-sitter ties or wins too (pure keyword tasks probably tie). That's what makes the numbers credible.

---

## 6. Design details worth deciding early

- **Token counting**: use the tokenizer of the eval model for budget math; store approximate counts in the index, exact-count at pack time.
- **Determinism**: same repo state + task ⇒ byte-identical pack (stable sort keys). Needed for caching, debugging, and credible evals.
- **Staleness**: v1 = index per commit (evals are fixed commits anyway). Incremental re-index is a post-MVP feature, not a launch requirement.
- **Pack explainability**: every packed item carries `why: seed|caller-of:X|impl-of:Y|test-of:Z` — one line each. Cheap to add, huge for demos and debugging, and it's the beginning of the "agent can trust/verify the pack" story.
- **AI in the loop (optional, later)**: LLM reranker on the top-50 candidates (SpIDER-style) as an *ablation row*, never the core. Keeps the "deterministic core + AI garnish" discipline.

---

## 7. Milestones & kill-tests

**Phase 0 — Feasibility spike (week 1)** — *✅ gate closed (Aug 2026); no pivot needed*
- ✅ Standalone AA stands up headless and resolves all three kill-test queries on a fixture built from the resolve-heavy cases: call target behind an aliased import, callers of an interface method invoked through injection, implementations of an interface (`core` module, `AnalysisApiResolverTest`). AA 2.3.20 runs fine inside a Kotlin 2.4.10 / JVM 21 Gradle build.
- ✅ Resolution reaches *through binary dependencies*: given a classpath and JDK home, calls resolve into the Kotlin stdlib and into a plain Java library (Guava), confirming the Java interop assumed in §4.
- ✅ **Real repo:** the Gradle Tooling API's IDEA model yields source roots and resolved dependency jars without the target build cooperating. On detekt (multi-module, convention plugins, version catalog): 132 source roots, 64 classpath entries, **29,486 distinct resolved call edges**, ~1 min end to end. The §8 "Gradle project import outside the IDE" risk is retired.
- ✅ **Resolution completeness is measured**, not assumed (`ResolutionCoverage`). On detekt, of 36,169 call sites: **96.0% of callees resolve**, and 89.8% also carry a caller. The 4% resolution miss is the honest ceiling on graph quality; the 6.2pp attribution gap is ours to close (below).
- ✅ Determinism and the resolve-vs-name-match distinction are under test (`ResolutionGuaranteesTest`): two independent sessions over one fixture produce identical edge lists, and a call to a name defined as both a member and an extension resolves to the member — the case surface matching gets wrong.
- ✅ Both Phase 0 findings are designed out in the Phase 1 indexer: caller attribution now walks to the enclosing *declaration* (96.0%, exactly equal to the callee resolution rate — no resolved call is dropped), and symbol identity carries parameter types and extension receivers, so overloads are distinct nodes.
- ✅ **The 64 classpath entries are real, and what the model misses is now known.** Asked module by module, detekt's IDEA model reports 1,272 dependency entries across 36 modules in every scope — `COMPILE` 203, `PROVIDED` 162, `RUNTIME` 116, `TEST` 791 — plus 306 project-to-project edges. They dedupe to 64 distinct jars, so nothing was being dropped by scope. Two things *are* invisible: dependencies declared in a **custom configuration** (Gradle's `dependencyScope`/`resolvable`, which the IDEA model does not enumerate), and classes that exist only as a **task output**, since the model is read without running tasks. detekt hits both — its `detekt-kotlin-analysis-api*` modules declare the Analysis API in an `aaDependency` configuration and republish it as a shadow jar — so the Analysis API classes that 103 of its files import are not on the classpath the indexer gets, and calls into them count as unresolved. It costs the eval almost nothing: those files are recent (`dev.detekt`), while every base commit in the task set predates them. The direction is also safe — a missing jar removes edges, so it handicaps the engine rather than flattering it.
- ⬜ Deferred, not blockers:
  - Whether AA 2.3.20 handles 2.4-only language features (fixtures are version-neutral). Pinned pair: AA 2.3.20 + IntelliJ platform 251.27812.49.
  - Dump to SQLite (was listed here; it is really the Phase 1 indexer's job).
- **Kill-test:** if standalone AA can't resolve reliably on a real Gradle multi-module repo within the week, pivot delivery to running the engine inside IntelliJ headless (`idea.headless` / plugin + CLI runner) — same project, different host. Do not pivot to tree-sitter; that erases the wedge.

**Phase 1 — Index + seeds (weeks 2–3)**
- ✅ Indexer: whole declarations with file/line ranges, signature, doc line and token cost; four edge kinds (`contains`, `calls`, `extends`, `overrides`). On detekt: 11,931 symbols and 43,704 edges in ~1 min.
  - *Deviation from §4, deliberate:* the index is held in memory rather than SQLite, and `imports` / `references-type` edges are not extracted. With resolved calls, `imports` is a coarser view of the same relation, and LocAgent's result is that a small heterogeneous edge set suffices. Both are cheap to add if an ablation shows they pay; adding them first would be guessing.
- Seed finder with RRF. Golden unit tests on a fixture project (fixture-based tests = JetBrains-style signal).

**Phase 2 — Rank + pack + CLI (weeks 3–4)**
- PPR, knapsack, tiered fidelity, MD/JSON renderer, `pack` CLI. Determinism test: pack twice, diff empty.

**Phase 3 — Eval harness + baselines (weeks 4–6)**
- Gold-symbol extraction from Kotlin-SWE-bench patches. Level-1 metrics vs all 4 baselines on ≥60 tasks. First results table.
- **Kill-test:** if PSI pack doesn't beat chunk RAG on Recall@4k here, stop and diagnose before any Level-2 spend.

**Phase 4 — Level-2 + ablations (weeks 6–8)**
- One-shot patch eval on Kotlin-SWE-bench subset (budget the API spend; ~105 tasks × few modes is affordable if Level 1 already pruned bad configs). Ablation table. README with method + tables + 2 GIFs.

**Phase 5 — Surfaces & polish (weeks 8–10)**
- MCP server; optional thin IntelliJ plugin; blog-style writeup. Marketplace/publication optional.

---

## 8. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Standalone Analysis API is experimental, breaks between versions; releases lag Kotlin (2.3.20 vs 2.4.10 as of Aug 2026) | High | Pin the AA/Kotlin pair; isolate behind an interface; IntelliJ-headless fallback host |
| Gradle project import outside IDE (classpath for resolution) | High | Use Gradle Tooling API to extract classpath; start with repos whose builds are simple; Kotlin-SWE-bench repos already build in Docker |
| PSI wins are marginal over tree-sitter on easy tasks | Medium | Curate resolve-heavy task slice *and* report overall; ablation makes even a modest gap publishable |
| Eval API costs | Medium | Level 1 is free; Level 2 on a subset first; one pinned model |
| Scope creep (plugin UI, incremental indexing, more languages) | High | Plugin and Java are stretch goals; the table is the deliverable |
| Category gets crowded mid-build | Medium | Ship Level-1 results early (repo public from week 4); the PSI-vs-tree-sitter ablation stays novel |

---

## 9. Improvement roadmap (post-MVP, pick by results)

1. Embedding seed channel + RRF (covers vocabulary-gap tasks where BM25 seeds fail)
2. LLM rerank ablation row
3. Incremental indexing (file-watcher, re-resolve dirty modules)
4. Java-first projects via IntelliJ Java PSI
5. IntelliJ plugin with pack preview panel ("why is this here" per item)
6. Diagnostics slice in packs (compile errors + resolution trail — the Explain Red idea as a *module*)
7. Multi-SWE-bench Java expansion; contribute results/harness back to Kotlin-SWE-bench

---

## 10. CV bullets (draft now, refine with real numbers)

> **Context Packer for Coding Agents** — Kotlin engine that packs token-budgeted task context from compiler-resolved code structure (Kotlin Analysis API): seeds → typed code graph → personalized PageRank → density knapsack. On Kotlin-SWE-bench (105 tasks), improved gold-symbol Recall@4k by **X%** over chunk-RAG and **Y%** over a tree-sitter/PageRank baseline; **Z%** fewer tokens per resolved task with the same model. CLI + MCP server; fixture-tested.

One-liner variant:
> Built a PSI-aware context packer + published benchmark showing compiler-grade retrieval beats chunk RAG and tree-sitter maps for Kotlin agent tasks.

---

## 11. Reading list (all verified in research)

**Must-read before coding**
- Analysis API docs & standalone: https://kotl.in/analysis-api · https://github.com/JetBrains/kotlin/tree/master/analysis/analysis-api-standalone
- detekt's AA migration issue (real-world standalone pitfalls): https://github.com/detekt/detekt/issues/8021
- Kotlin-SWE-bench: https://github.com/Kotlin/kotlin-swe-bench
- Aider repomap (baseline to reimplement): https://aider.chat/docs/repomap.html · https://aider.chat/2023/10/22/repomap.html

**Method / eval design**
- LocAgent (graph + ablations): https://arxiv.org/abs/2503.09089
- CORE-Bench (retrieval eval levels): https://arxiv.org/html/2606.11864v1
- SWE-Explore (exploration metrics, nDCG): https://arxiv.org/html/2606.07297v1
- SpIDER (graph-aware dense retrieval): https://arxiv.org/pdf/2512.16956
- AST-graph vs LLM-KG vs vector RAG on Java: https://arxiv.org/abs/2601.08773
- Structural/semantic query taxonomy: https://argosbrain.com/papers/structural-semantic-taxonomy

**Design references**
- knowing context-packing internals (RWR + knapsack): https://github.com/blackwell-systems/knowing/blob/main/docs/architecture/context-packing.md
- Multi-SWE-bench harness: https://github.com/multi-swe-bench/multi-swe-bench
- Grep-vs-embeddings practitioner take: https://jxnl.co/writing/2025/09/11/why-grep-beat-embeddings-in-our-swe-bench-agent-lessons-from-augment/
- Context budget practices (packs should be cache-stable, compact): https://foojay.io/today/context-is-a-budget-eight-levers-and-three-workflow-patterns/
