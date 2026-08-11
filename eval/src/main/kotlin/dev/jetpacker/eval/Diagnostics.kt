package dev.jetpacker.eval

import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.rank.Ranked

/**
 * Why a task's gold declaration was missed, attributed to the stage that lost it.
 *
 * Recall alone cannot tell "never reachable" from "ranked 400th" from "ranked well but priced out
 * of the budget", and those three call for completely different fixes. Every tuning decision
 * downstream depends on this split, so it is measured rather than guessed at.
 */
data class Diagnosis(
    val reachable: Boolean,
    val bestRank: Int?,
    val packed: Boolean,
    val testTokenShare: Double,
)

fun diagnose(index: CodeIndex, engine: Jetpacker, task: Task, gold: Set<String>, budget: Int): Diagnosis {
    val ranked: List<Ranked> = engine.ranked(task.text)
    val bestRank = ranked.indexOfFirst { it.symbol.id in gold }.takeIf { it >= 0 }
    val pack = engine.pack(task.text, budget)

    return Diagnosis(
        reachable = bestRank != null,
        bestRank = bestRank,
        packed = pack.items.any { it.symbol.id in gold },
        testTokenShare = pack.testTokenShare(),
    )
}

/** Budget spent on test code, which can never be gold — pure overhead for localization. */
private fun Pack.testTokenShare(): Double =
    if (tokens == 0) 0.0 else items.filter { it.symbol.isTest }.sumOf { it.tokens }.toDouble() / tokens

fun reportDiagnostics(diagnoses: List<Diagnosis>) {
    if (diagnoses.isEmpty()) return
    val reachable = diagnoses.count { it.reachable }
    val packed = diagnoses.count { it.packed }
    val ranks = diagnoses.mapNotNull { it.bestRank }.sorted()

    println(
        """
        |
        |where recall is lost, over ${diagnoses.size} tasks:
        |  gold reachable by expansion   $reachable
        |  of those, survived the budget $packed
        |  median rank of best gold      ${ranks.getOrNull(ranks.size / 2) ?: "-"}
        |  rank <= 50                    ${ranks.count { it <= 50 }}
        |  budget spent on test code     ${"%.1f%%".format(diagnoses.map { it.testTokenShare }.average() * 100)}
        """.trimMargin(),
    )
}
