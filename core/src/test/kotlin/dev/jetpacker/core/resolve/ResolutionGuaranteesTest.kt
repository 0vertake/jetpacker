package dev.jetpacker.core.resolve

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the two properties the project's claims rest on: that resolution beats name matching
 * (docs/plan.md §5 "where PSI should visibly win"), and that its output is deterministic
 * (a hard requirement — packs must be byte-identical for a given repo state).
 */
class ResolutionGuaranteesTest {
    @Test
    fun `a member wins over an extension function of the same name`() {
        val callees = resolve(SHADOWING) { resolver ->
            resolver.callEdges()
                .filter { it.callerFqName == "fixture.shadowing.lookup" }
                .map { it.calleeFqName }
        }

        assertEquals(listOf("fixture.shadowing.Repository.find"), callees)
    }

    @Test
    fun `two independent sessions produce identical output`() {
        val first = resolve(GREETER) { it.callEdges() }
        val second = resolve(GREETER) { it.callEdges() }

        assertEquals(first, second)
        assertTrue(first.isNotEmpty(), "fixture resolved nothing, so equality proves nothing")
    }

    @Test
    fun `every call site in a self-contained fixture resolves`() {
        val coverage = resolve(GREETER) { it.coverage() }

        assertEquals(
            coverage.callSites,
            coverage.resolvedCallees,
            "unresolved calls in a fixture with no external dependencies: $coverage",
        )
    }

    /** Fresh session per call: shared state would make the determinism check vacuous. */
    private fun <T> resolve(fixture: String, query: (CodeResolver) -> T): T {
        val root = Path.of(
            requireNotNull(javaClass.getResource("/fixtures/$fixture")).toURI(),
        )
        return AnalysisApiResolver(listOf(root)).use(query)
    }

    private companion object {
        const val GREETER = "greeter"
        const val SHADOWING = "shadowing"
    }
}
