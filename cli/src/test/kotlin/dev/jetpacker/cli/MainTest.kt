package dev.jetpacker.cli

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `embed-seeds is a switch, not a --flag value`() {
        val options = parse(
            arrayOf("pack", "--repo", "/tmp/repo", "--task", "task.md", "--embed-seeds", "--budget", "2000"),
        )

        assertTrue(options.embedSeeds)
        assertEquals(Path.of("/tmp/repo"), options.repo)
        assertEquals("task.md", options.task)
        assertEquals(2000, options.budget)
    }

    @Test
    fun `omitting embed-seeds leaves the default finder`() {
        val options = parse(arrayOf("serve", "--repo", "/tmp/repo"))

        assertFalse(options.embedSeeds)
        assertEquals("serve", options.command)
        assertEquals(null, options.task)
    }

    @Test
    fun `gradle fingerprint changes when a build file is written`() {
        val root = createTempDirectory("jetpacker-gradle")
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"x\"")

        val first = gradleFingerprint(root)
        root.resolve("build.gradle.kts").writeText("// plugins")
        val second = gradleFingerprint(root)

        assertNotEquals(first, second)
        assertEquals(second, gradleFingerprint(root))
    }

    @Test
    fun `gradle fingerprint ignores files under build output`() {
        val root = createTempDirectory("jetpacker-gradle")
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"x\"")
        val first = gradleFingerprint(root)

        val generated = root.resolve("build").createDirectories()
        generated.resolve("build.gradle.kts").writeText("should not count")

        assertEquals(first, gradleFingerprint(root))
    }
}
