package dev.jetpacker.core.resolve

/** A resolved call site: [callerFqName] invokes [calleeFqName]. */
data class CallEdge(val callerFqName: String, val calleeFqName: String)

/**
 * How much of the code a resolver actually understood.
 *
 * Edges are dropped silently for two different reasons, and they mean different things:
 * an unresolved callee is a resolution failure, while a call the resolver cannot attribute to
 * an enclosing named function is a gap in *this* implementation's caller attribution.
 * Without these counts, a low-quality index is indistinguishable from a small codebase.
 */
data class ResolutionCoverage(
    val callSites: Int,
    val resolvedCallees: Int,
    val attributedToCaller: Int,
) {
    val calleeRate: Double get() = ratio(resolvedCallees)
    val callerRate: Double get() = ratio(attributedToCaller)

    private fun ratio(count: Int): Double = if (callSites == 0) 1.0 else count.toDouble() / callSites
}

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

    /** What share of call sites [callEdges] managed to account for. */
    fun coverage(): ResolutionCoverage
}
