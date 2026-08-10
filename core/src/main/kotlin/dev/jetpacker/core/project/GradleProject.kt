package dev.jetpacker.core.project

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.nio.file.Path

/**
 * What the indexer needs to know about a project: where its code is, and what it compiles against.
 *
 * [testRoots] is a subset of [sourceRoots]. Keeping it saves guessing from path names later —
 * the build already knows which source sets are tests.
 */
data class GradleProject(
    val sourceRoots: List<Path>,
    val testRoots: List<Path>,
    val classpath: List<Path>,
)

/**
 * Reads [projectDir]'s structure through the Gradle Tooling API, flattening every module into one
 * set of source roots and one classpath.
 *
 * This configures the target build (resolving its dependencies, so it needs network on a cold
 * cache) but runs no tasks. Gradle is asked for its IDEA model because that is the one model
 * guaranteed to expose resolved dependency files without the target build cooperating.
 */
fun readGradleProject(projectDir: Path): GradleProject {
    val connector = GradleConnector.newConnector().forProjectDirectory(projectDir.toFile())
    connector.connect().use { connection ->
        val modules = connection.getModel(IdeaProject::class.java).modules

        val contentRoots = modules.flatMap { module -> module.contentRoots.orEmpty() }
        val mainRoots = contentRoots.flatMap { it.sourceDirectories.orEmpty() }.map { it.directory.toPath() }
        val testRoots = contentRoots.flatMap { it.testDirectories.orEmpty() }.map { it.directory.toPath() }

        val classpath = modules
            .flatMap { module -> module.dependencies.orEmpty() }
            .filterIsInstance<IdeaSingleEntryLibraryDependency>()
            .mapNotNull { it.file?.toPath() }

        return GradleProject(
            sourceRoots = (mainRoots + testRoots).distinct().sorted(),
            testRoots = testRoots.distinct().sorted(),
            classpath = classpath.distinct().sorted(),
        )
    }
}
