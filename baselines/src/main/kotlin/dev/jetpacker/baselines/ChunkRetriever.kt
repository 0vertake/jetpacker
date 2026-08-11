package dev.jetpacker.baselines

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.pack.Fidelity
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.PackItem
import dev.jetpacker.core.seed.Bm25
import dev.jetpacker.core.seed.terms
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Chunk RAG: fixed windows of lines, ranked by keyword similarity, packed to the budget.
 *
 * This is the baseline the whole project argues against, so it is implemented to be as strong as
 * it honestly can be — same tokenizer, same budget, same task text. It is retrieval over character
 * windows rather than declarations, which is the one thing that must differ.
 *
 * Ranking is BM25 rather than embeddings. Every practitioner report the plan cites has BM25 at or
 * above a local embedding model on code of this size, and an embedding model would add a model
 * dependency to a comparison it is unlikely to change. Noted as a limitation, not a result.
 *
 * A window only gets credit for a declaration it contains *whole*: half a function is not
 * retrieval, and letting a boundary count would make the baseline look better the more it cut
 * things in half.
 */
class ChunkRetriever(
    index: CodeIndex,
    repoRoot: Path,
    override val name: String = "chunk-bm25",
    windowLines: Int = WINDOW_LINES,
) : Retriever {
    private val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
    private val chunks: List<Chunk> = chunk(index, repoRoot, windowLines)
    private val bm25 = Bm25(chunks.map { terms(it.text) })

    private class Chunk(val file: String, val startLine: Int, val text: String, val symbols: List<Symbol>)

    override fun pack(task: String, budget: Int): Pack {
        val scores = bm25.score(terms(task))
        val ordered = scores.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Double>> { it.value }
                .thenBy { chunks[it.key].file }
                .thenBy { chunks[it.key].startLine },
        )

        val items = mutableListOf<PackItem>()
        var spent = 0
        for ((at, _) in ordered) {
            val chunk = chunks[at]
            val cost = encoding.countTokens(render(chunk))
            if (spent + cost > budget) continue
            spent += cost
            // The window is what costs tokens, so its declarations are listed at zero: charging
            // each of them again would report a pack several times the size of the text shown.
            items += chunk.symbols.map { PackItem(it, Fidelity.FULL, "chunk:${chunk.file}", it.signature, 0) }
        }
        return Pack(items, spent, budget)
    }

    private fun render(chunk: Chunk): String =
        "### ${chunk.file}:${chunk.startLine}\n\n```kotlin\n${chunk.text}\n```\n"

    private companion object {
        /** Roughly the 300-token window that chunking tools default to. */
        const val WINDOW_LINES = 40

        fun chunk(index: CodeIndex, repoRoot: Path, windowLines: Int): List<Chunk> {
            val bySymbolFile = index.symbols.groupBy { it.file }

            return bySymbolFile.keys.sorted().flatMap { file ->
                val lines = runCatching { repoRoot.resolve(file).readLines() }.getOrDefault(emptyList())
                lines.chunked(windowLines).mapIndexed { at, window ->
                    val start = at * windowLines + 1
                    val end = start + window.size - 1
                    Chunk(
                        file = file,
                        startLine = start,
                        text = window.joinToString("\n"),
                        symbols = bySymbolFile.getValue(file)
                            .filter { it.startLine >= start && it.endLine <= end },
                    )
                }
            }
        }
    }
}
