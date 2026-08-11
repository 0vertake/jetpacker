package dev.jetpacker.eval

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Harbor directory is an external format, so a change to it would otherwise show up as a
 * benchmark that quietly ran on fewer tasks.
 */
class HarborTasksTest {
    @Test
    fun `reads the issue, the base commit and the gold lines of a task`() {
        val task = harborTasks(suite, "detekt").single()

        assertEquals("detekt_detekt-4738", task.id)
        assertEquals("bd922af416fb3e28e4d5220ac693e1ae1a721bee", task.baseCommit)
        assertTrue(task.text.startsWith("# Implement the issue"), "got ${task.text}")
        assertEquals(mapOf("rules/src/main/kotlin/Rule.kt" to setOf(12, 13)), task.changedLines)
    }

    @Test
    fun `skips tasks belonging to another repository`() {
        assertEquals(emptyList(), harborTasks(suite, "ktlint"))
    }

    @Test
    fun `skips a task whose fix only touched tests`() {
        assertEquals(
            emptyList(),
            harborTasks(suite, "shadow"),
            "a fix with nothing but test edits has no declaration a retriever was meant to find",
        )
    }

    private companion object {
        /** One task per case: a usable one, and one whose fix only edits tests. */
        val suite: Path by lazy {
            createTempDirectory("jetpacker-harbor").also { root ->
                task(
                    root.resolve("detekt_detekt-4738"),
                    repository = "detekt",
                    fix = diff("rules/src/main/kotlin/Rule.kt"),
                )
                task(
                    root.resolve("GradleUp_shadow-1"),
                    repository = "shadow",
                    fix = diff("src/test/kotlin/RuleSpec.kt"),
                )
            }
        }

        fun task(directory: Path, repository: String, fix: String) {
            directory.resolve("environment").createDirectories()
            directory.resolve("solution").createDirectories()
            directory.resolve("task.toml").writeText(
                """
                [metadata.source]
                repository_owner = "detekt"
                repository_name = "$repository"
                """.trimIndent(),
            )
            directory.resolve("instruction.md").writeText("# Implement the issue(s) in $repository:\n")
            directory.resolve("environment/prepare.sh").writeText(
                "cd /home/x\ngit reset --hard\ngit checkout bd922af416fb3e28e4d5220ac693e1ae1a721bee\n",
            )
            directory.resolve("solution/fix.patch").writeText(fix)
        }

        fun diff(file: String) = "--- a/$file\n+++ b/$file\n@@ -12,2 +12,2 @@\n-old\n+new\n"
    }
}
