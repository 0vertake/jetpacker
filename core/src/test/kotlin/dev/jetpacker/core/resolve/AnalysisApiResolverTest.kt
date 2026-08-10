package dev.jetpacker.core.resolve

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 0 kill-test (docs/plan.md §7): can the Analysis API resolve a call target, the
 * callers of a function, and the implementations of an interface on real sources?
 *
 * The fixture is built from the cases where surface-level (tree-sitter) structure misleads:
 * an aliased import, a call through an injected interface, and multiple implementations.
 */
class AnalysisApiResolverTest {
    @Test
    fun `resolves a call target hidden behind an aliased import`() {
        val edges = resolver.callEdges()

        assertTrue(
            CallEdge("fixture.run", "fixture.GreetingService.welcome") in edges,
            "expected run() -> GreetingService.welcome despite the `as Service` alias, got $edges",
        )
    }

    @Test
    fun `finds callers of an interface method invoked through injection`() {
        val callers = resolver.callEdges()
            .filter { it.calleeFqName == "fixture.Greeter.greet" }
            .map { it.callerFqName }

        assertEquals(listOf("fixture.GreetingService.welcome"), callers)
    }

    @Test
    fun `finds implementations of an interface`() {
        assertEquals(
            listOf("fixture.CasualGreeter", "fixture.FormalGreeter"),
            resolver.implementationsOf("fixture.Greeter"),
        )
    }

    private companion object {
        /**
         * One session per JVM: building a standalone session registers application-level
         * IntelliJ services, so concurrent sessions in one process are not supported.
         */
        val resolver: CodeResolver by lazy {
            val root = Path.of(
                requireNotNull(AnalysisApiResolverTest::class.java.getResource("/fixtures/greeter")).toURI(),
            )
            AnalysisApiResolver(root)
        }
    }
}
