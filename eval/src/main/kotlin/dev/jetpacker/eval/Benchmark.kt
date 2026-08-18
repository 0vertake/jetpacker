package dev.jetpacker.eval

import dev.jetpacker.baselines.Bm25Retriever
import dev.jetpacker.baselines.ChunkRetriever
import dev.jetpacker.baselines.Embedder
import dev.jetpacker.baselines.EmbeddingChunkRetriever
import dev.jetpacker.baselines.EmbeddingSeeds
import dev.jetpacker.baselines.FileDumpRetriever
import dev.jetpacker.baselines.RepoMapRetriever
import dev.jetpacker.baselines.nameMatchedIndex
import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.rank.EdgeWeights
import dev.jetpacker.core.seed.DenseSeeds
import dev.jetpacker.core.seed.SeedFinder
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Level-1 retrieval benchmark (docs/plan.md §5): no model, no API spend, run constantly.
 *
 *     ./gradlew :eval:run -Pjetpacker.repo=/path/to/repo -Pjetpacker.tasks=30
 */
fun main() {
    val repo = Path.of(System.getProperty("jetpacker.repo") ?: error("set -Djetpacker.repo=<path>"))
    val wanted = System.getProperty("jetpacker.tasks")?.toInt() ?: 30
    val budgets = (System.getProperty("jetpacker.budgets") ?: "$HEADLINE_BUDGET").split(",").map { it.trim().toInt() }
    val cache = Path.of(System.getProperty("jetpacker.cache") ?: "${System.getProperty("user.home")}/.jetpacker")

    val harbor = System.getProperty("jetpacker.harbor")?.let { Path.of(it) }
    val tasks = when (harbor) {
        null -> mineTasks(repo, wanted)
        // The suite names repositories as their upstream does, which a local clone need not.
        else -> harborTasks(harbor, System.getProperty("jetpacker.harbor.repo") ?: repo.fileName.toString(), wanted)
    }
    val source = if (harbor == null) "mined from" else "Kotlin Benchmark issues for"
    println("${tasks.size} tasks $source ${repo.fileName}, budgets ${budgets.joinToString(", ")}")

    // Off unless asked for: the model is a download, and embedding every window of every checkout
    // adds minutes to a run that is otherwise cheap enough to sit through.
    val embedder = System.getProperty("jetpacker.embed")?.let { Embedder() }

    val indexes = Indexes(repo, cache)
    val results = budgets.associateWith { LinkedHashMap<String, MutableList<Score>>() }
    val diagnoses = mutableListOf<Diagnosis>()
    val namedSlice = LinkedHashMap<String, MutableList<Score>>()
    val unnamedSlice = LinkedHashMap<String, MutableList<Score>>()
    val headline = budgets.firstOrNull { it == HEADLINE_BUDGET } ?: budgets.first()
    var skipped = 0

    for ((at, task) in tasks.withIndex()) {
        val snapshot = runCatching { indexes.at(task.baseCommit) }.getOrElse {
            System.err.println("  [${task.id}] skipped: ${it.reason()}")
            skipped++
            continue
        }
        val gold = goldSymbols(snapshot.index, task)
        if (gold.isEmpty()) {
            // The patch touched only files this index does not cover, so nothing could be found.
            skipped++
            continue
        }

        val named = namesItsTarget(snapshot.index, task, gold)
        for (retriever in retrievers(snapshot, embedder)) {
            for (budget in budgets) {
                val scored = score(snapshot.index, retriever.pack(task.text, budget), gold)
                results.getValue(budget).getOrPut(retriever.name) { mutableListOf() } += scored
                if (budget != headline) continue
                (if (named) namedSlice else unnamedSlice).getOrPut(retriever.name) { mutableListOf() } += scored
            }
        }
        diagnoses += diagnose(snapshot.index, engine(snapshot, "default", fullTierShare = 0.15), task, gold, headline)
        println("  [${at + 1}/${tasks.size}] ${task.id}  gold=${gold.size}")
    }

    for (budget in budgets) {
        println("\n=== budget $budget tokens")
        report(results.getValue(budget), skipped)
    }
    println("\n--- at $headline tokens, tasks whose text names a changed declaration")
    report(namedSlice, 0)
    println("\n--- at $headline tokens, tasks whose text names none of them (the case structure is for)")
    report(unnamedSlice, 0)
    reportDiagnostics(diagnoses)
    embedder?.close()
    exitProcess(0)
}

/**
 * The failure's own message plus its deepest cause.
 *
 * A Gradle Tooling API failure says "Could not fetch model of type 'IdeaProject'" at the top and
 * names the actual build problem several causes down, which is the difference between "this
 * repository does not work" and a JDK version to change.
 */
private fun Throwable.reason(): String {
    val root = generateSequence(this) { it.cause }.last()
    val head = message?.lines()?.first().orEmpty()
    if (root === this) return head
    return "$head <- ${root::class.simpleName}: ${root.message?.lines()?.first()}"
}

/** The budget the slices and diagnostics describe, and the one results are quoted at. */
private const val HEADLINE_BUDGET = 4000

/** Deep enough for any arm to spend the largest budget swept. */
private const val CANDIDATES = 400

