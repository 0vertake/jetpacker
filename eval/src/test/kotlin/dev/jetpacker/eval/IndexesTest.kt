package dev.jetpacker.eval

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Edge
import dev.jetpacker.core.index.EdgeKind
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Patching a cached index is only sound if it re-analyzes everything whose edges could have gone
 * stale. Getting this wrong does not fail a run — it produces an index that is quietly wrong about
 * a few edges, and a benchmark that scores it.
 */
class IndexesTest {
    @Test
    fun `re-analyzes a file whose callee no longer exists`() {
        val base = index(
            symbols = listOf(symbol("Rule.visit", "Rule.kt"), symbol("Caller.run", "Caller.kt")),
            edges = listOf(Edge("Caller.run", "Rule.visit", EdgeKind.CALLS)),
        )

        assertEquals(
            setOf("Caller.kt"),
            indexes().referrersToRemoved(base, changed = setOf("Rule.kt"), survivors = emptySet()),
        )
    }

    @Test
    fun `leaves alone a file whose callee survived the edit`() {
        val base = index(
            symbols = listOf(symbol("Rule.visit", "Rule.kt"), symbol("Caller.run", "Caller.kt")),
            edges = listOf(Edge("Caller.run", "Rule.visit", EdgeKind.CALLS)),
        )

        assertEquals(
            emptySet(),
            indexes().referrersToRemoved(base, setOf("Rule.kt"), survivors = setOf("Rule.visit")),
            "an edge to a declaration that is still there is still right, however much its body moved",
        )
    }

    @Test
    fun `does not name a changed file, which is re-analyzed anyway`() {
        val base = index(
            symbols = listOf(symbol("Rule.visit", "Rule.kt"), symbol("Rule.report", "Rule.kt")),
            edges = listOf(Edge("Rule.report", "Rule.visit", EdgeKind.CALLS)),
        )

        assertEquals(emptySet(), indexes().referrersToRemoved(base, setOf("Rule.kt"), emptySet()))
    }

    private companion object {
        fun indexes() = Indexes(createTempDirectory("jetpacker-repo"), createTempDirectory("jetpacker-cache"))

        fun index(symbols: List<Symbol>, edges: List<Edge>) =
            CodeIndex(symbols, edges, ResolutionCoverage(0, 0, 0))

        fun symbol(id: String, file: String) = Symbol(
            id = id,
            fqName = id,
            name = id.substringAfterLast('.'),
            kind = SymbolKind.FUNCTION,
            file = file,
            startLine = 1,
            endLine = 5,
            signature = "fun $id()",
            doc = null,
            tokens = 10,
            isTest = false,
        )
    }
}
