package dev.jetpacker.baselines

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Edge
import dev.jetpacker.core.index.EdgeKind
import dev.jetpacker.core.index.Symbol
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * The same index with its call edges rebuilt from bare names — resolution switched off.
 *
 * This is the headline ablation of docs/plan.md §5. `seeds-only` answers whether expanding the graph
 * pays; this answers the question the project exists for: whether *resolved* edges beat the
 * name-matched ones a parser can produce without a compiler. Everything else is held constant —
 * the same declarations, the same seeds, the same ranker, the same packer — so a difference has one
 * cause.
 *
 * What a name-matched call edge cannot know is which declaration was meant. `format(x)` links to
 * every `format` in the repository, an overload set becomes one blur, and an interface method's
 * callers are indistinguishable from its implementations'. That is precisely the information the
 * Analysis API buys, and the cost of it is what this arm prices.
 *
 * Three choices make the arm stronger than a real tree-sitter pack would be, deliberately:
 *
 * - Declarations still come from the PSI index, so its node list is exactly right.
 * - [EdgeKind.CONTAINS], [EdgeKind.EXTENDS] and [EdgeKind.OVERRIDES] are kept as resolved. Only
 *   calls are degraded, which isolates the relation the ablation table showed matters most.
 * - A name that is declared in more than [MAX_DEFINITIONS] places is skipped rather than linked to
 *   all of them. Spraying rank across two hundred `visit` declarations is what resolution exists to
 *   avoid, so dropping those names flatters this arm.
 */
fun nameMatchedIndex(index: CodeIndex, repoRoot: Path, maxDefinitions: Int = MAX_DEFINITIONS): CodeIndex {
    val byName = index.symbols.groupBy { it.name }.filterValues { it.size <= maxDefinitions }
    val byFile = index.symbols.groupBy { it.file }

    val calls = HashSet<Edge>()
    for ((file, declarations) in byFile) {
        val lines = runCatching { repoRoot.resolve(file).readLines() }.getOrNull() ?: continue
        val enclosing = declarations.sortedBy { it.startLine }

        lines.forEachIndexed { at, line ->
            val lineNumber = at + 1
            val caller = innermost(enclosing, lineNumber) ?: return@forEachIndexed
            for (match in CALL.findAll(line)) {
                val called = match.groupValues[1]
                for (callee in byName[called].orEmpty()) {
                    if (callee.id != caller.id) calls += Edge(caller.id, callee.id, EdgeKind.CALLS)
                }
            }
        }
    }

    val kept = index.edges.filter { it.kind != EdgeKind.CALLS }
    return CodeIndex(
        symbols = index.symbols,
        edges = (kept + calls).sortedWith(compareBy({ it.kind }, { it.from }, { it.to })),
        coverage = index.coverage,
    )
}

/** The declaration a line belongs to: the innermost of those whose range covers it. */
private fun innermost(declarations: List<Symbol>, line: Int): Symbol? =
    declarations.filter { line in it.startLine..it.endLine }.maxByOrNull { it.startLine }

/** A name followed by an open parenthesis: as close to "a call" as text alone can get. */
private val CALL = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

private const val MAX_DEFINITIONS = 20
