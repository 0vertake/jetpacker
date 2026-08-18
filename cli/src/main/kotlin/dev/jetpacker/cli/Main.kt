package dev.jetpacker.cli

import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.pack.toMarkdown
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

private const val USAGE = """
Usage: packer pack --repo <dir> --task <file|-> [--budget 4000]
       packer serve --repo <dir>

  pack   print one pack and exit
  serve  an MCP server on stdio: get_context_pack and explain_context_pack

  --repo    a Gradle project to index
  --task    task description; - reads stdin
  --budget  token budget for the pack (default 4000)
"""

/**
 * Deliberately hand-rolled argument parsing: two subcommands with three flags do not justify a
 * CLI framework, and the eval harness calls [Jetpacker] directly rather than going through here.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    if (command != "pack" && command != "serve") fail(USAGE.trim())

    val flags = args.drop(1).chunked(2)
        .onEach { if (it.size != 2) fail("missing value for ${it.first()}\n\n${USAGE.trim()}") }
        .associate { (flag, value) -> flag to value }

    val repo = Path.of(flags["--repo"] ?: fail("--repo is required\n\n${USAGE.trim()}"))

    if (command == "serve") {
        serve(repo)
        exitProcess(0)
    }

    val task = flags["--task"] ?: fail("--task is required\n\n${USAGE.trim()}")
    val budget = flags["--budget"]?.toIntOrNull() ?: Retriever.DEFAULT_BUDGET

    val text = if (task == "-") generateSequence(::readLine).joinToString("\n") else Path.of(task).readText()

    System.err.println("indexing $repo ...")
    val packer = Jetpacker.forRepository(repo)
    System.err.println("indexed ${packer.index.symbols.size} declarations")

    println(packer.pack(text, budget).toMarkdown())

    // The IntelliJ platform leaves non-daemon threads behind, so the JVM will not exit on its own.
    exitProcess(0)
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
