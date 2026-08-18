package dev.jetpacker.core.project

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ExcludedRootsTest {
    private val core = Path.of("/repo/core")
    private val handwritten = core.resolve("src/main/kotlin")
    private val generated = core.resolve("generated-sources")
    private val generatedKotlin = generated.resolve("src/main/kotlin")
    private val tests = core.resolve("src/test/kotlin")

    @Test
    fun `keeps every root when nothing is excluded`() {
        assertEquals(
            listOf(handwritten, generatedKotlin),
            withoutExcluded(listOf(handwritten, generatedKotlin), emptyList()),
        )
    }

    @Test
    fun `drops a source root that is the excluded directory`() {
        assertEquals(
            listOf(handwritten),
            withoutExcluded(listOf(handwritten, generated), listOf(generated)),
        )
    }

    @Test
    fun `drops a source root nested under an excluded directory`() {
        // dataframe: excludeDirs is `generated-sources`, the IDEA source dir is
        // `generated-sources/src/main/kotlin`.
        assertEquals(
            listOf(handwritten, tests),
            withoutExcluded(listOf(handwritten, generatedKotlin, tests), listOf(generated)),
        )
    }

    @Test
    fun `does not drop a sibling of the excluded directory`() {
        val other = core.resolve("src")
        assertEquals(
            listOf(other),
            withoutExcluded(listOf(other, generatedKotlin), listOf(generated)),
        )
    }
}
