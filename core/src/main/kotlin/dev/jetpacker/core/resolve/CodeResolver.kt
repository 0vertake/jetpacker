package dev.jetpacker.core.resolve

/** A resolved call site: [callerFqName] invokes [calleeFqName]. */
data class CallEdge(val callerFqName: String, val calleeFqName: String)

/**
 * Compiler-grade structural queries the indexer builds its graph from.
 *
 * This is the swap point for the analysis host: the Analysis API standalone implementation is
 * the default, IntelliJ-headless is the documented fallback (docs/plan.md §7). Results are
 * ordered deterministically so packs built from them are byte-stable.
 */
interface CodeResolver : AutoCloseable {
    /** Every resolved call edge in the analyzed sources. */
    fun callEdges(): List<CallEdge>

    /** FQNs of classes and objects that directly extend or implement [fqName]. */
    fun implementationsOf(fqName: String): List<String>
}
