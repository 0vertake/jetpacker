package dev.jetpacker.core.index

/**
 * Re-analyzes what changed and keeps the rest of a cached [CodeIndex].
 *
 * Two passes, because an edge is only stale if what it points at is gone. The first analyzes
 * the files the diff touched; the second analyzes whichever files referenced a declaration
 * that pass one just proved no longer exists, since their edges now name an identifier nothing
 * declares.
 *
 * What neither pass can see is a call that did not resolve before and now resolves, to a
 * declaration a changed file has just added. Those edges stay missing until the next full index.
 */
object IndexPatch {
    /** Above this share of indexed files, patching a cached index stops being worth it. */
    const val REUSE_LIMIT = 0.35

    /**
     * Unchanged files whose edges point at a declaration the re-analysis found to be gone.
     *
     * These are the only unchanged files that can hold a stale edge: an edge naming a declaration
     * that still exists still describes it correctly, however much its body moved.
     */
    fun referrersToRemoved(base: CodeIndex, changed: Set<String>, survivors: Set<String>): Set<String> {
        val fileOf = base.symbols.associate { it.id to it.file }
        val removed = base.symbols.filter { it.file in changed }.map { it.id }.toSet() - survivors
        return base.edges.filter { it.to in removed }.mapNotNull { fileOf[it.from] }.toSet() - changed
    }

    fun merge(base: CodeIndex, dirty: Set<String>, fresh: CodeIndex, repaired: CodeIndex? = null): CodeIndex {
        val fileOf = base.symbols.associate { it.id to it.file }
        val rebuilt = fresh.symbols + repaired?.symbols.orEmpty()
        val rebuiltEdges = fresh.edges + repaired?.edges.orEmpty()
        return CodeIndex(
            symbols = (base.symbols.filterNot { it.file in dirty } + rebuilt)
                .sortedWith(compareBy({ it.id }, { it.file }, { it.startLine })),
            edges = (base.edges.filterNot { fileOf[it.from] in dirty } + rebuiltEdges)
                .distinct()
                .sortedWith(compareBy({ it.kind }, { it.from }, { it.to })),
            coverage = fresh.coverage,
        )
    }

    fun worthReusing(changed: Int, indexedFiles: Int): Boolean =
        indexedFiles > 0 && changed <= indexedFiles * REUSE_LIMIT
}
