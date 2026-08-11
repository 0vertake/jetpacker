package dev.jetpacker.baselines

import dev.jetpacker.core.Retriever
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.Packer
import dev.jetpacker.core.rank.Ranked
import dev.jetpacker.core.seed.SeedFinder
import java.nio.file.Path

/**
 * BM25 over declarations, packed to budget. No graph, no exact-identifier channel.
 *
 * The literature's strong cheap baseline, and the one that matters most: if compiler-grade
 * structure cannot beat sparse keyword search, the thesis is wrong. It runs the same BM25
 * implementation and the same packer as the real engine, so the only difference measured is the
 * retrieval idea.
 */
class Bm25Retriever(
    private val index: CodeIndex,
    private val repoRoot: Path,
    override val name: String = "bm25",
) : Retriever {
    private val finder = SeedFinder(index)

    override fun pack(task: String, budget: Int): Pack {
        // Rank position stands in for a score: BM25 magnitudes are not comparable across tasks,
        // and the packer only needs a monotone ordering to compute density.
        val ranked = finder.search(task).mapIndexed { rank, symbol ->
            Ranked(index.symbols[symbol], 1.0 / (rank + 1), "bm25")
        }
        return Packer(index, repoRoot, budget).pack(ranked)
    }
}
