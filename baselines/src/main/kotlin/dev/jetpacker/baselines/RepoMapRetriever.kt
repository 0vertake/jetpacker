package dev.jetpacker.baselines

import dev.jetpacker.core.Retriever
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.Packer
import dev.jetpacker.core.rank.Ranked
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.sqrt

/**
 * Aider's repo map: a file graph built from bare identifier names, ranked by PageRank.
 *
 * This is the baseline that decides whether resolution is worth its cost, so the algorithm is
 * ported from `aider/repomap.py` rather than approximated — the same edge multipliers, the same
 * `sqrt` damping of reference counts, the same personalization, and the same distribution of a
 * file's rank across its out-edges to score individual definitions.
 *
 * Two deliberate departures, both of which make the baseline stronger rather than weaker:
 *
 * - Definitions come from the PSI index instead of tree-sitter tags, so its list of declarations
 *   is exactly right where tree-sitter's is approximate. What it still does not get is what tags
 *   cannot express: which `format` a call meant, which class implements an interface.
 * - References are identifier occurrences in the file text. Tree-sitter's reference queries are
 *   narrower — this counts names in comments and strings too — but a name is a name either way,
 *   and being generous here is the point.
 */
class RepoMapRetriever(
    private val index: CodeIndex,
    private val repoRoot: Path,
    override val name: String = "repo-map",
) : Retriever {
    private val definitions: Map<String, List<Symbol>> = index.symbols.groupBy { it.name }
    private val files: List<String> = index.symbols.map { it.file }.distinct().sorted()
    private val position: Map<String, Int> = files.withIndex().associate { (at, file) -> file to at }

    /** ident → file → how many times that file names it. Built once; it does not depend on a task. */
    private val references: Map<String, Map<String, Int>> = countReferences()

    override fun pack(task: String, budget: Int): Pack {
        val mentioned = IDENTIFIER.findAll(task).map { it.value }.toSet()
        val edges = edges(mentioned)
        val rank = pageRank(edges, personalization(mentioned))

        // A file's rank flows out along its edges, so a definition is worth what the files that
        // name it are worth.
        val outWeight = DoubleArray(files.size)
        edges.forEach { outWeight[it.from] += it.weight }

        val scores = HashMap<Pair<String, String>, Double>()
        for (edge in edges) {
            if (outWeight[edge.from] == 0.0) continue
            val share = rank[edge.from] * edge.weight / outWeight[edge.from]
            scores.merge(files[edge.to] to edge.ident, share, Double::plus)
        }

        val ranked = scores.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Double>> { it.value }.thenBy { it.key.toString() })
            .flatMap { (key, score) ->
                val (file, ident) = key
                definitions[ident].orEmpty().filter { it.file == file }.map { Ranked(it, score, "repo-map") }
            }

        // Aider's map is signature lines, so the baseline spends its budget the same way. Every
        // other packing policy is the engine's, including the cap on test code: a baseline that
        // differed there would be losing to a packer setting rather than to a ranking.
        return Packer(index, repoRoot, budget, fullTierShare = 0.0).pack(ranked)
    }

    private class Edge(val from: Int, val to: Int, val ident: String, val weight: Double)

    private fun edges(mentioned: Set<String>): List<Edge> = buildList {
        for (ident in definitions.keys.sorted()) {
            val definers = definitions.getValue(ident).map { it.file }.distinct()
            val referencing = references[ident].orEmpty()

            // A definition nothing names still exists: Aider gives it a small self-edge so it does
            // not vanish from the graph entirely.
            if (referencing.isEmpty()) {
                definers.forEach { add(Edge(position.getValue(it), position.getValue(it), ident, SELF_EDGE)) }
                continue
            }

            var multiplier = 1.0
            if (ident in mentioned) multiplier *= 10.0
            if (ident.length >= 8 && (ident.any { it.isUpperCase() } && ident.any { it.isLowerCase() } || '_' in ident)) {
                multiplier *= 10.0
            }
            if (ident.startsWith("_")) multiplier *= 0.1
            // A name defined everywhere identifies nothing.
            if (definers.size > 5) multiplier *= 0.1

            for ((referencer, count) in referencing.entries.sortedBy { it.key }) {
                val from = position[referencer] ?: continue
                for (definer in definers) {
                    add(Edge(from, position.getValue(definer), ident, multiplier * sqrt(count.toDouble())))
                }
            }
        }
    }

    /** Files that define something the task named; the rest start from nothing. */
    private fun personalization(mentioned: Set<String>): DoubleArray {
        val restart = DoubleArray(files.size)
        for (ident in mentioned) {
            definitions[ident].orEmpty().forEach { restart[position.getValue(it.file)] += 1.0 }
        }
        val total = restart.sum()
        if (total == 0.0) return DoubleArray(files.size) { 1.0 / files.size }
        return DoubleArray(files.size) { restart[it] / total }
    }

    private fun pageRank(edges: List<Edge>, restart: DoubleArray): DoubleArray {
        val outWeight = DoubleArray(files.size)
        edges.forEach { outWeight[it.from] += it.weight }

        var rank = restart.copyOf()
        repeat(ITERATIONS) {
            val next = DoubleArray(files.size)
            var dangling = 0.0
            for (at in files.indices) {
                if (outWeight[at] == 0.0) dangling += rank[at]
            }
            for (edge in edges) {
                next[edge.to] += DAMPING * rank[edge.from] * edge.weight / outWeight[edge.from]
            }
            for (at in files.indices) {
                next[at] += (1 - DAMPING) * restart[at] + DAMPING * dangling * restart[at]
            }
            rank = next
        }
        return rank
    }

    private fun countReferences(): Map<String, Map<String, Int>> {
        val counts = HashMap<String, HashMap<String, Int>>()
        for (file in files) {
            val text = runCatching { repoRoot.resolve(file).readText() }.getOrDefault("")
            val seen = HashMap<String, Int>()
            IDENTIFIER.findAll(text).forEach { seen.merge(it.value, 1, Int::plus) }

            for ((ident, count) in seen) {
                if (ident !in definitions) continue
                // Declaring a name is not referencing it.
                val declared = definitions.getValue(ident).count { it.file == file }
                val referenced = count - declared
                if (referenced > 0) counts.getOrPut(ident) { HashMap() }[file] = referenced
            }
        }
        return counts
    }

    private companion object {
        val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        const val DAMPING = 0.85
        const val ITERATIONS = 30
        const val SELF_EDGE = 0.1
    }
}
