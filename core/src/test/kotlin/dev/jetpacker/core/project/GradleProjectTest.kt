package dev.jetpacker.core.project

import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extraction runs a real Gradle build of a two-module fixture, because the thing worth testing is
 * exactly what a synthetic stub would fake: that Gradle reports resolved dependency jars and every
 * module's source roots without the target build cooperating.
 */
class GradleProjectTest {
    @Test
    fun `reads source roots from every module`() {
        val roots = project.sourceRoots.map { it.parent.parent.parent.name to it.name }

        assertTrue("app" to "java" in roots, "expected app's source root, got ${project.sourceRoots}")
        assertTrue("lib" to "java" in roots, "expected lib's source root, got ${project.sourceRoots}")
    }

    @Test
    fun `separates test source roots from production ones`() {
        assertTrue(project.testRoots.isNotEmpty(), "expected the fixture's test roots, got none")
        assertTrue(
            project.testRoots.all { it in project.sourceRoots },
            "test roots must be a subset of source roots, or they will not be indexed",
        )
        assertTrue(
            project.testRoots.none { "main" in it.toString() },
            "a main source root leaked into testRoots: ${project.testRoots}",
        )
    }

    @Test
    fun `drops source roots the project asked IntelliJ to hide`() {
        assertTrue(
            project.sourceRoots.none { "generated-sources" in it.toString() },
            "the KDoc copy must not be indexed, got ${project.sourceRoots}",
        )
    }

    @Test
    fun `reads resolved external dependencies as jars`() {
        val guava = project.classpath.filter { it.name.startsWith("guava-") }

        assertEquals(1, guava.size, "expected exactly one resolved guava jar, got ${project.classpath}")
        assertTrue(guava.single().name.endsWith(".jar"))
    }

    private companion object {
        /** Configuring the fixture build is slow; do it once for the class. */
        val project: GradleProject by lazy {
            val dir = Path.of(
                requireNotNull(GradleProjectTest::class.java.getResource("/fixtures/gradle-project")).toURI(),
            )
            readGradleProject(dir)
        }
    }
}
