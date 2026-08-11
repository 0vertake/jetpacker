package dev.jetpacker.core.seed

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Symbol
import kotlin.math.ln

/** A symbol the task text points at, with the channel that found it (docs/plan.md §6). */
data class Seed(val id: String, val score: Double, val why: String)

/**
 * Turns task text into the symbols a pack should be built around.
 *
 * Two channels, fused with Reciprocal Rank Fusion: BM25 over declaration names and docs, and
 * exact hits on identifiers the task quotes literally. They fail in different ways — BM25 is
 * robust to paraphrase but ranks a quoted `FooBar` no higher than any other term, while exact
 * matching is precise and brittle — so RRF is used rather than a weighted sum, since it needs no
 * calibration between two incomparable score scales.
 */
class SeedFinder(private val index: CodeIndex) {
    private val documents: List<List<String>> = index.symbols.map(::documentOf)
    private val lengths: List<Int> = documents.map { it.size }
    private val averageLength: Double = lengths.average().takeIf { !it.isNaN() } ?: 0.0
    private val postings: Map<String, List<Int>> = buildPostings()

    /** Symbols named outright in the task, by exact identifier — case-sensitive on purpose. */
    private val byName: Map<String, List<Int>> =
        index.symbols.indices.groupBy { index.symbols[it].name }

    fun find(task: String, limit: Int = DEFAULT_SEEDS): List<Seed> {
        val ranked = mapOf(
            "seed:mentioned" to exactMatches(task),
            "seed:search" to bm25(queryTerms(task)),
        )
        return fuse(ranked).take(limit)
    }

    /**
     * Reciprocal Rank Fusion: each channel contributes `1 / (k + rank)`. [RRF_K] damps the top of
     * short lists so a single channel cannot dominate the fused ranking.
     */
    private fun fuse(channels: Map<String, List<Int>>): List<Seed> {
        val scores = HashMap<Int, Double>()
        val reasons = HashMap<Int, MutableSet<String>>()
        for ((why, ranking) in channels) {
            ranking.forEachIndexed { rank, symbol ->
                scores.merge(symbol, 1.0 / (RRF_K + rank + 1)) { a, b -> a + b }
                reasons.getOrPut(symbol) { sortedSetOf() } += why
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { index.symbols[it.key].id })
            .map { (symbol, score) ->
                Seed(index.symbols[symbol].id, score, reasons.getValue(symbol).joinToString("+"))
            }
    }

    /**
     * Only text that is visibly code counts: backticked spans, and bare words carrying an interior
     * capital or underscore. Matching every word would make an issue that says "should build the
     * value" seed on whatever happens to be named `build` or `value`.
     */
    private fun exactMatches(task: String): List<Int> {
        val quoted = BACKTICKED.findAll(task).flatMap { IDENTIFIER.findAll(it.groupValues[1]) }
        val compound = COMPOUND_NAME.findAll(task)
        return (quoted + compound)
            .map { it.value }
            .distinct()
            .flatMap { byName[it].orEmpty() }
            .distinct()
            // Nothing separates two symbols that share a quoted name, so order by id for stability.
            .sortedBy { index.symbols[it].id }
            .toList()
    }

    private fun bm25(terms: List<String>): List<Int> {
        if (terms.isEmpty() || documents.isEmpty()) return emptyList()
        val scores = HashMap<Int, Double>()
        for (term in terms) {
            val matches = postings[term] ?: continue
            val idf = ln(1 + (documents.size - matches.size + 0.5) / (matches.size + 0.5))
            for (document in matches) {
                val frequency = documents[document].count { it == term }.toDouble()
                val norm = K1 * (1 - B + B * lengths[document] / averageLength)
                scores.merge(document, idf * frequency * (K1 + 1) / (frequency + norm)) { a, b -> a + b }
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { index.symbols[it.key].id })
            .map { it.key }
            .take(CANDIDATES)
    }

    private fun buildPostings(): Map<String, List<Int>> {
        val postings = HashMap<String, MutableList<Int>>()
        documents.forEachIndexed { document, terms ->
            terms.distinct().forEach { postings.getOrPut(it) { mutableListOf() } += document }
        }
        return postings
    }

    /**
     * A declaration's searchable text. The name is repeated so that matching what something is
     * called outranks matching its package or a word in its doc.
     */
    private fun documentOf(symbol: Symbol): List<String> =
        terms(symbol.name) + terms(symbol.name) + terms(symbol.fqName) + terms(symbol.doc.orEmpty())

    private fun queryTerms(task: String): List<String> = terms(task)

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
        const val RRF_K = 60
        const val CANDIDATES = 200
        const val DEFAULT_SEEDS = 20

        val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        val BACKTICKED = Regex("""`([^`\n]+)`""")

        /** `GreetingService`, `parseKtFile`, `snake_case` — but not an ordinary English word. */
        val COMPOUND_NAME = Regex("""\b[A-Za-z][A-Za-z0-9]*(?:[A-Z][A-Za-z0-9]*|_[A-Za-z0-9]+)+\b""")

        /** Splits `parseKtFile` into `parse`, `kt`, `file` so prose can match code names. */
        val WORD = Regex("""[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+""")

        fun terms(text: String): List<String> =
            WORD.findAll(text).map { it.value.lowercase() }.filter { it.length > 1 }.toList()
    }
}
