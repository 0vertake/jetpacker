package dev.jetpacker.core.graph

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Edge
import dev.jetpacker.core.index.EdgeKind
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphQueryTest {
    @Test
    fun `finds callers and implementations by simple name`() {
        val iface = sym("fixture.PaymentGateway", "PaymentGateway")
        val impl = sym("fixture.StripeAdapter", "StripeAdapter")
        val caller = sym("fixture.Checkout", "Checkout")
        val index = CodeIndex(
            symbols = listOf(iface, impl, caller),
            edges = listOf(
                Edge(caller.id, iface.id, EdgeKind.CALLS),
                Edge(impl.id, iface.id, EdgeKind.EXTENDS),
            ),
            coverage = ResolutionCoverage(2, 2, 2),
        )

        assertEquals(listOf(caller.id), GraphQuery.callersOf(index, "PaymentGateway").map { it.id })
        assertEquals(listOf(impl.id), GraphQuery.implementationsOf(index, "PaymentGateway").map { it.id })
    }

    private fun sym(id: String, name: String) = Symbol(
        id = id,
        fqName = id,
        name = name,
        kind = SymbolKind.CLASS,
        file = "src/$name.kt",
        startLine = 1,
        endLine = 5,
        signature = "class $name",
        doc = null,
        tokens = 10,
        isTest = false,
    )
}
