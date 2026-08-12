package dev.jetpacker.baselines

import dev.jetpacker.core.Retriever
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.seed.Bm25
import dev.jetpacker.core.seed.terms
import java.nio.file.Path

/**
 * Chunk RAG: fixed windows of lines, ranked by keyword similarity, packed to the budget.
 *
 * This is the baseline the whole project argues against, so it is implemented to be as strong as
 * it honestly can be — same tokenizer, same budget, same task text. It is retrieval over character
 * windows rather than declarations, which is the one thing that must differ.
 *
 * Ranking is BM25. [EmbeddingChunkRetriever] is the same windows ranked by a sentence-transformer,
 * which is what most people mean by RAG; the two are reported side by side.
 */
class ChunkRetriever(
    index: CodeIndex,
    repoRoot: Path,
    override val name: String = "chunk-bm25",
    windowLines: Int = WINDOW_LINES,
) : Retriever {
    private val chunks: List<Chunk> = chunk(index, repoRoot, windowLines)
    private val bm25 = Bm25(chunks.map { terms(it.indexed) })

    override fun pack(task: String, budget: Int): Pack =
        packChunks(chunks, order(chunks, bm25.score(terms(task))), budget)
}
