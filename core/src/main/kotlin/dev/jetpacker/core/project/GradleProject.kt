package dev.jetpacker.core.project

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.nio.file.Path

/** What the resolver needs to know about a project: where its code is, and what it compiles against. */
data class GradleProject(val sourceRoots: List<Path>, val classpath: List<Path>)

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

        val sourceRoots = modules
            .flatMap { module -> module.contentRoots.orEmpty() }
            .flatMap { root -> root.sourceDirectories.orEmpty() + root.testDirectories.orEmpty() }
            .map { it.directory.toPath() }

        val classpath = modules
            .flatMap { module -> module.dependencies.orEmpty() }
            .filterIsInstance<IdeaSingleEntryLibraryDependency>()
            .mapNotNull { it.file?.toPath() }

        return GradleProject(
            sourceRoots = sourceRoots.distinct().sorted(),
            classpath = classpath.distinct().sorted(),
        )
    }
}
