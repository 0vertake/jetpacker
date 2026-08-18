package dev.jetpacker.baselines

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import dev.jetpacker.core.seed.SeedFinder
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in: same model download as [EmbeddingChunkRetrieverTest].
 *
 *     ./gradlew :baselines:test -Djetpacker.embed=true --tests '*EmbeddingSeeds*'
 */
class EmbeddingSeedsTest {
    private val embedder by lazy { Embedder() }

    @AfterTest
    fun release() {
        if (enabled) embedder.close()
    }

    @Test
    fun `seeds a declaration the task describes and never names`() {
        assumeTrue(enabled, WHY)

        val seeds = SeedFinder(index, dense = EmbeddingSeeds(index, embedder))
            .find("the greeting is not translated for other languages")

        assertEquals("translate", seeds.first().id)
        assertTrue("seed:embed" in seeds.first().why)
    }

    private companion object {
        val enabled = System.getProperty("jetpacker.embed") != null
        const val WHY = "set -Djetpacker.embed=true to download and run the embedding model"

        val index = CodeIndex(
            symbols = listOf(
                symbol("translate", "fun translate(text: String, locale: Locale): String"),
                symbol("checksum", "fun checksum(bytes: ByteArray): Long"),
            ),
            edges = emptyList(),
            coverage = ResolutionCoverage(0, 0, 0),
        )

        fun symbol(id: String, signature: String) = Symbol(
            id = id,
            fqName = id,
            name = id,
            kind = SymbolKind.FUNCTION,
            file = "src/Fixture.kt",
            startLine = 1,
            endLine = 2,
            signature = signature,
            doc = null,
            tokens = 12,
            isTest = false,
        )
    }
}
