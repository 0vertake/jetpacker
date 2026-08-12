package dev.jetpacker.eval

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.EdgeKind
import dev.jetpacker.core.pack.Pack
import kotlin.math.log2

/** Level-1 scores for one task (docs/plan.md §5). */
data class Score(
    val recall: Double,
    val precision: Double,
    val fileRecall: Double,
    val callerRecall: Double,
    val ndcg: Double,
    val tokens: Int,
    val goldSize: Int,
)

/**
 * Scores a pack against the declarations the fix touched.
 *
 * [fileRecall] is reported next to [recall] because they fail differently: a retriever can find
 * the right file and still miss the method on it, and knowing which of the two broke is what
 * makes a regression diagnosable.
 *
 * [callerRecall] and [ndcg] answer the two questions plain recall cannot. A pack that holds the
 * method to change but nothing that calls it is not as useful as the number suggests, so §5 asks
 * for the direct callers of gold at half weight. And recall is indifferent to order, while a model
 * reads a pack from the top, so [ndcg] scores where in the pack the gold landed.
 */
fun score(index: CodeIndex, pack: Pack, gold: Set<String>): Score {
    val packed = pack.items.mapTo(HashSet()) { it.symbol.id }
    val hits = gold.count { it in packed }

    val goldFiles = gold.mapNotNullTo(HashSet()) { index.byId[it]?.file }
    val packedFiles = pack.items.mapTo(HashSet()) { it.symbol.file }

    return Score(
        recall = hits.toDouble() / gold.size,
        precision = if (packed.isEmpty()) 0.0 else hits.toDouble() / packed.size,
        fileRecall = if (goldFiles.isEmpty()) 0.0 else goldFiles.count { it in packedFiles }.toDouble() / goldFiles.size,
        callerRecall = weightedRecall(pack, gold, callersOf(index, gold)),
        ndcg = ndcg(pack, gold),
        tokens = pack.tokens,
        goldSize = gold.size,
    )
}

/** Declarations that call gold and are not gold themselves — §5's half-credit tier. */
private fun callersOf(index: CodeIndex, gold: Set<String>): Set<String> =
    index.edges
        .filter { it.kind == EdgeKind.CALLS && it.to in gold }
        .mapTo(HashSet()) { it.from } - gold

/** Gold at full weight, its callers at half, over the best a perfect pack of that size could do. */
private fun weightedRecall(pack: Pack, gold: Set<String>, callers: Set<String>): Double {
    val packed = pack.items.mapTo(HashSet()) { it.symbol.id }
    val ideal = gold.size + CALLER_CREDIT * callers.size
    if (ideal == 0.0) return 0.0
    val earned = gold.count { it in packed } + CALLER_CREDIT * callers.count { it in packed }
    return earned / ideal
}

/**
 * Discounted cumulative gain over the pack's own order, against gold packed as densely as possible.
 *
 * Positions come from the rendered order rather than from a retriever's internal ranking, so every
 * arm is scored on the artifact it actually hands a model — and no baseline has to expose a
 * ranking it does not have.
 */
private fun ndcg(pack: Pack, gold: Set<String>): Double {
    val gains = pack.items.withIndex().sumOf { (at, item) ->
        if (item.symbol.id in gold) 1.0 / log2(at + 2.0) else 0.0
    }
    val best = (0 until minOf(gold.size, pack.items.size)).sumOf { 1.0 / log2(it + 2.0) }
    return if (best == 0.0) 0.0 else gains / best
}

/**
 * Whether the task text spells out the name of something it changed.
 *
 * This is the split that decides whether structure is doing any work. When an issue names the
 * declaration, keyword search is already at its best and there is little for a graph to add;
 * the case the thesis rests on is the other one, where the target has to be reached through
 * relationships (docs/plan.md §5). Reporting only the average hides both.
 */
fun namesItsTarget(index: CodeIndex, task: Task, gold: Set<String>): Boolean {
    val words = Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(task.text).map { it.value }.toSet()
    return gold.any { index.byId[it]?.name in words }
}

/** Mean of per-task scores. Unweighted: every task counts once, whatever its patch size. */
fun List<Score>.mean(): Score = Score(
    recall = map { it.recall }.average(),
    precision = map { it.precision }.average(),
    fileRecall = map { it.fileRecall }.average(),
    callerRecall = map { it.callerRecall }.average(),
    ndcg = map { it.ndcg }.average(),
    tokens = map { it.tokens }.average().toInt(),
    goldSize = sumOf { it.goldSize },
)

/** Gold at full weight, its direct callers at half (docs/plan.md §5). */
private const val CALLER_CREDIT = 0.5
