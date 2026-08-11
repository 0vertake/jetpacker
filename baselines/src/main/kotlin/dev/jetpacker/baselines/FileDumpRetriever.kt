package dev.jetpacker.baselines

import dev.jetpacker.core.Retriever
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.Packer
import dev.jetpacker.core.rank.Ranked
import dev.jetpacker.core.seed.SeedFinder
import java.nio.file.Path

/**
 * Whole top-ranked files, until the budget runs out — what lazy tooling does.
 *
 * Worth measuring because it is the honest floor for *recall*: dumping entire files finds the
 * changed declaration whenever it picks the right file. If structural packing cannot beat this at
 * equal budget, it is not buying anything.
 */
class FileDumpRetriever(
    private val index: CodeIndex,
    private val repoRoot: Path,
    override val name: String = "file-dump",
) : Retriever {
    private val finder = SeedFinder(index)
    private val byFile = index.symbols.groupBy { it.file }

    override fun pack(task: String, budget: Int): Pack {
        val files = finder.search(task).map { index.symbols[it].file }.distinct()

        // Every declaration in a chosen file outranks every declaration in the next file, so the
        // packer fills up with whole files rather than cherry-picking across them.
        val ranked = files.flatMapIndexed { rank, file ->
            byFile[file].orEmpty().map { Ranked(it, 1.0 / (rank + 1), "in-file:$file") }
        }
        return Packer(index, repoRoot, budget, fullTierShare = 1.0).pack(ranked)
    }
}
