package dev.jetpacker.baselines

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.seed.DenseSeeds

/**
 * MiniLM seed channel. Opt-in; `jp:default` stays BM25 plus quoted names.
 */
class EmbeddingSeeds(
    private val index: CodeIndex,
    private val embedder: Embedder,
) : DenseSeeds {
    private val vectors: List<FloatArray> by lazy { embedder.embed(index.symbols.map(::documentOf)) }

    override fun score(task: String): Map<Int, Double> {
        val query = embedder.embed(task)
        return vectors.indices.associateWith { Embedder.similarity(query, vectors[it]) }
    }

    private fun documentOf(symbol: Symbol): String = buildString {
        append(symbol.signature)
        symbol.doc?.let { append('\n').append(it) }
    }
}
