package dev.jetpacker.core.resolve

import dev.jetpacker.core.project.readGradleProject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Points the whole pipeline — Gradle model extraction, then resolution — at an arbitrary
 * repository on disk. This is the Phase 0 kill-test (docs/plan.md §7) in runnable form, and the
 * harness Phase 1 will use to index real repositories.
 *
 * Opt-in: it configures someone else's Gradle build, which needs network and minutes.
 *
 *     ./gradlew :core:test -Djetpacker.repo=/path/to/detekt --tests '*RealRepository*'
 */
class RealRepositoryResolutionTest {
    @Test
    fun `resolves calls in a real repository`() {
        val repo = System.getProperty("jetpacker.repo")
        assumeTrue(repo != null, "set -Djetpacker.repo=<path> to run against a real repository")

        val project = readGradleProject(Path.of(repo))
        assertTrue(project.sourceRoots.isNotEmpty(), "no source roots found in $repo")

        AnalysisApiResolver(
            sourceRoots = project.sourceRoots,
            classpath = project.classpath,
            jdkHome = Path.of(System.getProperty("java.home")),
        ).use { resolver ->
            val edges = resolver.callEdges()
            val crossFile = edges.filter { it.calleeFqName.substringBeforeLast('.') != it.callerFqName.substringBeforeLast('.') }

            println(
                "resolved ${edges.size} call edges (${crossFile.size} crossing declaration boundaries) " +
                    "from ${project.sourceRoots.size} source roots and ${project.classpath.size} classpath entries",
            )
            assertTrue(edges.isNotEmpty(), "resolved no calls at all in $repo")
        }
    }
}
