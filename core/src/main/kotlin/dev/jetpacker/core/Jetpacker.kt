package dev.jetpacker.core

import dev.jetpacker.core.index.AnalysisApiIndexer
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.Packer
import dev.jetpacker.core.project.readGradleProject
import dev.jetpacker.core.rank.EdgeWeights
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
    private val weights: EdgeWeights = EdgeWeights(),
) {
    private val seedFinder = SeedFinder(index)
    private val ranker = Ranker(index, weights)

    fun pack(task: String, budget: Int = DEFAULT_BUDGET): Pack =
        Packer(index, repoRoot, budget).pack(ranker.rank(seedFinder.find(task)))

    companion object {
        const val DEFAULT_BUDGET = 4000

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
