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
 *
 * Source directories the project asked IntelliJ to hide (`idea { excludeDirs }`) are dropped.
 */
fun readGradleProject(projectDir: Path): GradleProject {
    val connector = GradleConnector.newConnector().forProjectDirectory(projectDir.toFile())
    connector.connect().use { connection ->
        val modules = connection.getModel(IdeaProject::class.java).modules

        val contentRoots = modules.flatMap { module -> module.contentRoots.orEmpty() }
        val excluded = contentRoots.flatMap { it.excludeDirectories.orEmpty() }.map { it.toPath() }
        val mainRoots = withoutExcluded(
            contentRoots.flatMap { it.sourceDirectories.orEmpty() }.map { it.directory.toPath() },
            excluded,
        )
        val testRoots = withoutExcluded(
            contentRoots.flatMap { it.testDirectories.orEmpty() }.map { it.directory.toPath() },
            excluded,
        )

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

/**
 * Drops a source root that is an excluded directory, or that lives under one.
 * The IDEA model can still list a folder as a source directory after `excludeDirs`.
 */
internal fun withoutExcluded(roots: List<Path>, excluded: Collection<Path>): List<Path> {
    if (excluded.isEmpty()) return roots
    val blocked = excluded.map { it.toAbsolutePath().normalize() }
    return roots.filterNot { root ->
        val path = root.toAbsolutePath().normalize()
        blocked.any { path == it || path.startsWith(it) }
    }
}
