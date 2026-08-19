package dev.jetpacker.core.index

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Patching a cached index is only sound if it re-analyzes everything whose edges could have gone
 * stale. Getting this wrong does not fail a run — it produces an index that is quietly wrong about
 * a few edges, and a pack built from it.
 */
class IndexPatchTest {
    @Test
    fun `re-analyzes a file whose callee no longer exists`() {
        val base = index(
            symbols = listOf(symbol("Rule.visit", "Rule.kt"), symbol("Caller.run", "Caller.kt")),
            edges = listOf(Edge("Caller.run", "Rule.visit", EdgeKind.CALLS)),
        )

        assertEquals(
            setOf("Caller.kt"),
            IndexPatch.referrersToRemoved(base, changed = setOf("Rule.kt"), survivors = emptySet()),
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
            IndexPatch.referrersToRemoved(base, setOf("Rule.kt"), survivors = setOf("Rule.visit")),
            "an edge to a declaration that is still there is still right, however much its body moved",
        )
    }

    @Test
    fun `does not name a changed file, which is re-analyzed anyway`() {
        val base = index(
            symbols = listOf(symbol("Rule.visit", "Rule.kt"), symbol("Rule.report", "Rule.kt")),
            edges = listOf(Edge("Rule.report", "Rule.visit", EdgeKind.CALLS)),
        )

        assertEquals(emptySet(), IndexPatch.referrersToRemoved(base, setOf("Rule.kt"), emptySet()))
    }

    @Test
    fun `re-analyzes a file that called a name the edit just declared`() {
        val base = index(
            symbols = listOf(symbol("Caller.run", "Caller.kt")),
            errors = listOf(CompileError("Caller.kt", 1, "unresolved call `visit`")),
        )
        val fresh = index(symbols = listOf(symbol("Rule.visit", "Rule.kt")))

        assertEquals(setOf("Caller.kt"), IndexPatch.referrersToAdded(base, setOf("Rule.kt"), fresh))
    }

    @Test
    fun `leaves alone an unresolved call whose name was not added`() {
        val base = index(
            symbols = listOf(symbol("Caller.run", "Caller.kt")),
            errors = listOf(CompileError("Caller.kt", 1, "unresolved call `other`")),
        )
        val fresh = index(symbols = listOf(symbol("Rule.visit", "Rule.kt")))

        assertEquals(emptySet(), IndexPatch.referrersToAdded(base, setOf("Rule.kt"), fresh))
    }

    @Test
    fun `does not name a changed file as an added-callee referrer`() {
        val base = index(
            symbols = emptyList(),
            errors = listOf(CompileError("Rule.kt", 1, "unresolved call `visit`")),
        )
        val fresh = index(symbols = listOf(symbol("Rule.visit", "Rule.kt")))

        assertEquals(emptySet(), IndexPatch.referrersToAdded(base, setOf("Rule.kt"), fresh))
    }

    @Test
    fun `keeps coverage of files the patch did not touch`() {
        val base = index(
            symbols = listOf(symbol("A.a", "A.kt"), symbol("B.b", "B.kt")),
            coverageByFile = mapOf(
                "A.kt" to ResolutionCoverage(10, 8, 8),
                "B.kt" to ResolutionCoverage(4, 4, 4),
            ),
        )
        val fresh = index(
            symbols = listOf(symbol("B.b", "B.kt")),
            coverageByFile = mapOf("B.kt" to ResolutionCoverage(5, 5, 5)),
        )

        assertEquals(
            ResolutionCoverage(15, 13, 13),
            IndexPatch.merge(base, setOf("B.kt"), fresh).coverage,
        )
    }

    private companion object {
        fun index(
            symbols: List<Symbol>,
            edges: List<Edge> = emptyList(),
            errors: List<CompileError> = emptyList(),
            coverageByFile: Map<String, ResolutionCoverage> = emptyMap(),
        ) = CodeIndex(symbols, edges, ResolutionCoverage(0, 0, 0), errors, coverageByFile)

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
