package dev.jetpacker.baselines

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chunk baseline decides the headline comparison, so the rules that make it fair are pinned
 * here: a window is credited for what it contains whole, and it pays for every line it shows.
 */
class ChunkRetrieverTest {
    @Test
    fun `credits a declaration a window contains whole`() {
        val packed = retriever(windowLines = 10).pack("alpha", budget = 4000).items.map { it.symbol.id }

        assertTrue("alpha" in packed, "lines 1..4 sit inside the first window, got $packed")
    }

    @Test
    fun `refuses credit for a declaration a window cuts in half`() {
        // `straddler` spans lines 8..12, so no five-line window holds all of it.
        val packed = retriever(windowLines = 5).pack("straddler", budget = 4000).items.map { it.symbol.id }

        assertTrue(
            "straddler" !in packed,
            "half a declaration is not retrieval; crediting it would reward cutting things in half",
        )
    }

    @Test
    fun `pays for the whole window, not for the declarations inside it`() {
        val pack = retriever(windowLines = 10).pack("alpha", budget = 4000)

        assertTrue(pack.tokens > 0, "showing lines costs tokens")
        assertTrue(
            pack.tokens >= pack.items.size,
            "a window is charged once for its text: ${pack.tokens} tokens for ${pack.items.size} items",
        )
    }

    @Test
    fun `stops at the budget`() {
        for (budget in listOf(20, 60, 200)) {
            val pack = retriever(windowLines = 5).pack("alpha beta straddler", budget)

            assertTrue(pack.tokens <= budget, "spent ${pack.tokens} of $budget")
        }
    }

    @Test
    fun `ranks the same way every run`() {
        val one = retriever(windowLines = 5).pack("alpha", budget = 200)
        val two = retriever(windowLines = 5).pack("alpha", budget = 200)

        assertEquals(one.items.map { it.symbol.id }, two.items.map { it.symbol.id })
    }

    private fun retriever(windowLines: Int) =
        ChunkRetriever(index, root, windowLines = windowLines)

    private companion object {
        val root: Path by lazy {
            createTempDirectory("jetpacker-chunk").also { directory ->
                directory.resolve("src").createDirectories()
                // Each declaration's name appears on its own lines and nowhere else, so a keyword
                // ranking can only reach it through the window that holds those lines.
                val named = mapOf(1..4 to "alpha", 8..12 to "straddler", 16..18 to "beta")
                val lines = (1..20).map { line ->
                    val name = named.entries.firstOrNull { line in it.key }?.value
                    if (name == null) "// filler" else "fun $name() = $line"
                }
                directory.resolve(FILE).writeText(lines.joinToString("\n") + "\n")
            }
        }

        const val FILE = "src/Fixture.kt"

        val index: CodeIndex by lazy {
            CodeIndex(
                symbols = listOf(
                    symbol("alpha", 1, 4),
                    symbol("straddler", 8, 12),
                    symbol("beta", 16, 18),
                ),
                edges = emptyList(),
                coverage = ResolutionCoverage(0, 0, 0),
            )
        }

        fun symbol(id: String, startLine: Int, endLine: Int) = Symbol(
            id = id,
            fqName = id,
            name = id,
            kind = SymbolKind.FUNCTION,
            file = FILE,
            startLine = startLine,
            endLine = endLine,
            signature = "fun $id()",
            doc = null,
            tokens = 4,
            isTest = false,
        )
    }
}
