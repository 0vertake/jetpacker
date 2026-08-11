package dev.jetpacker.baselines

import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Edge
import dev.jetpacker.core.index.EdgeKind
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameMatchedIndexTest {
    @Test
    fun `links a call to every declaration that shares the name`() {
        val edges = nameMatchedIndex(index, root).edges.filter { it.kind == EdgeKind.CALLS }

        assertEquals(
            setOf("a.Printer.format", "b.Report.format"),
            edges.filter { it.from == "a.Caller.run" }.map { it.to }.toSet(),
            "without resolution `format(x)` names both declarations and cannot say which was meant",
        )
    }

    @Test
    fun `attributes a call to the innermost declaration around it`() {
        val edges = nameMatchedIndex(index, root).edges

        assertTrue(
            edges.none { it.from == "a.Caller" && it.kind == EdgeKind.CALLS },
            "the call is inside `run`, so crediting the enclosing class too would double-count it",
        )
    }

    @Test
    fun `keeps the resolved relations it is not ablating`() {
        val rebuilt = nameMatchedIndex(index, root)

        assertTrue(
            Edge("a.Caller", "a.Caller.run", EdgeKind.CONTAINS) in rebuilt.edges,
            "only calls are degraded; containment is syntax a parser gets right",
        )
    }

    @Test
    fun `skips a name declared in too many places to identify anything`() {
        val edges = nameMatchedIndex(index, root, maxDefinitions = 1).edges

        assertTrue(
            edges.none { it.kind == EdgeKind.CALLS && it.to.endsWith("format") },
            "two `format` declarations exceed the cap, so the ambiguous name is dropped entirely",
        )
    }

    private val root: Path by lazy {
        createTempDirectory("jetpacker-names").also { directory ->
            directory.resolve("a").createDirectories()
            directory.resolve("b").createDirectories()
            directory.resolve("a/Caller.kt").writeText(
                """
                package a

                class Printer {
                    fun format(value: Int) = value.toString()
                }

                class Caller {
                    fun run() = format(1)
                }
                """.trimIndent() + "\n",
            )
            directory.resolve("b/Report.kt").writeText(
                """
                package b

                class Report {
                    fun format(value: Int) = "report ${'$'}value"
                }
                """.trimIndent() + "\n",
            )
        }
    }

    /** Hand-built rather than resolved: the ablation's input is an index, whatever produced it. */
    private val index: CodeIndex by lazy {
        CodeIndex(
            symbols = listOf(
                symbol("a.Printer", "Printer", "a/Caller.kt", 3, 5),
                symbol("a.Printer.format", "format", "a/Caller.kt", 4, 4),
                symbol("a.Caller", "Caller", "a/Caller.kt", 7, 9),
                symbol("a.Caller.run", "run", "a/Caller.kt", 8, 8),
                symbol("b.Report", "Report", "b/Report.kt", 3, 5),
                symbol("b.Report.format", "format", "b/Report.kt", 4, 4),
            ),
            edges = listOf(
                Edge("a.Caller", "a.Caller.run", EdgeKind.CONTAINS),
                Edge("a.Printer", "a.Printer.format", EdgeKind.CONTAINS),
                // The resolved answer, which this ablation is meant to throw away.
                Edge("a.Caller.run", "a.Printer.format", EdgeKind.CALLS),
            ),
            coverage = ResolutionCoverage(1, 1, 1),
        )
    }

    private fun symbol(id: String, name: String, file: String, start: Int, end: Int) = Symbol(
        id = id,
        fqName = id,
        name = name,
        kind = if (name.first().isUpperCase()) SymbolKind.CLASS else SymbolKind.FUNCTION,
        file = file,
        startLine = start,
        endLine = end,
        signature = "fun $name()",
        doc = null,
        tokens = 8,
        isTest = false,
    )
}
