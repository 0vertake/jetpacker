package dev.jetpacker.baselines

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Evidence that the Aider port ranks the way Aider does, so that its score on a real repository
 * can be read as a property of the algorithm rather than of a mistake in the port.
 *
 * The fixture is three files that name `Gadget` and one that names nothing: exactly the situation
 * the repo map exists to summarize.
 */
class RepoMapRetrieverTest {
    @Test
    fun `the more widely referenced declaration ranks higher`() {
        val packed = retriever().pack("something is broken", budget = 4000).items.map { it.symbol.id }

        assertTrue("Gadget" in packed, "the most referenced definition is the map's whole point, got $packed")
        assertTrue(
            packed.indexOf("Gadget") < packed.indexOf("Hermit"),
            "reference count is what ranks a repo map, got $packed",
        )
    }

    @Test
    fun `naming a declaration in the task pulls it in`() {
        val packed = retriever().pack("Hermit returns the wrong value", budget = 4000).items.map { it.symbol.id }

        assertTrue("Hermit" in packed, "a mentioned identifier is weighted ten-fold, got $packed")
    }

    @Test
    fun `ranks the same way every run`() {
        val task = "Gadget is broken"

        assertTrue(
            retriever().pack(task, 200).items.map { it.symbol.id } ==
                retriever().pack(task, 200).items.map { it.symbol.id },
        )
    }

    private fun retriever() = RepoMapRetriever(index, root)

    private companion object {
        val root: Path by lazy {
            createTempDirectory("jetpacker-repomap").also { directory ->
                directory.resolve("Gadget.kt").writeText("class Gadget { fun run() = 1 }\n")
                for (at in 1..3) {
                    directory.resolve("User$at.kt").writeText(
                        "class User$at { fun use(gadget: Gadget) = Gadget().run() }\n",
                    )
                }
                // Referenced once, against Gadget's six times. A definition referenced *nowhere*
                // is a different case: Aider gives it a self-edge that hands it its file's whole
                // rank, so it would say more about that rule than about reference counting.
                directory.resolve("Hermit.kt").writeText("class Hermit { fun alone() = 2 }\n")
                directory.resolve("User1.kt").writeText(
                    "class User1 { fun use(gadget: Gadget) = Gadget().run() + Hermit().alone() }\n",
                )
            }
        }

        val index: CodeIndex by lazy {
            CodeIndex(
                symbols = listOf(symbol("Gadget", "Gadget.kt"), symbol("Hermit", "Hermit.kt")) +
                    (1..3).map { symbol("User$it", "User$it.kt") },
                edges = emptyList(),
                coverage = ResolutionCoverage(0, 0, 0),
            )
        }

        fun symbol(name: String, file: String) = Symbol(
            id = name,
            fqName = name,
            name = name,
            kind = SymbolKind.CLASS,
            file = file,
            startLine = 1,
            endLine = 1,
            signature = "class $name",
            doc = null,
            tokens = 3,
            isTest = false,
        )
    }
}
