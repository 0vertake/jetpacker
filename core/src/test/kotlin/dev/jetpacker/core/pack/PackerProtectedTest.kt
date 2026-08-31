package dev.jetpacker.core.pack

import dev.jetpacker.core.index.AnalysisApiIndexer
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.rank.Ranked
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Protected lexical seeds must land even when graph rank omits them. */
class PackerProtectedTest {
    @Test
    fun `packs a protected symbol that graph rank omitted`() {
        val root = fixture("greeter")
        val index = indexOf(root)
        val greeter = index.byId.getValue("fixture.Greeter")
        val packer = Packer(index, root, budget = 4000, fullTierShare = 0.15)

        // Graph rank lists only an unrelated symbol; BM25 would still hit Greeter.
        val ranked = listOf(Ranked(index.byId.getValue("fixture.FormalGreeter"), 1.0, "graph"))
        val pack = packer.pack(ranked, protected = listOf(greeter.id))

        assertTrue(
            pack.items.any { it.symbol.id == greeter.id },
            "protected seed must appear even when absent from ranked candidates, got ${pack.items.map { it.symbol.id }}",
        )
    }

    private companion object {
        fun fixture(name: String): Path =
            Path.of(requireNotNull(PackerProtectedTest::class.java.getResource("/fixtures/$name")).toURI())

        fun indexOf(root: Path): CodeIndex =
            AnalysisApiIndexer(listOf(root), repoRoot = root).use { it.index() }
    }
}
