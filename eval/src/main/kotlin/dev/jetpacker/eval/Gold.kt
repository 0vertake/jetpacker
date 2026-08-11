package dev.jetpacker.eval

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Symbol

/**
 * The declarations a fix actually touched — ground truth for Level-1 retrieval scoring.
 *
 * Only the innermost match counts. A patch inside one method intersects that method *and* its
 * class and file; crediting all three would let a retriever score by returning whole files, which
 * is exactly the lazy behaviour the benchmark exists to distinguish from real localization.
 */
fun goldSymbols(index: CodeIndex, task: Task): Set<String> {
    val touched = index.symbols.filter { symbol ->
        task.changedLines[symbol.file]?.any { it in symbol.startLine..symbol.endLine } == true
    }
    val enclosing = touched.filter { candidate ->
        touched.any { it !== candidate && it.enclosedBy(candidate) }
    }.mapTo(HashSet()) { it.id }
    return touched.filterNot { it.id in enclosing }.mapTo(HashSet()) { it.id }
}

private fun Symbol.enclosedBy(outer: Symbol): Boolean =
    file == outer.file && startLine >= outer.startLine && endLine <= outer.endLine
