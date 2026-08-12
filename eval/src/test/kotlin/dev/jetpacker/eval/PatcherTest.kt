package dev.jetpacker.eval

import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `passes a reply back through the helper process`() {
        val patcher = CursorPatcher(Path.of("/usr/bin/python3"), helper("print('''```diff\n$DIFF\n```''')"))

        assertEquals(DIFF + "\n", patcher.patch("fix it", pack = null))
    }

    @Test
    fun `refuses to score an arm whose backend never ran`() {
        val patcher = CursorPatcher(
            Path.of("/usr/bin/python3"),
            helper("import sys\nprint('no key', file=sys.stderr)\nsys.exit(1)"),
        )

        val failure = assertFailsWith<IllegalStateException> { patcher.patch("fix it", pack = null) }

        assertTrue("no key" in failure.message!!, "the reason has to reach the operator, got ${failure.message}")
    }

    private fun read(reply: String) = diffIn(reply)

    /** A backend that answers however the test needs, without a model or a key behind it. */
    private fun helper(body: String) = createTempFile("helper", ".py").also {
        it.writeText("import sys\nsys.stdin.read()\n$body\n")
    }

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