/**
 * Everything scored on each task. Same budget, same packer, same token accounting — the only
 * thing that varies is how candidates are chosen and ordered.
 */
private fun retrievers(snapshot: Snapshot, embedder: Embedder?): List<Retriever> = listOfNotNull(
    // Baselines get the same fidelity policy as the engine config they are compared against;
    // leaving them on a body-heavy default would have flattered us by ten points.
    Bm25Retriever(snapshot.index, snapshot.root, "bm25:full.00", fullTierShare = 0.00),
    ChunkRetriever(snapshot.index, snapshot.root),
    // The same windows ranked by meaning instead of keywords — RAG as most people build it.
    embedder?.let { EmbeddingChunkRetriever(snapshot.index, snapshot.root, it) },
    Bm25Retriever(snapshot.index, snapshot.root, "bm25:full.30", fullTierShare = 0.30),
    FileDumpRetriever(snapshot.index, snapshot.root),
    RepoMapRetriever(snapshot.index, snapshot.root),
    // The headline ablation: the same seed ranking, with structural expansion switched off. It
    // needs enough seeds to fill the budget, or it would be losing to having nothing to pack.
    Jetpacker(snapshot.root, snapshot.index, EdgeWeights().none(), "seeds-only", seeds = CANDIDATES),
    // One variable at a time off the default, so a difference has one cause.
    engine(snapshot, "default", fullTierShare = 0.15),
    engine(snapshot, "all-stubs"),
    engine(snapshot, "seed-tests", fullTierShare = 0.15, testPenalty = 1.0),
    // Same engine, seeds also ranked by MiniLM. Off with the rest of the embedding arms.
    embedder?.let {
        engine(snapshot, "embed-seeds", fullTierShare = 0.15, dense = EmbeddingSeeds(snapshot.index, it))
    },
    // Resolution off: the same engine over call edges a parser could have produced (§5). Built per
    // task and not cached: every task is a different checkout, so a cache across them only holds
    // dead indexes — each one millions of ambiguous edges — until the run is thrashing the GC.
    Jetpacker(
        snapshot.root,
        nameMatchedIndex(snapshot.index, snapshot.root),
        EdgeWeights(),
        "names-only",
        fullTierShare = 0.15,
        testShare = 0.1,
    ),
) + edgeAblations(snapshot)

/**
 * One relation removed at a time, against `jp:default` (docs/plan.md §5).
 *
 * `seeds-only` says expansion pays; these say what it is paying for. A relation whose removal costs
 * nothing is one the engine could stop extracting, and one whose removal costs a lot is the claim.
 * Directions are separate where they mean different things: `-callers` can still walk from a
 * declaration to what it calls, while `-calls` removes the call relation entirely.
 */
private fun edgeAblations(snapshot: Snapshot): List<Retriever> = listOf(
    "-calls" to EdgeWeights(calls = 0.0, calledBy = 0.0),
    "-callers" to EdgeWeights(calledBy = 0.0),
    "-impls" to EdgeWeights(extends = 0.0, extendedBy = 0.0, overrides = 0.0, overriddenBy = 0.0),
    "-contains" to EdgeWeights(contains = 0.0, containedBy = 0.0),
    "-samefile" to EdgeWeights(sameFile = 0.0),
).map { (name, weights) -> engine(snapshot, name, weights, fullTierShare = 0.15) } +
    // Not an edge kind: test code is reached by ordinary call edges, so the only way to ask what it
    // is worth is to refuse to pack it.
    engine(snapshot, "-testcode", fullTierShare = 0.15, testShare = 0.0)

private fun engine(
    snapshot: Snapshot,
    name: String,
    weights: EdgeWeights = EdgeWeights(sameFile = 1.0),
    fullTierShare: Double = 0.0,
    seeds: Int = Jetpacker.DEFAULT_SEEDS,
    testPenalty: Double = SeedFinder.DEFAULT_TEST_PENALTY,
    testShare: Double = 0.1,
    dense: DenseSeeds? = null,
) = Jetpacker(
    snapshot.root,
    snapshot.index,
    weights,
    "jp:$name",
    fullTierShare,
    testShare = testShare,
    seeds = seeds,
    testPenalty = testPenalty,
    dense = dense,
)

private fun report(results: Map<String, List<Score>>, skipped: Int) {
    if (results.isEmpty()) {
        println("\nno scorable tasks")
        return
    }
    val scored = results.values.first().size
    println("\n$scored tasks scored, $skipped skipped\n")
    println("| retriever      | recall@budget | +callers | nDCG  | precision | file recall | tokens |")
    println("|----------------|---------------|----------|-------|-----------|-------------|--------|")
    for ((name, scores) in results.entries.sortedBy { it.key }) {
        val mean = scores.mean()
        println(
            "| %-14s | %13s | %8s | %5.3f | %9s | %11s | %6d |".format(
                name,
                percent(mean.recall),
                percent(mean.callerRecall),
                mean.ndcg,
                percent(mean.precision),
                percent(mean.fileRecall),
                mean.tokens,
            ),
        )
    }
}

private fun percent(value: Double) = "%.1f%%".format(value * 100)
