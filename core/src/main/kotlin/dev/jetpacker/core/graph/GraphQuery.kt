package dev.jetpacker.core.graph

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.EdgeKind
import dev.jetpacker.core.index.Symbol

/** Interactive graph lookups for MCP query tools. */
object GraphQuery {
    fun resolve(index: CodeIndex, query: String): Symbol? =
        index.byId[query]
            ?: index.symbols.singleOrNull { it.fqName == query }
            ?: index.symbols.filter { it.name == query }.minByOrNull { it.id }

    fun callersOf(index: CodeIndex, query: String): List<Symbol> {
        val target = resolve(index, query) ?: return emptyList()
        return index.edges
            .asSequence()
            .filter { it.kind == EdgeKind.CALLS && it.to == target.id }
            .mapNotNull { index.byId[it.from] }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .toList()
    }

    fun implementationsOf(index: CodeIndex, query: String): List<Symbol> {
        val target = resolve(index, query) ?: return emptyList()
        return index.edges
            .asSequence()
            .filter { it.kind in IMPLEMENTATION_KINDS && it.to == target.id }
            .mapNotNull { index.byId[it.from] }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .toList()
    }

    private val IMPLEMENTATION_KINDS = setOf(EdgeKind.EXTENDS, EdgeKind.OVERRIDES)
}
