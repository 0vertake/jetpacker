package dev.jetpacker.eval

import dev.jetpacker.baselines.Bm25Retriever
import dev.jetpacker.baselines.FileDumpRetriever
import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.rank.EdgeWeights
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

        for (retriever in retrievers(snapshot)) {
            val pack = retriever.pack(task.text, budget)
            results.getOrPut(retriever.name) { mutableListOf() } += score(snapshot.index, pack, gold)
        }
        println("  [${at + 1}/${tasks.size}] ${task.id}  gold=${gold.size}")
    }

    report(results, skipped)
    exitProcess(0)
}

/**
 * Everything scored on each task. Same budget, same packer, same token accounting — the only
 * thing that varies is how candidates are chosen and ordered.
 */
private fun retrievers(snapshot: Snapshot): List<Retriever> = listOf(
    Bm25Retriever(snapshot.index, snapshot.root),
    FileDumpRetriever(snapshot.index, snapshot.root),
    Jetpacker(snapshot.root, snapshot.index, EdgeWeights().none(), name = "seeds-only"),
    Jetpacker(snapshot.root, snapshot.index, EdgeWeights(), name = "jetpacker"),
)

private fun report(results: Map<String, List<Score>>, skipped: Int) {
    if (results.isEmpty()) {
        println("\nno scorable tasks")
        return
    }
    val scored = results.values.first().size
    println("\n$scored tasks scored, $skipped skipped\n")
    println("| retriever    | recall@budget | precision | file recall | tokens |")
    println("|--------------|---------------|-----------|-------------|--------|")
    for ((name, scores) in results.entries.sortedBy { it.key }) {
        val mean = scores.mean()
        println(
            "| %-12s | %13s | %9s | %11s | %6d |".format(
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
