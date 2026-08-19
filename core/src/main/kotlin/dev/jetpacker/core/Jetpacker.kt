package dev.jetpacker.core

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.IndexCache
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.Packer
import dev.jetpacker.core.project.readGradleProject
import dev.jetpacker.core.rank.EdgeWeights
import dev.jetpacker.core.rank.Ranked
import dev.jetpacker.core.rank.Ranker
import dev.jetpacker.core.seed.DenseSeeds
import dev.jetpacker.core.seed.SeedFinder
import java.nio.file.Path

/**
 * The whole pipeline for one repository: seeds → graph expansion → knapsack (docs/plan.md §4).
 *
 * Indexing a repository costs about a minute, so an instance is built once and reused across
 * tasks; only [pack] runs per task.
 */
class Jetpacker(
    private val repoRoot: Path,
    val index: CodeIndex,
    weights: EdgeWeights = EdgeWeights(),
    override val name: String = "jetpacker",
    private val fullTierShare: Double = Packer.DEFAULT_FULL_TIER_SHARE,
    private val testShare: Double = Packer.DEFAULT_TEST_SHARE,
    private val seeds: Int = DEFAULT_SEEDS,
    testPenalty: Double = SeedFinder.DEFAULT_TEST_PENALTY,
    dense: DenseSeeds? = null,
) : Retriever {
    private val seedFinder = SeedFinder(index, testPenalty, dense)
    private val ranker = Ranker(index, weights)

    override fun pack(task: String, budget: Int): Pack =
        Packer(index, repoRoot, budget, fullTierShare, testShare).pack(ranked(task))

    /** The ranking before the budget is applied — what diagnostics need to separate the stages. */
    fun ranked(task: String): List<Ranked> = ranker.rank(seedFinder.find(task, seeds))

    companion object {
        const val DEFAULT_SEEDS = 20

        /**
         * Reads the build and indexes the sources. Slow on a cold cache; a later call on the
         * same checkout reuses the on-disk index, or re-resolves only the files that changed.
         */
        fun forRepository(
            repoRoot: Path,
            weights: EdgeWeights = EdgeWeights(),
            cacheDir: Path = IndexCache.defaultDir(),
        ): Jetpacker {
            val project = readGradleProject(repoRoot)
            val index = IndexCache.loadOrIndex(
                repoRoot = repoRoot,
                sourceRoots = project.sourceRoots,
                classpath = project.classpath,
                testRoots = project.testRoots,
                cacheDir = cacheDir,
            )
            return Jetpacker(repoRoot, index, weights)
        }
    }
}
