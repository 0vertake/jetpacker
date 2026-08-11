package dev.jetpacker.eval

import dev.jetpacker.baselines.Bm25Retriever
import dev.jetpacker.baselines.FileDumpRetriever
import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.rank.EdgeWeights
import dev.jetpacker.core.rank.RRF_K
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
    val budget = System.getProperty("jetpacker.budget")?.toInt() ?: 4000
    val cache = Path.of(System.getProperty("jetpacker.cache") ?: "${System.getProperty("user.home")}/.jetpacker")

    val tasks = mineTasks(repo, wanted)
    println("mined ${tasks.size} tasks from ${repo.fileName}, budget $budget tokens")

    val indexes = Indexes(repo, cache)
    val results = LinkedHashMap<String, MutableList<Score>>()
    val diagnoses = mutableListOf<Diagnosis>()
    val namedSlice = LinkedHashMap<String, MutableList<Score>>()
    val unnamedSlice = LinkedHashMap<String, MutableList<Score>>()
    var skipped = 0

    for ((at, task) in tasks.withIndex()) {
        val snapshot = runCatching { indexes.at(task.baseCommit) }.getOrElse {
            System.err.println("  [${task.id}] skipped: ${it.message?.lines()?.first()}")
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
        for (retriever in retrievers(snapshot)) {
            val pack = retriever.pack(task.text, budget)
            val scored = score(snapshot.index, pack, gold)
            results.getOrPut(retriever.name) { mutableListOf() } += scored
            (if (named) namedSlice else unnamedSlice).getOrPut(retriever.name) { mutableListOf() } += scored
        }
        diagnoses += diagnose(
            snapshot.index,
            engine(snapshot, "base"),
            task,
            gold,
            budget,
        )
        println("  [${at + 1}/${tasks.size}] ${task.id}  gold=${gold.size}")
    }

    report(results, skipped)
    println("\n--- tasks whose text names a changed declaration (keyword search at its best)")
    report(namedSlice, 0)
    println("\n--- tasks whose text names none of them (the case structure is for)")
    report(unnamedSlice, 0)
    reportDiagnostics(diagnoses)
    exitProcess(0)
}

/**
 * Everything scored on each task. Same budget, same packer, same token accounting — the only
 * thing that varies is how candidates are chosen and ordered.
 */
private fun retrievers(snapshot: Snapshot): List<Retriever> = listOf(
    // Baselines get the same fidelity policy as the engine config they are compared against;
    // leaving them on a body-heavy default would have flattered us by ten points.
    Bm25Retriever(snapshot.index, snapshot.root, "bm25:full.00", fullTierShare = 0.00),
    Bm25Retriever(snapshot.index, snapshot.root, "bm25:full.30", fullTierShare = 0.30),
    FileDumpRetriever(snapshot.index, snapshot.root),
    Jetpacker(snapshot.root, snapshot.index, EdgeWeights().none(), name = "seeds-only"),
    // One variable at a time off `base`, so a difference has one cause.
    engine(snapshot, "k:1", rrfK = 1),
    engine(snapshot, "k:3", rrfK = 3),
    engine(snapshot, "k:5", rrfK = 5),
    engine(snapshot, "k:10", rrfK = 10),
    engine(snapshot, "k:20", rrfK = 20),
    engine(snapshot, "k:10+s40", rrfK = 10, seeds = 40),
    engine(snapshot, "k:10+f.15", rrfK = 10, fullTierShare = 0.15),
)

private fun engine(
    snapshot: Snapshot,
    name: String,
    weights: EdgeWeights = EdgeWeights(sameFile = 1.0),
    fullTierShare: Double = 0.0,
    seeds: Int = Jetpacker.DEFAULT_SEEDS,
    fuseSearch: Boolean = true,
    rrfK: Int = RRF_K,
) = Jetpacker(
    snapshot.root,
    snapshot.index,
    weights,
    "jp:$name",
    fullTierShare,
    testShare = 0.1,
    seeds = seeds,
    fuseSearch = fuseSearch,
    rrfK = rrfK,
)

private fun report(results: Map<String, List<Score>>, skipped: Int) {
    if (results.isEmpty()) {
        println("\nno scorable tasks")
        return
    }
    val scored = results.values.first().size
    println("\n$scored tasks scored, $skipped skipped\n")
    println("| retriever      | recall@budget | precision | file recall | tokens |")
    println("|----------------|---------------|-----------|-------------|--------|")
    for ((name, scores) in results.entries.sortedBy { it.key }) {
        val mean = scores.mean()
        println(
            "| %-14s | %13s | %9s | %11s | %6d |".format(
                name,
                percent(mean.recall),
                percent(mean.precision),
                percent(mean.fileRecall),
                mean.tokens,
            ),
        )
    }
}

private fun percent(value: Double) = "%.1f%%".format(value * 100)
