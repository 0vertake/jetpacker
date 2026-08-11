package dev.jetpacker.core

import dev.jetpacker.core.index.AnalysisApiIndexer
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.Packer
import dev.jetpacker.core.project.readGradleProject
import dev.jetpacker.core.rank.EdgeWeights
import dev.jetpacker.core.rank.fuse
import dev.jetpacker.core.rank.RRF_K
import dev.jetpacker.core.rank.Ranked
import dev.jetpacker.core.rank.Ranker
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
    private val fuseSearch: Boolean = true,
    private val rrfK: Int = RRF_K,
    testPenalty: Double = SeedFinder.DEFAULT_TEST_PENALTY,
) : Retriever {
    private val seedFinder = SeedFinder(index, testPenalty)
    private val ranker = Ranker(index, weights)

    override fun pack(task: String, budget: Int): Pack =
        Packer(index, repoRoot, budget, fullTierShare, testShare).pack(ranked(task))

    /** The ranking before the budget is applied — what diagnostics need to separate the stages. */
    fun ranked(task: String): List<Ranked> {
        val graph = ranker.rank(seedFinder.find(task, seeds))
        if (!fuseSearch) return graph

        val search = seedFinder.search(task, SEARCH_DEPTH).map { at ->
            Ranked(index.symbols[at], 0.0, "matches:task")
        }
        return fuse(listOf(graph, search), rrfK)
    }

    companion object {
        const val DEFAULT_SEEDS = 20

        /** How deep the keyword ranking is fused in; roughly what a 4k budget can hold. */
        const val SEARCH_DEPTH = 600

        /** Reads the build, resolves the sources, and indexes them. Slow; call once per checkout. */
        fun forRepository(repoRoot: Path, weights: EdgeWeights = EdgeWeights()): Jetpacker {
            val project = readGradleProject(repoRoot)
            val index = AnalysisApiIndexer(
                sourceRoots = project.sourceRoots,
                classpath = project.classpath,
                jdkHome = Path.of(System.getProperty("java.home")),
                repoRoot = repoRoot,
                testRoots = project.testRoots,
            ).use { it.index() }
            return Jetpacker(repoRoot, index, weights)
        }
    }
}
