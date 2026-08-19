package dev.jetpacker.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
