package dev.jetpacker.core.index

import dev.jetpacker.core.project.readGradleProject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Points the whole pipeline — Gradle model extraction, then indexing — at an arbitrary repository
 * on disk. This is the Phase 0 kill-test (docs/plan.md §7) in runnable form, and the harness the
 * benchmark uses to index real repositories.
 *
 * Opt-in: it configures someone else's Gradle build, which needs network and minutes.
 *
 *     ./gradlew :core:test -Djetpacker.repo=/path/to/detekt --tests '*RealRepository*'
 */
class RealRepositoryIndexTest {
    @Test
    fun `indexes a real repository`() {
        val repo = System.getProperty("jetpacker.repo")
        assumeTrue(repo != null, "set -Djetpacker.repo=<path> to run against a real repository")

        val root = Path.of(repo)
        val project = readGradleProject(root)
        assertTrue(project.sourceRoots.isNotEmpty(), "no source roots found in $repo")

        AnalysisApiIndexer(
            sourceRoots = project.sourceRoots,
            classpath = project.classpath,
            jdkHome = Path.of(System.getProperty("java.home")),
            repoRoot = root,
            testRoots = project.testRoots,
        ).use { indexer ->
            val index = indexer.index()
            val coverage = index.coverage
            val counts = index.edges.groupingBy { it.kind }.eachCount().toSortedMap()

            println(
                """
                |${project.sourceRoots.size} source roots, ${project.classpath.size} classpath entries
                |${index.symbols.size} symbols (${index.symbols.count { it.isTest }} in tests)
                |${coverage.callSites} call sites
                |  ${coverage.resolvedCallees} callees resolved (${percent(coverage.calleeRate)})
                |  ${coverage.attributedToCaller} attributed to a caller (${percent(coverage.callerRate)})
                |${index.edges.size} edges: $counts
                """.trimMargin(),
            )
            assertTrue(index.symbols.isNotEmpty(), "indexed no declarations at all in $repo")
            assertTrue(index.edges.isNotEmpty(), "resolved no edges at all in $repo")
        }
    }

    private fun percent(rate: Double): String = "%.1f%%".format(rate * 100)
}
