package dev.jetpacker.core.seed

import dev.jetpacker.core.index.AnalysisApiIndexer
import dev.jetpacker.core.index.CodeIndex
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeds decide what the rest of the pipeline ever gets to see, so the cases that matter are the
 * ones where a channel fails alone: prose that never spells a name the way the code does, and
 * ordinary English words that collide with declaration names.
 */
class SeedFinderTest {
    @Test
    fun `finds a declaration the task names outright`() {
        val seeds = finder.find("`GreetingService` returns the wrong string")

        assertEquals("fixture.GreetingService", seeds.first().id)
        assertTrue("seed:mentioned" in seeds.first().why)
    }

    @Test
    fun `matches prose against a camelCase name`() {
        val ids = finder.find("the greeting service welcomes people").map { it.id }

        assertTrue(
            "fixture.GreetingService" in ids,
            "splitting GreetingService into words is what lets prose reach it, got $ids",
        )
    }

    @Test
    fun `an unquoted english word does not seed on a declaration that shares its name`() {
        val why = finder.find("this should run without crashing")
            .singleOrNull { it.id == "fixture.run()" }
            ?.why

        assertTrue(
            why == null || "seed:mentioned" !in why,
            "`run` in prose must not count as naming fixture.run(), got $why",
        )
    }

    @Test
    fun `a declaration found by both channels outranks one found by either`() {
        val seeds = finder.find("`FormalGreeter` and the greeter interface")
        val formal = seeds.indexOfFirst { it.id == "fixture.FormalGreeter" }

        assertEquals(0, formal, "the doubly-supported seed should rank first, got $seeds")
        assertEquals("seed:mentioned+seed:search", seeds[formal].why)
    }

    @Test
    fun `returns nothing when the task shares no vocabulary with the code`() {
        assertEquals(emptyList(), finder.find("update the CSS grid on the marketing page"))
    }

    @Test
    fun `ranking is stable across runs`() {
        val task = "the greeter should greet"

        assertEquals(finder.find(task), finder.find(task))
    }

    @Test
    fun `a spec name does not outseed the code it exercises`() {
        val seeds = SeedFinder(specNames).find("combine numbers together and get a wrong total")

        assertEquals(
            "fixture.specnames.Totals.combineNumbersTogether(kotlin.collections.List)",
            seeds.first().id,
            "the spec name matches the task almost word for word, which is why it must not seed " +
                "ahead of the code under test, got ${seeds.map { it.id }}",
        )
    }

    @Test
    fun `a test is still reachable as a search result`() {
        val found = SeedFinder(specNames).search("combine numbers together and get a wrong total")
            .map { specNames.symbols[it].id }

        assertTrue(
            found.any { specNames.byId.getValue(it).isTest },
            "the penalty applies to seeding only: tests are still ranked, and reach a pack " +
                "through the edge from the code they exercise",
        )
    }

    private companion object {
        val finder: SeedFinder by lazy { SeedFinder(index) }

        val specNames: CodeIndex by lazy {
            val root = Path.of(
                requireNotNull(SeedFinderTest::class.java.getResource("/fixtures/spec-names")).toURI(),
            )
            AnalysisApiIndexer(
                sourceRoots = listOf(root),
                repoRoot = root,
                testRoots = listOf(root.resolve("test")),
            ).use { it.index() }
        }

        val index: CodeIndex by lazy {
            val root = Path.of(
                requireNotNull(SeedFinderTest::class.java.getResource("/fixtures/greeter")).toURI(),
            )
            AnalysisApiIndexer(listOf(root)).use { it.index() }
        }
    }
}
