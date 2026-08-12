package dev.jetpacker.baselines

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

/**
 * Opt-in: the model is a 90MB download on first run, and the point of the arm is a benchmark rather
 * than a build gate.
 *
 *     ./gradlew :baselines:test -Djetpacker.embed=true --tests '*Embedding*'
 */
class EmbeddingChunkRetrieverTest {
    private val embedder by lazy { Embedder() }

    @AfterTest
    fun release() {
        if (enabled) embedder.close()
    }

    @Test
    fun `finds the window that means what the task means, without sharing its words`() {
        assumeTrue(enabled, WHY)

        val packed = retriever().pack("the greeting is not translated for other languages", budget = 4000)

        assertEquals(
            "translate",
            packed.items.first().symbol.id,
            "no word of the task appears in the declaration; keywords cannot reach it and meaning can",
        )
    }

    @Test
    fun `scores every window, so a task that matches no keyword still fills the budget`() {
        assumeTrue(enabled, WHY)

        val words = "zzz qqq vvv"
        val keywords = ChunkRetriever(index, root, windowLines = 10).pack(words, budget = 4000)
        val meaning = retriever().pack(words, budget = 4000)

        assertTrue(keywords.items.isEmpty(), "BM25 has nothing to match, got ${keywords.items}")
        assertTrue(meaning.items.isNotEmpty(), "a dense ranking always has an ordering to offer")
    }

    @Test
    fun `stops at the budget and ranks the same way every run`() {
        assumeTrue(enabled, WHY)

        val one = retriever().pack("translation", budget = 120)
        val two = retriever().pack("translation", budget = 120)

        assertTrue(one.tokens <= 120, "spent ${one.tokens}")
        assertEquals(one.items.map { it.symbol.id }, two.items.map { it.symbol.id })
    }

    @Test
    fun `separates code by meaning rather than by shared words`() {
        assumeTrue(enabled, WHY)

        val task = embedder.embed("the greeting is not translated")
        val near = embedder.embed(index.symbols.first { it.id == "translate" }.signature)
        val far = embedder.embed(index.symbols.first { it.id == "checksum" }.signature)

        assertTrue(
            Embedder.similarity(task, near) > Embedder.similarity(task, far),
            "the model is the baseline's whole ranking; if it cannot do this the arm means nothing",
        )
        assertNotEquals(0.0, Embedder.similarity(near, near), "a normalized vector is its own cosine")
    }

    private fun retriever() = EmbeddingChunkRetriever(index, root, embedder, windowLines = 10)

    private companion object {
        val enabled = System.getProperty("jetpacker.embed") != null
        const val WHY = "set -Djetpacker.embed=true to download and run the embedding model"

        const val FILE = "src/Fixture.kt"

        /**
         * Three declarations that share no vocabulary with each other, in windows of ten lines. The
         * task text deliberately avoids every word in the code it should find.
         */
        val root: Path by lazy {
            createTempDirectory("jetpacker-embed").also { directory ->
                directory.resolve(FILE).parent.toFile().mkdirs()
                directory.resolve(FILE).writeText(
                    buildString {
                        appendLine("fun translate(text: String, locale: Locale): String =")
                        appendLine("    bundle.forLocale(locale).lookup(text) ?: text")
                        repeat(8) { appendLine("// filler") }
                        appendLine("fun checksum(bytes: ByteArray): Long =")
                        appendLine("    bytes.fold(0L) { total, byte -> total * 31 + byte }")
                        repeat(8) { appendLine("// filler") }
                    },
                )
            }
        }

        val index: CodeIndex by lazy {
            CodeIndex(
                symbols = listOf(
                    symbol("translate", 1, 2, "fun translate(text: String, locale: Locale): String"),
                    symbol("checksum", 11, 12, "fun checksum(bytes: ByteArray): Long"),
                ),
                edges = emptyList(),
                coverage = ResolutionCoverage(0, 0, 0),
            )
        }

        fun symbol(id: String, startLine: Int, endLine: Int, signature: String) = Symbol(
            id = id,
            fqName = id,
            name = id,
            kind = SymbolKind.FUNCTION,
            file = FILE,
            startLine = startLine,
            endLine = endLine,
            signature = signature,
            doc = null,
            tokens = 12,
            isTest = false,
        )
    }
}
