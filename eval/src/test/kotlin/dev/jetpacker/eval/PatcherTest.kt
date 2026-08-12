package dev.jetpacker.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A model that is asked for a bare diff does not reliably send one, and every way it wraps the
 * answer looks the same to the harness: no patch, arm scores zero. That failure is indistinguishable
 * from a pack that was useless, so the reading of a reply is pinned here.
 */
class PatcherTest {
    @Test
    fun `takes a bare diff as it stands`() {
        assertEquals(DIFF + "\n", read(DIFF))
    }

    @Test
    fun `unwraps a fenced diff`() {
        for (fence in listOf("```diff", "```patch", "```")) {
            assertEquals(DIFF + "\n", read("$fence\n$DIFF\n```"), "for $fence")
        }
    }

    @Test
    fun `drops the sentence a model puts in front of the diff`() {
        val reply = "Here is the fix:\n\n$DIFF"

        assertTrue(read(reply).startsWith("diff --git"), "git apply reads the first line, got ${read(reply)}")
    }

    @Test
    fun `reports prose with no diff in it as no patch at all`() {
        val excuses = listOf(
            "I need to see the implementation of UnnecessaryInnerClass before I can fix this.",
            "",
            "```kotlin\nfun visit() = Unit\n```",
        )

        for (excuse in excuses) {
            assertEquals("", read(excuse), "for ${excuse.take(30)}")
        }
    }

    @Test
    fun `ends with a newline, which git apply requires`() {
        assertTrue(read(DIFF.trimEnd()).endsWith("\n"))
    }

    private fun read(reply: String) = diffIn(reply)

    private companion object {
        val DIFF = """
            diff --git a/src/Rule.kt b/src/Rule.kt
            --- a/src/Rule.kt
            +++ b/src/Rule.kt
            @@ -1,3 +1,3 @@
             class Rule {
            -    fun visit() = Unit
            +    fun visit() = report()
             }
        """.trimIndent()
    }
}
