package dev.jetpacker.core.rank

import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fusion is the single change that moved the benchmark most (+6 points on detekt), so its
 * behaviour is pinned here rather than left to be inferred from an end-to-end score.
 */
class FusionTest {
    @Test
    fun `a symbol both rankings agree on outranks the top of either`() {
        val fused = fuse(listOf(ranking("a", "shared"), ranking("b", "shared")))

        assertEquals("shared", fused.first().symbol.id, "got ${fused.map { it.symbol.id }}")
    }

    @Test
    fun `keeps what only one ranking found`() {
        val fused = fuse(listOf(ranking("a"), ranking("b"))).map { it.symbol.id }

        assertEquals(setOf("a", "b"), fused.toSet(), "fusion is a union, not an intersection")
    }

    @Test
    fun `the first ranking to explain a symbol keeps its provenance`() {
        val graph = listOf(ranked("x", "impl-of:Gateway"))
        val search = listOf(ranked("x", "matches:task"))

        assertEquals(
            "impl-of:Gateway",
            fuse(listOf(graph, search)).single().why,
            "a structural reason says more than 'the words matched'",
        )
    }

    @Test
    fun `a smaller k trusts the head of a ranking over agreement far down it`() {
        // "deep" is twentieth in both rankings; "head" is first in one and absent from the other.
        val filler = List(19) { "filler$it" }
        val one = ranking(*(listOf("head") + filler.drop(1) + "deep").toTypedArray())
        val two = ranking(*(filler + "deep").toTypedArray())

        val peaked = fuse(listOf(one, two), k = 1).map { it.symbol.id }
        val flat = fuse(listOf(one, two), k = 1000).map { it.symbol.id }

        assertTrue(peaked.indexOf("head") < peaked.indexOf("deep"), "k=1 should back the head, got $peaked")
        assertTrue(
            flat.indexOf("deep") < flat.indexOf("head"),
            "a large k flattens the ranks until two deep mentions outweigh one top hit, got $flat",
        )
    }

    @Test
    fun `ties break on id, so the order is the same every run`() {
        val one = fuse(listOf(ranking("b", "a")))
        val two = fuse(listOf(ranking("b", "a")))

        assertEquals(one.map { it.symbol.id }, two.map { it.symbol.id })
    }

    private fun ranking(vararg ids: String): List<Ranked> = ids.map { ranked(it, "why:$it") }

    private fun ranked(id: String, why: String) = Ranked(symbol(id), 1.0, why)

    private fun symbol(id: String) = Symbol(
        id = id,
        fqName = id,
        name = id,
        kind = SymbolKind.FUNCTION,
        file = "Fixture.kt",
        startLine = 1,
        endLine = 1,
        signature = "fun $id()",
        doc = null,
        tokens = 4,
        isTest = false,
    )
}
