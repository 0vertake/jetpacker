package dev.jetpacker.eval

import kotlin.test.Test
import kotlin.test.assertEquals

class PatchTest {
    @Test
    fun `normalizes Windows line endings`() {
        val patch = "diff --git a/F.kt b/F.kt\r\n--- a/F.kt\r\n+++ b/F.kt\r\n"

        assertEquals("diff --git a/F.kt b/F.kt\n--- a/F.kt\n+++ b/F.kt\n", Patch.normalize(patch))
    }

    @Test
    fun `adds a trailing newline`() {
        assertEquals("diff --git a/F.kt b/F.kt\n", Patch.normalize("diff --git a/F.kt b/F.kt"))
    }
}
