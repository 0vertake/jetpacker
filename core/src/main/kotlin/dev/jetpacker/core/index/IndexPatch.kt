package dev.jetpacker.core.index

/**
 * Re-analyzes what changed and keeps the rest of a cached [CodeIndex].
 *
 * Two passes, because an edge is only stale if what it points at is gone. The first analyzes
 * the files the diff touched; the second analyzes whichever files referenced a declaration
 * that pass one just proved no longer exists, since their edges now name an identifier nothing
 * declares.
 *
 * A third pass re-analyzes files that had an unresolved call whose *name* a changed file just
 * declared. Overloads of a name that already existed still wait for a full index.
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

    /**
     * Unchanged files that called a name nothing declared, if a changed file has just declared it.
     */
    fun referrersToAdded(base: CodeIndex, changed: Set<String>, fresh: CodeIndex): Set<String> {
        val added = fresh.symbols.map { it.name }.toSet() - base.symbols.map { it.name }.toSet()
        if (added.isEmpty()) return emptySet()
        return base.errors.mapNotNull { error ->
            if (error.file in changed) return@mapNotNull null
            val name = unresolvedCallee(error.message) ?: return@mapNotNull null
            error.file.takeIf { name in added }
        }.toSet()
    }

    fun merge(base: CodeIndex, dirty: Set<String>, fresh: CodeIndex, repaired: CodeIndex? = null): CodeIndex {
        val fileOf = base.symbols.associate { it.id to it.file }
        val rebuilt = fresh.symbols + repaired?.symbols.orEmpty()
        val rebuiltEdges = fresh.edges + repaired?.edges.orEmpty()
        val byFile = (base.coverageByFile.filterKeys { it !in dirty } +
            fresh.coverageByFile +
            repaired?.coverageByFile.orEmpty())
            .toSortedMap()
        return CodeIndex(
            symbols = (base.symbols.filterNot { it.file in dirty } + rebuilt)
                .sortedWith(compareBy({ it.id }, { it.file }, { it.startLine })),
            edges = (base.edges.filterNot { fileOf[it.from] in dirty } + rebuiltEdges)
                .distinct()
                .sortedWith(compareBy({ it.kind }, { it.from }, { it.to })),
            coverage = byFile.values.fold(ResolutionCoverage(0, 0, 0)) { a, b -> a + b },
            errors = (base.errors.filterNot { it.file in dirty } + fresh.errors + repaired?.errors.orEmpty())
                .sortedWith(compareBy({ it.file }, { it.line }, { it.message })),
            coverageByFile = byFile,
        )
    }

    fun worthReusing(changed: Int, indexedFiles: Int): Boolean =
        indexedFiles > 0 && changed <= indexedFiles * REUSE_LIMIT

    private fun unresolvedCallee(message: String): String? {
        if (!message.startsWith(UNRESOLVED_PREFIX) || !message.endsWith("`")) return null
        val text = message.removePrefix(UNRESOLVED_PREFIX).removeSuffix("`")
        return text.substringBefore('(').substringAfterLast('.').trim().takeIf { it.isNotEmpty() }
    }

    private const val UNRESOLVED_PREFIX = "unresolved call `"
}
