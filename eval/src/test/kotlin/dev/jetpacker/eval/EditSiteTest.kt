package dev.jetpacker.eval

import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import dev.jetpacker.core.pack.Fidelity
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.PackItem
import kotlin.test.Test
import kotlin.test.assertEquals

class EditSiteTest {
    @Test
    fun `strict edit-site recall needs full bodies on changed lines`() {
        val task = task(mapOf(FILE to setOf(7)))
        val gold = setOf("Rule.visit")
        val stubOnly = packOf(symbol("Rule.visit", 5, 10), Fidelity.STUB)
        val full = packOf(symbol("Rule.visit", 5, 10), Fidelity.FULL)

        assertEquals(1.0, score(indexOf(symbol("Rule.visit", 5, 10)), stubOnly, gold, task).recall)
        assertEquals(0.0, editSiteRecall(task, stubOnly, strict = true))
        assertEquals(1.0 / 1.0, editSiteRecall(task, full, strict = true))
    }

    @Test
    fun `loose edit-site recall credits a stub start line`() {
        val task = task(mapOf(FILE to setOf(7)))
        val stubOnly = packOf(symbol("Rule.visit", 5, 10), Fidelity.STUB)

        assertEquals(0.0, editSiteRecall(task, stubOnly, strict = true))
        assertEquals(0.0, editSiteRecall(task, stubOnly, strict = false), "line 7 is not the stub anchor")
    }

    @Test
    fun `chunk windows count toward edit-site recall`() {
        val task = task(mapOf(FILE to setOf(12, 13)))
        val pack = Pack(
            items = listOf(
                PackItem(
                    symbol("Rule.visit", 5, 10),
                    Fidelity.FULL,
                    "chunk:$FILE:10:20",
                    "sig",
                    0,
                ),
            ),
            tokens = 100,
            budget = 4000,
        )

        assertEquals(2.0 / 2.0, editSiteRecall(task, pack, strict = true))
    }

    @Test
    fun `symbol recall can be one while edit-site recall is partial`() {
        val task = task(mapOf(FILE to setOf(7, 8, 9)))
        val gold = setOf("Rule.visit")
        // Body covers only the first changed line inside the method span.
        val pack = Pack(
            items = listOf(
                PackItem(
                    symbol("Rule.visit", 5, 10),
                    Fidelity.FULL,
                    "seed",
                    "partial",
                    10,
                ),
            ),
            tokens = 10,
            budget = 4000,
        )
        // FULL fidelity claims the whole declaration span for edit-site purposes.
        assertEquals(1.0, score(indexOf(symbol("Rule.visit", 5, 10)), pack, gold, task).recall)
        assertEquals(1.0, editSiteRecall(task, pack, strict = true))
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
            dev.jetpacker.core.index.CodeIndex(symbols.toList(), emptyList(), dev.jetpacker.core.index.ResolutionCoverage(0, 0, 0))

        fun packOf(symbol: Symbol, fidelity: Fidelity) = Pack(
            items = listOf(PackItem(symbol, fidelity, "seed", "body", symbol.tokens)),
            tokens = symbol.tokens,
            budget = 4000,
        )

        fun task(changed: Map<String, Set<Int>>) = Task("id", "text", "abc^", changed)
    }
}
