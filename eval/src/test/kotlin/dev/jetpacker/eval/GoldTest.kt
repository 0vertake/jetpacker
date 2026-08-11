package dev.jetpacker.eval

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import dev.jetpacker.core.pack.Fidelity
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.PackItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ground truth decides every number the benchmark reports, so a quiet bug here would not fail a
 * test — it would just produce a plausible, wrong table.
 */
class GoldTest {
    @Test
    fun `reads pre-image line numbers from a diff`() {
        val diff = """
            diff --git a/src/Rule.kt b/src/Rule.kt
            --- a/src/Rule.kt
            +++ b/src/Rule.kt
            @@ -12,3 +12,4 @@
            -old
            +new
            @@ -40 +41 @@
            -x
            +y
        """.trimIndent()

        assertEquals(mapOf("src/Rule.kt" to setOf(12, 13, 14, 40)), changedLines(diff))
    }

    @Test
    fun `treats a pure insertion as touching the seam it was inserted into`() {
        val diff = """
            --- a/src/Rule.kt
            +++ b/src/Rule.kt
            @@ -12,0 +13,2 @@
            +added
            +lines
        """.trimIndent()

        assertEquals(
            mapOf("src/Rule.kt" to setOf(12, 13)),
            changedLines(diff),
            "an insertion has no pre-image span, but it still tells us which declaration grew",
        )
    }

    @Test
    fun `ignores a newly added file, which has no pre-image at all`() {
        val diff = """
            --- /dev/null
            +++ b/src/New.kt
            @@ -0,0 +1,3 @@
            +package a
        """.trimIndent()

        assertEquals(emptyMap(), changedLines(diff))
    }

    @Test
    fun `credits the innermost declaration a patch touched`() {
        val index = indexOf(
            symbol("Rule", 1, 20),
            symbol("Rule.visit", 5, 10),
            symbol("Rule.report", 12, 18),
        )
        val task = task(mapOf(FILE to setOf(7)))

        assertEquals(
            setOf("Rule.visit"),
            goldSymbols(index, task),
            "crediting the enclosing class too would let a retriever score by dumping whole files",
        )
    }

    @Test
    fun `credits every declaration when a patch spans several`() {
        val index = indexOf(symbol("Rule", 1, 20), symbol("Rule.visit", 5, 10), symbol("Rule.report", 12, 18))

        assertEquals(setOf("Rule.visit", "Rule.report"), goldSymbols(index, task(mapOf(FILE to setOf(7, 15)))))
    }

    @Test
    fun `scores a pack that found one of two gold declarations`() {
        val index = indexOf(symbol("Rule.visit", 5, 10), symbol("Rule.report", 12, 18))
        val pack = packOf(symbol("Rule.visit", 5, 10))

        val score = score(index, pack, setOf("Rule.visit", "Rule.report"))

        assertEquals(0.5, score.recall)
        assertEquals(1.0, score.precision)
        assertEquals(1.0, score.fileRecall, "both gold declarations live in the same file")
    }

    private companion object {
        const val FILE = "src/Rule.kt"

        fun symbol(id: String, start: Int, end: Int) = Symbol(
            id = id,
            fqName = id,
            name = id.substringAfterLast('.'),
            kind = SymbolKind.FUNCTION,
            file = FILE,
            startLine = start,
            endLine = end,
            signature = "fun $id()",
            doc = null,
            tokens = 10,
            isTest = false,
        )

        fun indexOf(vararg symbols: Symbol) =
            CodeIndex(symbols.toList(), emptyList(), ResolutionCoverage(0, 0, 0))

        fun packOf(vararg symbols: Symbol) = Pack(
            items = symbols.map { PackItem(it, Fidelity.FULL, "seed", "body", it.tokens) },
            tokens = symbols.sumOf { it.tokens },
            budget = 4000,
        )

        fun task(changed: Map<String, Set<Int>>) = Task("abc", "text", "abc^", changed)
    }
}
