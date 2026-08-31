package dev.jetpacker.eval

import dev.jetpacker.core.pack.Fidelity
import dev.jetpacker.core.pack.Pack

/**
 * Level 1.5: do the lines the gold patch touches appear in packed content?
 *
 * Symbol recall asks whether the right *declaration* is named. Edit-site recall asks whether the
 * model could edit without reading the file — the gap between the two explains many L2 failures
 * where retrieval "found" the target but the patch still missed.
 */
fun editSiteRecall(task: Task, pack: Pack, strict: Boolean = true): Double {
    val goldLines = task.changedLines.values.sumOf { it.size }
    if (goldLines == 0) return 0.0
    val covered = packedEditLines(pack, strict)
    val hits = task.changedLines.entries.sumOf { (file, lines) ->
        lines.count { it in covered.getOrElse(file) { emptySet() } }
    }
    return hits.toDouble() / goldLines
}

/** Union of source line numbers present in [pack] content. */
fun packedEditLines(pack: Pack, strict: Boolean): Map<String, Set<Int>> {
    val byFile = HashMap<String, MutableSet<Int>>()
    val seenChunks = HashSet<String>()

    for (item in pack.items) {
        if (item.why.startsWith(CHUNK_PREFIX)) {
            if (item.why in seenChunks) continue
            seenChunks += item.why
            chunkWindow(item.why)?.let { (file, range) -> addRange(byFile, file, range) }
            continue
        }
        when (item.fidelity) {
            Fidelity.FULL -> addRange(byFile, item.symbol.file, item.symbol.startLine..item.symbol.endLine)
            Fidelity.STUB -> if (!strict) addRange(byFile, item.symbol.file, item.symbol.startLine..item.symbol.startLine)
        }
    }
    return byFile
}

private fun chunkWindow(why: String): Pair<String, IntRange>? {
    // chunk:<file>:<start>:<end> — file may contain ':' on Windows but not in this corpus.
    val body = why.removePrefix(CHUNK_PREFIX)
    val lastColon = body.lastIndexOf(':')
    if (lastColon <= 0) return null
    val end = body.substring(lastColon + 1).toIntOrNull() ?: return null
    val rest = body.substring(0, lastColon)
    val midColon = rest.lastIndexOf(':')
    if (midColon <= 0) return null
    val start = rest.substring(midColon + 1).toIntOrNull() ?: return null
    val file = rest.substring(0, midColon)
    return file to (start..end)
}

private fun addRange(byFile: HashMap<String, MutableSet<Int>>, file: String, range: IntRange) {
    val set = byFile.getOrPut(file) { HashSet() }
    for (line in range) set += line
}

private const val CHUNK_PREFIX = "chunk:"
