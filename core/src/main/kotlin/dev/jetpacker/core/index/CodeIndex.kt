package dev.jetpacker.core.index

import kotlinx.serialization.Serializable

/** What a [Symbol] is, which decides how it is packed and how edges to it are weighted. */
@Serializable
enum class SymbolKind { CLASS, INTERFACE, OBJECT, FUNCTION, CONSTRUCTOR, PROPERTY }

/**
 * A whole declaration — the unit of retrieval (docs/plan.md §3: never a character window).
 *
 * [id] is signature-derived rather than the FQN, so overloads are distinct nodes; keying on
 * FQNs would silently merge `format(Int)` and `format(String)` into one.
 *
 * The body text is deliberately absent: [tokens] is all the packer needs to make budget
 * decisions, and re-reading [file] at pack time keeps a whole-repo index small.
 */
@Serializable
data class Symbol(
    val id: String,
    val fqName: String,
    val name: String,
    val kind: SymbolKind,
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val signature: String,
    val doc: String?,
    val tokens: Int,
    val isTest: Boolean,
)

/**
 * Edge types, following LocAgent's finding that a small heterogeneous set is enough
 * (contain / invoke / inherit).
 *
 * [IMPORTS] and [REFERENCES_TYPE] are defaulted to zero weight in [EdgeWeights] so they do not
 * change existing behaviour; the ablation harness measures whether they earn a nonzero weight.
 * They are coarser than [CALLS]: an import is file-level and costs no resolution, a type reference
 * is declaration-level and does. Both add edges where CALLS already exists (a called function is
 * also a referenced type of its return), so ablating them on costs only — and ablating them off
 * tells you how much of the graph they uniquely contribute.
 */
@Serializable
enum class EdgeKind { CONTAINS, CALLS, EXTENDS, OVERRIDES, IMPORTS, REFERENCES_TYPE }

/**
 * A directed relation between two [Symbol.id]s.
 *
 * [to] may name a declaration outside the repository — a stdlib or dependency function has no
 * [Symbol] and can never be packed. Those edges are kept because "calls into Guava" is real
 * signal; consumers that only care about packable nodes filter on [CodeIndex.byId].
 */
@Serializable
data class Edge(val from: String, val to: String, val kind: EdgeKind)

/**
 * How much of the code the indexer understood.
 *
 * The counters mean different things: an unresolved callee is a limit of resolution, a call with
 * no enclosing declaration is a gap in extraction, and a [failedFiles] file is one the Analysis
 * API threw on and the index therefore knows nothing about. Without them a low-quality index is
 * indistinguishable from a small codebase.
 */
@Serializable
data class ResolutionCoverage(
    val callSites: Int,
    val resolvedCallees: Int,
    val attributedToCaller: Int,
    val failedFiles: Int = 0,
) {
    val calleeRate: Double get() = ratio(resolvedCallees)
    val callerRate: Double get() = ratio(attributedToCaller)

    private fun ratio(count: Int): Double = if (callSites == 0) 1.0 else count.toDouble() / callSites

    /** One line for a pack header: how much of the graph the indexer actually understood. */
    fun asPackLine(): String {
        val resolved = if (callSites == 0) {
            "no calls indexed"
        } else {
            "${(calleeRate * 100).toInt()}% of $callSites calls resolved"
        }
        return if (failedFiles == 0) resolved else "$resolved, $failedFiles files unanalyzable"
    }

    operator fun plus(other: ResolutionCoverage) = ResolutionCoverage(
        callSites + other.callSites,
        resolvedCallees + other.resolvedCallees,
        attributedToCaller + other.attributedToCaller,
        failedFiles + other.failedFiles,
    )
}

@Serializable
data class CompileError(val file: String, val line: Int, val message: String)

/**
 * The resolved code graph a pack is built from.
 *
 * Collections are sorted so that everything downstream inherits determinism for free, which the
 * pack format depends on (docs/plan.md §6).
 */
@Serializable
data class CodeIndex(
    val symbols: List<Symbol>,
    val edges: List<Edge>,
    val coverage: ResolutionCoverage,
    val errors: List<CompileError> = emptyList(),
    val coverageByFile: Map<String, ResolutionCoverage> = emptyMap(),
) {
    val byId: Map<String, Symbol> = symbols.associateBy { it.id }

    // ponytail: linear scans, fine for one-off queries. The ranker builds its own adjacency map
    // because it walks the whole graph.

    /** Edges of [kind] pointing at [id], e.g. callers of a function. */
    fun incoming(id: String, kind: EdgeKind): List<String> =
        edges.filter { it.to == id && it.kind == kind }.map { it.from }

    /** Edges of [kind] leaving [id], e.g. the supertypes a class declares. */
    fun outgoing(id: String, kind: EdgeKind): List<String> =
        edges.filter { it.from == id && it.kind == kind }.map { it.to }
}

/**
 * Builds a [CodeIndex] from a checkout.
 *
 * The seam that keeps Analysis API usage isolated: standalone AA is the default host and
 * IntelliJ-headless is the documented fallback (docs/plan.md §7), and neither should leak past
 * this interface.
 */
interface CodeIndexer : AutoCloseable {
    fun index(): CodeIndex
}
