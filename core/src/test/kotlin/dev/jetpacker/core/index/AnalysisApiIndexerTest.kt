package dev.jetpacker.core.index

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Phase 0 kill-test (docs/plan.md §7) restated against the index: can resolution find a call
 * target, the callers of a function, and the implementations of an interface on real sources?
 *
 * The fixture is built from the cases where surface-level (tree-sitter) structure misleads:
 * an aliased import, a call through an injected interface, and multiple implementations.
 */
class AnalysisApiIndexerTest {
    @Test
    fun `resolves a call target hidden behind an aliased import`() {
        assertTrue(
            calls("fixture.run()").any { it.startsWith("fixture.GreetingService.welcome") },
            "expected run() -> GreetingService.welcome despite the `as Service` alias",
        )
    }

    @Test
    fun `finds callers of an interface method invoked through injection`() {
        val callers = index.incoming("fixture.Greeter.greet(kotlin.String)", EdgeKind.CALLS)

        assertEquals(listOf("fixture.GreetingService.welcome(kotlin.String)"), callers)
    }

    @Test
    fun `finds implementations of an interface`() {
        val implementations = index.edges
            .filter { it.kind == EdgeKind.EXTENDS && it.to == "fixture.Greeter" }
            .map { it.from }

        assertEquals(listOf("fixture.CasualGreeter", "fixture.FormalGreeter"), implementations)
    }

    @Test
    fun `records where a declaration lives and what it costs`() {
        val welcome = assertNotNull(index.byId["fixture.GreetingService.welcome(kotlin.String)"])

        assertEquals("GreetingService.kt", welcome.file.substringAfterLast('/'))
        assertEquals(SymbolKind.FUNCTION, welcome.kind)
        assertTrue(welcome.startLine > 0 && welcome.endLine >= welcome.startLine)
        assertTrue(welcome.tokens > 0, "token cost is what the packer budgets against")
    }

    @Test
    fun `nests members under the class that declares them`() {
        assertEquals(
            listOf("fixture.GreetingService.welcome(kotlin.String)"),
            index.outgoing("fixture.GreetingService", EdgeKind.CONTAINS).sorted(),
        )
    }

    @Test
    fun `instantiating a type is an edge to the type itself`() {
        assertTrue(
            "fixture.FormalGreeter" in index.outgoing("fixture.run()", EdgeKind.CALLS),
            "a primary constructor is not a separate node, so `FormalGreeter()` points at the class",
        )
    }

    @Test
    fun `links an override to the interface method it implements`() {
        assertEquals(
            listOf("fixture.Greeter.greet(kotlin.String)"),
            index.outgoing("fixture.FormalGreeter.greet(kotlin.String)", EdgeKind.OVERRIDES),
        )
    }

    private fun calls(callerId: String): List<String> = index.outgoing(callerId, EdgeKind.CALLS)

    private companion object {
        /** Building a session boots the IntelliJ platform, so share one across the class. */
        val index: CodeIndex by lazy {
            val root = Path.of(
                requireNotNull(AnalysisApiIndexerTest::class.java.getResource("/fixtures/greeter")).toURI(),
            )
            AnalysisApiIndexer(listOf(root)).use { it.index() }
        }
    }
}
