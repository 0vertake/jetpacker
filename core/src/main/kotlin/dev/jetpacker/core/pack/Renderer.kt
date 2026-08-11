package dev.jetpacker.core.pack

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
    append(header(tokens, budget, items.size))
    section("Definitions", items.filter { it.fidelity == Fidelity.FULL && !it.symbol.isTest })
    section("Related signatures", items.filter { it.fidelity == Fidelity.STUB && !it.symbol.isTest })
    section("Tests likely affected", items.filter { it.symbol.isTest })
}.trimEnd() + "\n"

internal fun header(tokens: Int, budget: Int, count: Int): String =
    "# Context pack\n\n$tokens / $budget tokens, $count declarations.\n"

/**
 * One item exactly as it appears in the pack.
 *
 * The packer charges the budget for this whole string rather than for the code alone: headings,
 * paths and fences are tokens the model pays for too, and on a real repository they came to more
 * than the code itself.
 */
internal fun block(symbol: Symbol, why: String, text: String): String =
    buildString {
        appendLine("### ${symbol.fqName}")
        appendLine("`${symbol.file}:${symbol.startLine}` — $why")
        appendLine()
        appendLine("```kotlin")
        appendLine(text)
        appendLine("```")
    }

private fun StringBuilder.section(title: String, items: List<PackItem>) {
    if (items.isEmpty()) return
    appendLine()
    appendLine("## $title")
    for (item in items) {
        appendLine()
        append(block(item.symbol, item.why, item.text))
    }
}
