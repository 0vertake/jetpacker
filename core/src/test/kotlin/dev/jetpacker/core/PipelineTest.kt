package dev.jetpacker.core

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import dev.jetpacker.core.index.AnalysisApiIndexer
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.pack.Fidelity
import dev.jetpacker.core.pack.toMarkdown
import dev.jetpacker.core.rank.EdgeWeights
import dev.jetpacker.core.rank.Ranker
import dev.jetpacker.core.seed.SeedFinder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end on the greeter fixture: task text in, pack out.
 *
 * The claims worth testing here are the ones the benchmark will later have to defend — that
 * expansion reaches code the task never names, that the budget is respected exactly, and that
 * the same input renders byte-identically.
 */
class PipelineTest {
    @Test
    fun `reaches an implementation the task never mentions`() {
        val pack = Jetpacker(injectionRoot, injection).pack(INJECTION_TASK)
        val adapter = pack.items.singleOrNull { it.symbol.id == "fixture.injection.StripeAdapter" }

        assertTrue(
            adapter != null,
            "expansion must reach the implementation of a named interface, got ${pack.items.map { it.symbol.id }}",
        )
        assertEquals(
            "impl-of:PaymentGateway",
            adapter.why,
            "StripeAdapter shares no vocabulary with the task, so only the graph can explain it",
        )
    }

    @Test
    fun `the pack names how much of the repository resolved`() {
        val markdown = packer().pack("`GreetingService` is broken").toMarkdown()

        assertTrue(
            Regex("""\d+% of \d+ calls resolved""").containsMatchIn(markdown),
            "coverage is the indexer's quality line, got:\n$markdown",
        )
    }

    @Test
    fun `explains why every item is present`() {
        val pack = packer().pack("`GreetingService` is broken")

        assertTrue(pack.items.isNotEmpty())
        assertTrue(
            pack.items.all { it.why.isNotBlank() },
            "provenance is what makes a pack auditable (docs/plan.md §6)",
        )
        assertEquals("seed", pack.items.first { it.symbol.id == "fixture.GreetingService" }.why)
    }

    @Test
    fun `the rendered pack never exceeds the budget`() {
        val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)

        for (budget in listOf(60, 120, 400, 4000)) {
            val pack = packer().pack("`Greeter` and `GreetingService`", budget)
            val rendered = encoding.countTokens(pack.toMarkdown())

            // Measured on the Markdown, not on the code inside it: headings, paths and fences are
            // tokens too, and counting only bodies once understated a pack six-fold.
            assertTrue(rendered <= budget, "rendered $rendered tokens for a budget of $budget")
            assertTrue(pack.tokens <= budget, "reported ${pack.tokens} of $budget")
            assertTrue(
                pack.tokens >= rendered,
                "the packer charges for what the renderer prints, so its count may round up but " +
                    "never down: reported ${pack.tokens}, rendered $rendered",
            )
        }
    }

    @Test
    fun `names a file once for all of the signatures it contributes`() {
        // Tight enough that Greeter.kt contributes signatures rather than whole bodies.
        val markdown = packer().pack("`Greeter` and `GreetingService`", budget = 120).toMarkdown()
        val stubs = markdown.substringAfter("## Related signatures", "")

        assertTrue(
            stubs.lines().count { it.startsWith("- ") } >= 2,
            "expected several signatures from one file, got:\n$markdown",
        )
        assertEquals(
            1,
            Regex("`[^`\n]*Greeter\\.kt`").findAll(stubs).count(),
            "repeating the path per stub cost more than the signatures did, so it is printed once",
        )
    }

    @Test
    fun `spends a small budget on signatures rather than one body`() {
        val pack = packer().pack("`Greeter` and `GreetingService`", budget = 60)

        assertTrue(pack.items.isNotEmpty(), "a tight budget should still yield stubs")
        assertTrue(
            pack.items.count { it.fidelity == Fidelity.STUB } >= pack.items.count { it.fidelity == Fidelity.FULL },
            "density ranking should prefer several stubs over one body, got ${pack.items.map { it.fidelity }}",
        )
    }

    @Test
    fun `does not pack a method twice inside its own class body`() {
        val pack = packer().pack("`FormalGreeter` greets", budget = 4000)
        val full = pack.items.filter { it.fidelity == Fidelity.FULL }.map { it.symbol.id }

        assertTrue(
            !("fixture.FormalGreeter" in full && "fixture.FormalGreeter.greet(kotlin.String)" in full),
            "a class body already contains its methods, got $full",
        )
    }

    @Test
    fun `renders byte-identically across runs`() {
        val task = "`Greeter` formats the greeting wrong"

        assertEquals(packer().pack(task).toMarkdown(), packer().pack(task).toMarkdown())
    }

    @Test
    fun `packs nothing when the task matches nothing`() {
        val pack = packer().pack("update the CSS grid on the marketing page")

        assertEquals(emptyList(), pack.items)
    }

    @Test
    fun `the pack lists an unresolved call in a packed file`() {
        val root = fixture("unresolved")
        val index = indexOf(root)
        val markdown = Jetpacker(root, index).pack("`broken` cannot find missing").toMarkdown()

        assertTrue(
            index.errors.any { "missing" in it.message },
            "the indexer has to record the unresolved call, got ${index.errors}",
        )
        assertTrue("## Diagnostics" in markdown, markdown)
        assertTrue("missing" in markdown, markdown)
    }

    @Test
    fun `without structural expansion the implementation is never found`() {
        val ranked = Ranker(injection, EdgeWeights().none())
            .rank(SeedFinder(injection).find(INJECTION_TASK))

        assertTrue(
            ranked.none { it.symbol.id == "fixture.injection.StripeAdapter" },
            "this is the headline ablation's OFF arm: without edges the implementation is " +
                "unreachable, got ${ranked.map { it.symbol.id }}",
        )
    }

    private fun packer() = Jetpacker(root, index)

    private companion object {
        const val INJECTION_TASK = "`PaymentGateway` rejects amounts it should accept"

        fun fixture(name: String): Path =
            Path.of(requireNotNull(PipelineTest::class.java.getResource("/fixtures/$name")).toURI())

        fun indexOf(root: Path): CodeIndex =
            AnalysisApiIndexer(listOf(root), repoRoot = root).use { it.index() }

        val root: Path by lazy { fixture("greeter") }
        val index: CodeIndex by lazy { indexOf(root) }

        val injectionRoot: Path by lazy { fixture("injection") }
        val injection: CodeIndex by lazy { indexOf(injectionRoot) }
    }
}
