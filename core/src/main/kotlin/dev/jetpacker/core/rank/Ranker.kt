package dev.jetpacker.core.rank

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Edge
import dev.jetpacker.core.index.EdgeKind
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.seed.Seed
import kotlin.math.abs

/** A symbol worth packing, with the provenance line the pack shows for it (docs/plan.md §6). */
data class Ranked(val symbol: Symbol, val score: Double, val why: String)

/**
 * How far relevance flows along each relation, per direction.
 *
 * Every value is a starting guess; these are exactly the knobs the ablation table exists to
 * settle (docs/plan.md §5), so they are data rather than constants buried in the walk.
 *
 * Reverse weights are not smaller than forward ones by accident. Given a relevant interface
 * method, its *implementations* are usually the code that needs changing — that is the
 * Spring-style injection case where surface-name tooling picks wrong.
 */
data class EdgeWeights(
    val calls: Double = 1.0,
    val calledBy: Double = 1.0,
    val contains: Double = 0.5,
    val containedBy: Double = 1.0,
    val extends: Double = 0.8,
    val extendedBy: Double = 1.0,
    val overrides: Double = 0.8,
    val overriddenBy: Double = 1.0,
    val sameFile: Double = 1.0,
) {
    /** Turns off structural expansion, leaving seeds only — the headline ablation's OFF arm. */
    fun none(): EdgeWeights = EdgeWeights(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
}

/**
 * Spreads seed relevance across the graph with personalized PageRank.
 *
 * PPR rather than the bounded depth-2 traversal of §4 because it subsumes it: distance decay
 * falls out of the damping factor, and a symbol reached by many weak paths correctly outranks one
 * reached by a single weak path — which a depth cutoff cannot express.
 */
class Ranker(
    private val index: CodeIndex,
    private val weights: EdgeWeights = EdgeWeights(),
) {
    private val ids: List<String> = index.symbols.map { it.id }
    private val position: Map<String, Int> = ids.withIndex().associate { (at, id) -> id to at }

    /**
     * One extra node per file, so declarations that merely sit together are weakly connected.
     *
     * Sibling top-level functions have no edge between them, yet the benchmark showed the right
     * *file* being found far more often than the right declaration — the missing relation was
     * plain co-location. A star through a file node keeps this linear; connecting every pair
     * directly would be quadratic in the size of a file.
     */
    private val fileNode: Map<String, Int> = index.symbols.map { it.file }.distinct().sorted()
        .withIndex().associate { (at, file) -> file to ids.size + at }

    private val nodeCount = ids.size + fileNode.size
    private val links: List<List<Link>> = buildAdjacency()

    /** [reason] is fixed once the link exists, since both of its endpoints are. */
    private class Link(val target: Int, val weight: Double, val reason: String)

    fun rank(seeds: List<Seed>): List<Ranked> {
        val restart = personalization(seeds) ?: return emptyList()
        val scores = propagate(restart)
        val reasons = explain(seeds)

        return index.symbols.indices
            .filter { scores[it] > 0.0 }
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { ids[it] })
            .map { Ranked(index.symbols[it], scores[it], reasons[it] ?: "related") }
    }

    /** Seed scores as a probability distribution; null when no seed exists in this index. */
    private fun personalization(seeds: List<Seed>): DoubleArray? {
        val restart = DoubleArray(nodeCount)
        var total = 0.0
        for (seed in seeds) {
            val at = position[seed.id] ?: continue
            restart[at] += seed.score
            total += seed.score
        }
        if (total == 0.0) return null
        for (at in restart.indices) restart[at] /= total
        return restart
    }

    private fun propagate(restart: DoubleArray): DoubleArray {
        var scores = restart.copyOf()
        repeat(MAX_ITERATIONS) {
            val next = DoubleArray(nodeCount)
            var leaked = 0.0
            for (from in scores.indices) {
                val mass = scores[from]
                if (mass == 0.0) continue
                val outgoing = links[from]
                val total = outgoing.sumOf { it.weight }
                // A symbol with no outgoing weight would otherwise swallow its mass; hand it back
                // to the seeds so the walk stays a distribution.
                if (total == 0.0) {
                    leaked += mass
                    continue
                }
                for (link in outgoing) next[link.target] += DAMPING * mass * link.weight / total
            }
            for (at in next.indices) next[at] += (1 - DAMPING + DAMPING * leaked) * restart[at]

            val delta = next.indices.sumOf { abs(next[it] - scores[it]) }
            scores = next
            if (delta < TOLERANCE) return scores
        }
        return scores
    }

    /**
     * Why a symbol is in the pack, taken from the shortest path back to a seed.
     *
     * PageRank scores cannot answer this — mass arrives from everywhere at once — so provenance
     * comes from a breadth-first walk over the same adjacency, which by construction reports the
     * most direct relationship rather than the strongest one.
     */
    private fun explain(seeds: List<Seed>): Array<String?> {
        val reasons = arrayOfNulls<String>(nodeCount)
        var frontier = seeds.mapNotNull { position[it.id] }
        frontier.forEach { reasons[it] = "seed" }

        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Int>()
            for (from in frontier) {
                for (link in links[from]) {
                    if (link.weight == 0.0 || reasons[link.target] != null) continue
                    reasons[link.target] = link.reason
                    next += link.target
                }
            }
            frontier = next
        }
        return reasons
    }

    private fun buildAdjacency(): List<List<Link>> {
        val adjacency = List(nodeCount) { mutableListOf<Link>() }
        index.symbols.forEachIndexed { at, symbol ->
            val file = fileNode.getValue(symbol.file)
            val name = symbol.file.substringAfterLast('/')
            adjacency[at] += Link(file, weights.sameFile, "in:$name")
            adjacency[file] += Link(at, weights.sameFile, "same-file:$name")
        }
        for (edge in index.edges) {
            val from = position[edge.from] ?: continue
            // Calls into the stdlib and dependencies resolve but are not packable declarations.
            val to = position[edge.to] ?: continue
            val (forward, backward) = weightsFor(edge)
            adjacency[from] += Link(to, forward, forwardReason(edge.kind, index.symbols[from]))
            adjacency[to] += Link(from, backward, backwardReason(edge.kind, index.symbols[to], index.symbols[from]))
        }
        return adjacency
    }

    private fun weightsFor(edge: Edge): Pair<Double, Double> = when (edge.kind) {
        EdgeKind.CALLS -> weights.calls to weights.calledBy
        EdgeKind.CONTAINS -> weights.contains to weights.containedBy
        EdgeKind.EXTENDS -> weights.extends to weights.extendedBy
        EdgeKind.OVERRIDES -> weights.overrides to weights.overriddenBy
    }

    private fun forwardReason(kind: EdgeKind, from: Symbol): String = when (kind) {
        EdgeKind.CALLS -> "called-by:${from.name}"
        EdgeKind.CONTAINS -> "member-of:${from.name}"
        EdgeKind.EXTENDS -> "supertype-of:${from.name}"
        EdgeKind.OVERRIDES -> "overridden-by:${from.name}"
    }

    /** [target] is the symbol this link leads to; [origin] is the one it leads back from. */
    private fun backwardReason(kind: EdgeKind, origin: Symbol, target: Symbol): String = when (kind) {
        EdgeKind.CALLS -> if (target.isTest) "test-of:${origin.name}" else "caller-of:${origin.name}"
        EdgeKind.CONTAINS -> "declares:${origin.name}"
        EdgeKind.EXTENDS, EdgeKind.OVERRIDES -> "impl-of:${origin.name}"
    }

    private companion object {
        const val DAMPING = 0.85
        const val MAX_ITERATIONS = 40
        const val TOLERANCE = 1e-8
    }
}
