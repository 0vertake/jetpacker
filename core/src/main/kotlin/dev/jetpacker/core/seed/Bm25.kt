package dev.jetpacker.core.seed

import kotlin.math.ln

/**
 * Splits `parseKtFile` into `parse`, `kt`, `file` so prose can match code names.
 *
 * Single characters are dropped: they carry no signal and inflate every document's length.
 */
fun terms(text: String): List<String> =
    WORD.findAll(text).map { it.value.lowercase() }.filter { it.length > 1 }.toList()

private val WORD = Regex("""[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+""")

/**
 * Textbook BM25 over an in-memory corpus.
 *
 * Hand-rolled at forty lines rather than pulled from Lucene, which would be a large dependency
 * and an index lifecycle to own for one ranked query over a list already in memory.
 *
 * Shared by the seed finder and the BM25 baseline on purpose: a baseline that is quietly a worse
 * implementation of the same idea measures nothing (docs/plan.md §5).
 */
class Bm25(private val documents: List<List<String>>) {
    private val lengths = documents.map { it.size }
    private val averageLength = lengths.average().takeIf { !it.isNaN() } ?: 0.0
    private val postings: Map<String, List<Int>> = buildMap<String, MutableList<Int>> {
        documents.forEachIndexed { document, terms ->
            terms.distinct().forEach { getOrPut(it) { mutableListOf() } += document }
        }
    }

    /** Score per matching document. Callers sort, so they can break ties on their own stable key. */
    fun score(terms: List<String>): Map<Int, Double> {
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
        return scores
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
    }
}
