package dev.jetpacker.core.seed

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Symbol

/** A symbol the task text points at, with the channel that found it (docs/plan.md §6). */
data class Seed(val id: String, val score: Double, val why: String)

fun interface DenseSeeds {
    fun score(task: String): Map<Int, Double>
}

/**
 * Turns task text into the symbols a pack should be built around.
 *
 * [testPenalty] applies to seeds only. Spec-style test names are English sentences, so they match
 * task prose better than the code under test does: on detekt the top seeds for "Don't leak
 * AnalysisApi types" were five variants of `AnnotationExcluderSpec.difference between Analysis API
 * and no Analysis API`, and expansion then restarted inside the test suite. Tests still reach a
 * pack the way they should — through a `test-of` edge from the code they exercise.
 *
 * Two channels by default, fused with Reciprocal Rank Fusion: BM25 over declaration names and
 * docs, and exact hits on identifiers the task quotes literally. They fail in different ways —
 * BM25 is robust to paraphrase but ranks a quoted `FooBar` no higher than any other term, while
 * exact matching is precise and brittle — so RRF is used rather than a weighted sum, since it
 * needs no calibration between two incomparable score scales.
 *
 * A third, optional channel ([dense]) ranks by embedding similarity. Off unless passed in.
 */
class SeedFinder(
    private val index: CodeIndex,
    private val testPenalty: Double = DEFAULT_TEST_PENALTY,
    private val dense: DenseSeeds? = null,
) {
    private val bm25 = Bm25(index.symbols.map(::documentOf))

    /** Symbols named outright in the task, by exact identifier — case-sensitive on purpose. */
    private val byName: Map<String, List<Int>> =
        index.symbols.indices.groupBy { index.symbols[it].name }

    fun find(task: String, limit: Int = DEFAULT_SEEDS): List<Seed> {
        val ranked = buildMap {
            put("seed:mentioned", exactMatches(task))
            put("seed:search", search(task, CANDIDATES, testPenalty))
            dense?.let { put("seed:embed", embed(task)) }
        }
        return fuse(ranked).take(limit)
    }

    /** Dense ranking, same test penalty and candidate cap as [search]. */
    private fun embed(task: String): List<Int> =
        dense!!.score(task).entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Double>> {
                    it.value * if (index.symbols[it.key].isTest) testPenalty else 1.0
                }
                    .thenBy { index.symbols[it.key].id },
            )
            .map { it.key }
            .take(CANDIDATES)

    /** BM25 alone, exposed so the baseline can run the identical ranking without the graph. */
    fun search(task: String, limit: Int = CANDIDATES, testPenalty: Double = 1.0): List<Int> =
        bm25.score(terms(task)).entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Double>> {
                    it.value * if (index.symbols[it.key].isTest) testPenalty else 1.0
                }
                    .thenBy { index.symbols[it.key].id },
            )
            .map { it.key }
            .take(limit)

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
            .sortedWith(compareBy({ index.symbols[it].isTest }, { index.symbols[it].id }))
            .toList()
    }

    /**
     * A declaration's searchable text. The name is repeated so that matching what something is
     * called outranks matching its package or a word in its doc.
     */
    private fun documentOf(symbol: Symbol): List<String> =
        terms(symbol.name) + terms(symbol.name) + terms(symbol.fqName) + terms(symbol.doc.orEmpty())

    companion object {
        /** On detekt every value below 1.0 scored the same, so this only has to break the tie. */
        const val DEFAULT_TEST_PENALTY = 0.3

        private const val RRF_K = 60
        private const val CANDIDATES = 200
        private const val DEFAULT_SEEDS = 20

        private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        private val BACKTICKED = Regex("""`([^`\n]+)`""")

        /** `GreetingService`, `parseKtFile`, `snake_case` — but not an ordinary English word. */
        private val COMPOUND_NAME = Regex("""\b[A-Za-z][A-Za-z0-9]*(?:[A-Z][A-Za-z0-9]*|_[A-Za-z0-9]+)+\b""")
    }
}
