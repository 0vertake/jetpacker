package dev.jetpacker.core.index

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
        val callees = index(SHADOWING)
            .outgoing("fixture.shadowing.lookup(fixture.shadowing.Repository)", EdgeKind.CALLS)

        assertEquals(listOf("fixture.shadowing.Repository.find(kotlin.String)"), callees)
    }

    @Test
    fun `an overload keeps its own identity`() {
        val callees = index(OVERLOADS)
            .edges
            .filter { it.kind == EdgeKind.CALLS && it.from.startsWith("fixture.overloads.use") }
            .map { it.to }
            .sorted()

        assertEquals(
            listOf(
                "fixture.overloads.Formatter.format(kotlin.Int)",
                "fixture.overloads.Formatter.format(kotlin.String)",
            ),
            callees,
            "overloads must be distinct nodes, or the graph cannot express which one is called",
        )
    }

    @Test
    fun `two independent sessions produce identical output`() {
        val first = index(GREETER)
        val second = index(GREETER)

        assertEquals(first.symbols, second.symbols)
        assertEquals(first.edges, second.edges)
        assertTrue(first.edges.isNotEmpty(), "fixture resolved nothing, so equality proves nothing")
    }

    @Test
    fun `every call site in a self-contained fixture resolves and is attributed`() {
        val coverage = index(GREETER).coverage

        assertEquals(coverage.callSites, coverage.resolvedCallees, "unresolved calls: $coverage")
        assertEquals(coverage.callSites, coverage.attributedToCaller, "unattributed calls: $coverage")
    }

    @Test
    fun `attributes a call in a property initializer to the property`() {
        val callers = index(INITIALIZERS).incoming("fixture.initializers.build()", EdgeKind.CALLS)

        assertEquals(
            listOf("fixture.initializers.Holder.value"),
            callers,
            "calls outside a named function are real edges (docs/plan.md §7)",
        )
    }

    /** Fresh session per call: shared state would make the determinism check vacuous. */
    private fun index(fixture: String): CodeIndex {
        val root = Path.of(
            requireNotNull(javaClass.getResource("/fixtures/$fixture")).toURI(),
        )
        return AnalysisApiIndexer(listOf(root)).use { it.index() }
    }

    private companion object {
        const val GREETER = "greeter"
        const val SHADOWING = "shadowing"
        const val OVERLOADS = "overloads"
        const val INITIALIZERS = "initializers"
    }
}
