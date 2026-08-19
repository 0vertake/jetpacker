package dev.jetpacker.core.index

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A patched index has to match a full one on the cases the two-pass update can see: a body
 * edit, and a removed declaration whose callers still mention it.
 */
class IncrementalIndexTest {
    @Test
    fun `a body edit matches a full re-index`() {
        val root = repo("fun used() = 1")
        val base = index(root)
        assertEquals("Used.kt", base.symbols.first { it.name == "used" }.file)
        root.resolve("Used.kt").writeText("package inc\n\nfun used() = 2\n")

        val patched = AnalysisApiIndexer(listOf(root), repoRoot = root).use { indexer ->
            IndexPatch.merge(base, setOf("Used.kt"), indexer.index(setOf("Used.kt")))
        }

        assertEquals(index(root).symbols, patched.symbols)
        assertEquals(index(root).edges, patched.edges)
    }

    @Test
    fun `re-analyzes the caller of a removed declaration`() {
        val root = repo("fun used() = 1")
        val base = index(root)
        root.resolve("Used.kt").writeText("package inc\nfun other() = 1\n")

        val patched = AnalysisApiIndexer(listOf(root), repoRoot = root).use { indexer ->
            val fresh = indexer.index(setOf("Used.kt"))
            val referrers = IndexPatch.referrersToRemoved(base, setOf("Used.kt"), fresh.byId.keys)
            assertEquals(setOf("User.kt"), referrers)
            IndexPatch.merge(base, setOf("Used.kt") + referrers, fresh, indexer.index(referrers))
        }

        assertEquals(index(root).symbols, patched.symbols)
        assertEquals(index(root).edges, patched.edges)
    }

    @Test
    fun `reuses a cached index when the sources have not changed`() {
        val root = repo("fun used() = 1")
        val cache = createTempDirectory("jetpacker-cache")
        val first = IndexCache.loadOrIndex(root, listOf(root), cacheDir = cache)
        val second = IndexCache.loadOrIndex(root, listOf(root), cacheDir = cache)

        assertEquals(first, second)
    }

    @Test
    fun `patches the cache when one file changes`() {
        val root = repo("fun used() = 1")
        val cache = createTempDirectory("jetpacker-cache")
        IndexCache.loadOrIndex(root, listOf(root), cacheDir = cache)
        root.resolve("Used.kt").writeText("package inc\n\nfun used() = 2\n")

        val patched = IndexCache.loadOrIndex(root, listOf(root), cacheDir = cache)
        val full = AnalysisApiIndexer(listOf(root), repoRoot = root).use { it.index() }

        assertEquals(full.symbols, patched.symbols)
        assertEquals(full.edges, patched.edges)
    }

    private companion object {
        fun repo(usedBody: String) = createTempDirectory("jetpacker-inc").also { root ->
            root.resolve("Used.kt").writeText("package inc\n$usedBody\n")
            root.resolve("User.kt").writeText("package inc\nfun user() = used()\n")
            // Two inert files so a one-file edit stays under [IndexPatch.REUSE_LIMIT].
            root.resolve("Pad0.kt").writeText("package inc\nfun pad0() = 0\n")
            root.resolve("Pad1.kt").writeText("package inc\nfun pad1() = 1\n")
        }

        fun index(root: Path) =
            AnalysisApiIndexer(listOf(root), repoRoot = root).use { it.index() }
    }
}
