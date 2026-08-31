package dev.jetpacker.eval

import dev.jetpacker.baselines.Bm25Retriever
import dev.jetpacker.baselines.ChunkRetriever
import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.rank.EdgeWeights
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.appendText
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Joins Level-2 patch outcomes with Level-1 / L1.5 pack metrics — no model calls.
 *
 * Re-packs each scored `(task, arm)` row and writes `level2-join.tsv` beside the ledger.
 *
 *     ./gradlew :eval:level2join -Pjetpacker.repo=/tmp/detekt \
 *       -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=detekt
 */
fun main() {
    val repo = Path.of(System.getProperty("jetpacker.repo") ?: error("set -Djetpacker.repo=<path>"))
    val harbor = Path.of(System.getProperty("jetpacker.harbor") ?: error("set -Djetpacker.harbor=<dir>"))
    val repository = System.getProperty("jetpacker.harbor.repo") ?: repo.fileName.toString()
    val budget = System.getProperty("jetpacker.budgets")?.toInt() ?: 4000
    val fullTierShare = System.getProperty("jetpacker.fullTierShare")?.toDouble() ?: 1.0
    val testShare = System.getProperty("jetpacker.testShare")?.toDouble() ?: 0.1
    val cache = Path.of(System.getProperty("jetpacker.cache") ?: "${System.getProperty("user.home")}/.jetpacker")
    val workspace = Path.of(System.getProperty("jetpacker.l2") ?: "${System.getProperty("user.home")}/.jetpacker-l2")
        .also { it.createDirectories() }

    val ledger = workspace.resolve("level2.tsv")
    if (!ledger.exists()) error("no ledger at $ledger")

    val tasksById = harborTasks(harbor, repository).associateBy { it.id }
    val indexes = Indexes(repo, cache)
    val rows = ledger.readLines().mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size < 3) return@mapNotNull null
        val outcome = runCatching { Outcome.valueOf(parts[2]) }.getOrNull() ?: return@mapNotNull null
        JoinRow(parts[0], parts[1], outcome, parts.getOrNull(3)?.toIntOrNull() ?: 0)
    }

    val out = workspace.resolve("level2-join.tsv")
    out.writeText("task\tarm\toutcome\ttokens\tsymbol_recall\tedit_site_recall\tedit_site_loose\tgold_size\n")

    var skipped = 0
    for (row in rows) {
        val task = tasksById[row.task] ?: run { skipped++; continue }
        val snapshot = runCatching { indexes.at(task.baseCommit) }.getOrElse {
            println("[${row.task}] skipped: ${it.message?.lines()?.first()}")
            skipped++
            continue
        }
        val gold = goldSymbols(snapshot.index, task)
        if (gold.isEmpty()) {
            skipped++
            continue
        }

        val metrics = when (val retriever = arm(snapshot, row.arm, fullTierShare, testShare)) {
            null -> JoinMetrics(0.0, 0.0, 0.0, gold.size)
            else -> {
                val pack = retriever.pack(task.text, budget)
                val scored = score(snapshot.index, pack, gold, task)
                JoinMetrics(scored.recall, scored.editSiteRecall, scored.editSiteRecallLoose, gold.size)
            }
        }
        out.appendText(
            "${row.task}\t${row.arm}\t${row.outcome}\t${row.tokens}\t" +
                "${fmt(metrics.symbolRecall)}\t${fmt(metrics.editSiteRecall)}\t" +
                "${fmt(metrics.editSiteLoose)}\t${metrics.goldSize}\n",
        )
    }

    println("joined ${rows.size - skipped}/${rows.size} rows -> $out")
    reportJoin(out)
    exitProcess(0)
}

private data class JoinRow(val task: String, val arm: String, val outcome: Outcome, val tokens: Int)
private data class JoinMetrics(
    val symbolRecall: Double,
    val editSiteRecall: Double,
    val editSiteLoose: Double,
    val goldSize: Int,
)

private fun arm(snapshot: Snapshot, name: String, fullTierShare: Double, testShare: Double): Retriever? =
    when (name) {
        "none" -> null
        "chunk-bm25" -> ChunkRetriever(snapshot.index, snapshot.root)
        "bm25" -> Bm25Retriever(snapshot.index, snapshot.root, "bm25", fullTierShare = fullTierShare)
        "jp" -> Jetpacker(
            snapshot.root,
            snapshot.index,
            EdgeWeights(sameFile = 1.0),
            "jp",
            fullTierShare = fullTierShare,
            testShare = testShare,
        )
        else -> error("unknown arm: $name")
    }

private fun fmt(value: Double) = "%.4f".format(value)

private fun reportJoin(path: Path) {
    val rows = path.readLines().drop(1).map { it.split("\t") }.filter { it.size >= 7 }
    if (rows.isEmpty()) return

    println("\n| arm | n | symbol recall | edit-site | resolved | edit≥0.8 & resolved | sym=1 edit<0.5 |")
    println("|-----|---|---------------|-----------|----------|---------------------|----------------|")
    for ((arm, group) in rows.groupBy { it[1] }.entries.sortedBy { it.key }) {
        val sym = group.mapNotNull { it[4].toDoubleOrNull() }.average()
        val edit = group.mapNotNull { it[5].toDoubleOrNull() }.average()
        val resolved = group.count { it[2] == Outcome.RESOLVED.name }
        val highEditResolved = group.count {
            it[2] == Outcome.RESOLVED.name && (it[5].toDoubleOrNull() ?: 0.0) >= 0.8
        }
        val symbolOnly = group.count {
            (it[4].toDoubleOrNull() ?: 0.0) >= 0.999 && (it[5].toDoubleOrNull() ?: 0.0) < 0.5
        }
        println(
            "| %-4s | %3d | %13s | %9s | %8d | %19d | %14d |".format(
                arm,
                group.size,
                percent(sym),
                percent(edit),
                resolved,
                highEditResolved,
                symbolOnly,
            ),
        )
    }
}

private fun percent(value: Double) = "%.1f%%".format(value * 100)
