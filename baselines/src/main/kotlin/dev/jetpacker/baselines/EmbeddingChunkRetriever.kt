package dev.jetpacker.baselines

import dev.jetpacker.core.Retriever
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Pack
import java.nio.file.Path

/**
 * Chunk RAG as most people build it: the same fixed windows as [ChunkRetriever], ranked by cosine
 * similarity against a sentence-transformer instead of by keywords.
 *
 * This is the arm that answers the obvious objection to the results table — that the chunk baseline
 * was losing because BM25 is not what anyone means by RAG. Everything except the ranking is held
 * constant: same windows, same budget, same tokenizer, same rule that a window earns credit only
 * for declarations it holds whole.
 *
 * Unlike BM25, an embedding scores every window, so this arm can always fill its budget. That is a
 * real advantage rather than a rigged one — dense retrieval never runs out of candidates the way a
 * keyword match does.
 */
class EmbeddingChunkRetriever(
    index: CodeIndex,
    repoRoot: Path,
    private val embedder: Embedder,
    windowLines: Int = WINDOW_LINES,
) : Retriever {
    override val name = "chunk-embed"

    private val chunks: List<Chunk> = chunk(index, repoRoot, windowLines)
    private val vectors: List<FloatArray> by lazy { embedder.embed(chunks.map { it.indexed }) }

    override fun pack(task: String, budget: Int): Pack {
        val query = embedder.embed(task)
        val scores = vectors.indices.associateWith { Embedder.similarity(query, vectors[it]) }
        return packChunks(chunks, order(chunks, scores), budget)
    }
}
