package dev.jetpacker.core.pack

import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol

/**
 * Renders a [Pack] as Markdown.
 *
 * Layout is fixed and ordering is stable (docs/plan.md §5) so that two runs over the same repo
 * state produce byte-identical text — which is what lets an agent's prompt cache hit.
 *
 * Every item carries its `why`, which is what makes a pack auditable rather than a pile of code.
 */
fun Pack.toMarkdown(): String = buildString {
    append(header(tokens, budget, items.size, coverage))
    section("Definitions", items.filter { it.fidelity == Fidelity.FULL && !it.symbol.isTest })
    section("Related signatures", items.filter { it.fidelity == Fidelity.STUB && !it.symbol.isTest })
    section("Tests likely affected", items.filter { it.symbol.isTest })
}.trimEnd() + "\n"

internal fun header(
    tokens: Int,
    budget: Int,
    count: Int,
    coverage: ResolutionCoverage = ResolutionCoverage(0, 0, 0),
): String = buildString {
    append("# Context pack\n\n$tokens / $budget tokens, $count declarations.")
    if (coverage.callSites > 0 || coverage.failedFiles > 0) {
        append(' ').append(coverage.asPackLine()).append('.')
    }
    append('\n')
}

/**
 * One item exactly as it appears in the pack.
 *
 * The packer charges the budget for this whole string rather than for the code alone: headings,
 * paths and fences are tokens the model pays for too, and on a real repository they came to more
 * than the code itself.
 *
 * Hence two shapes. A body needs a heading and a fence to be readable, but wrapping a
 * one-line signature the same way spent more tokens on scaffolding than on the signature —
 * measured on detekt, compacting stubs bought more recall than any ranking change.
 *
 * A stub does not repeat its path either: on detekt a path alone tokenized to more than the
 * signature it was annotating, so stubs are grouped under [fileHeading] and name only their line.
 */
internal fun block(symbol: Symbol, why: String, text: String, fidelity: Fidelity): String =
    when (fidelity) {
        Fidelity.FULL -> buildString {
            appendLine("### ${symbol.fqName}")
            appendLine("`${symbol.file}:${symbol.startLine}` — $why")
            appendLine()
            appendLine("```kotlin")
            appendLine(text)
            appendLine("```")
        }
        Fidelity.STUB -> "- ${symbol.startLine}: ${text.oneLine()} — $why\n"
    }

/** Printed once per file per section; the packer charges the budget for it exactly once too. */
internal fun fileHeading(file: String): String = "\n`$file`\n"

/** Signatures wrap across lines in source; a stub is only worth its cost as one line. */
private fun String.oneLine(): String = lines().joinToString(" ") { it.trim() }.replace(Regex(" +"), " ")

private fun StringBuilder.section(title: String, items: List<PackItem>) {
    if (items.isEmpty()) return
    appendLine()
    appendLine("## $title")

    val (bodies, stubs) = items.partition { it.fidelity == Fidelity.FULL }
    for (item in bodies) {
        appendLine()
        append(block(item.symbol, item.why, item.text, item.fidelity))
    }
    // groupBy keeps rank order, both of the files and of the stubs within each file.
    for ((file, group) in stubs.groupBy { it.symbol.file }) {
        append(fileHeading(file))
        for (item in group) append(block(item.symbol, item.why, item.text, item.fidelity))
    }
}
