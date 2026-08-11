package dev.jetpacker.eval

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Pack

/** Level-1 scores for one task (docs/plan.md §5). */
data class Score(
    val recall: Double,
    val precision: Double,
    val fileRecall: Double,
    val tokens: Int,
    val goldSize: Int,
)

/**
 * Scores a pack against the declarations the fix touched.
 *
 * [fileRecall] is reported next to [recall] because they fail differently: a retriever can find
 * the right file and still miss the method on it, and knowing which of the two broke is what
 * makes a regression diagnosable.
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
        tokens = pack.tokens,
        goldSize = gold.size,
    )
}

/** Mean of per-task scores. Unweighted: every task counts once, whatever its patch size. */
fun List<Score>.mean(): Score = Score(
    recall = map { it.recall }.average(),
    precision = map { it.precision }.average(),
    fileRecall = map { it.fileRecall }.average(),
    tokens = map { it.tokens }.average().toInt(),
    goldSize = sumOf { it.goldSize },
)
