package dev.jetpacker.baselines

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.pack.Fidelity
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.PackItem
import java.nio.file.Path
import kotlin.io.path.readLines

/** One fixed window of lines, and whichever declarations it happens to hold whole. */
internal class Chunk(val file: String, val startLine: Int, val text: String, val symbols: List<Symbol>) {
    /**
     * What a ranking sees: the window, with the path it came from.
     *
     * Every chunking pipeline attaches the path to the chunk before indexing it, and on this corpus
     * it is the most informative line available — `UnnecessaryInnerClass.kt` says more about what a
     * window is for than the forty lines of Kotlin below it. Both chunk baselines get it, so
     * neither is being handicapped by an omission the other does not have.
     */
    val indexed: String get() = "$file\n$text"
}

/** Roughly the 300-token window that chunking tools default to. */
internal const val WINDOW_LINES = 40

/**
 * Every file the index knows a declaration in, cut into windows of [windowLines] lines.
 *
 * Shared by the two chunk baselines so that the only thing separating them is how they rank what
 * comes out: keywords in [ChunkRetriever], a sentence-transformer in [EmbeddingChunkRetriever].
 */
internal fun chunk(index: CodeIndex, repoRoot: Path, windowLines: Int = WINDOW_LINES): List<Chunk> {
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
                symbols = bySymbolFile.getValue(file).filter { it.startLine >= start && it.endLine <= end },
            )
        }
    }
}

/**
 * Takes windows in [order] until the budget is gone.
 *
 * A window only gets credit for a declaration it contains *whole*: half a function is not
 * retrieval, and letting a boundary count would make a baseline look better the more it cut things
 * in half.
 */
internal fun packChunks(chunks: List<Chunk>, order: List<Int>, budget: Int): Pack {
    val items = mutableListOf<PackItem>()
    var spent = 0

    for (at in order) {
        val chunk = chunks[at]
        val cost = encoding.countTokens(render(chunk))
        if (spent + cost > budget) continue
        spent += cost
        // The window is what costs tokens, so its declarations are listed at zero: charging each of
        // them again would report a pack several times the size of the text shown.
        items += chunk.symbols.map { PackItem(it, Fidelity.FULL, "chunk:${chunk.file}", it.signature, 0) }
    }
    return Pack(items, spent, budget)
}

/**
 * Windows by descending score, ties broken by position so that a run is reproducible.
 *
 * Only scored windows are ranked. A ranking that fell back to every other window in the repository
 * would spend whatever budget the matches left over on arbitrary text, which is not what a
 * retriever does.
 */
internal fun order(chunks: List<Chunk>, scores: Map<Int, Double>): List<Int> =
    scores.keys.sortedWith(
        compareByDescending<Int> { scores.getValue(it) }
            .thenBy { chunks[it].file }
            .thenBy { chunks[it].startLine },
    )

private fun render(chunk: Chunk) = "### ${chunk.file}:${chunk.startLine}\n\n```kotlin\n${chunk.text}\n```\n"

private val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
