package dev.jetpacker.cli

import dev.jetpacker.baselines.Embedder
import dev.jetpacker.baselines.EmbeddingSeeds
import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.pack.toMarkdown
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

private const val USAGE = """
Usage: packer pack --repo <dir> --task <file|-> [--budget 4000] [--embed-seeds]
       packer serve --repo <dir> [--embed-seeds]

  pack   print one pack and exit
  serve  an MCP server on stdio: get_context_pack and explain_context_pack

  --repo         a Gradle project to index
  --task         task description; - reads stdin
  --budget       token budget for the pack (default 4000)
  --embed-seeds  also rank seeds by MiniLM (downloads ~90MB on first use)
"""

/**
 * Deliberately hand-rolled argument parsing: two subcommands with a handful of flags do not
 * justify a CLI framework, and the eval harness calls [Jetpacker] directly rather than going
 * through here.
 */
fun main(args: Array<String>) {
    val options = parse(args)

    if (options.command == "serve") {
        serve(options.repo, options.embedSeeds)
        exitProcess(0)
    }

    val text = if (options.task == "-") {
        generateSequence(::readLine).joinToString("\n")
    } else {
        Path.of(options.task!!).readText()
    }

    System.err.println("indexing ${options.repo} ...")
    val packer = jetpacker(options.repo, options.embedSeeds)
    System.err.println("indexed ${packer.index.symbols.size} declarations")

    println(packer.pack(text, options.budget).toMarkdown())

    // The IntelliJ platform leaves non-daemon threads behind, so the JVM will not exit on its own.
    exitProcess(0)
}

internal data class Options(
    val command: String,
    val repo: Path,
    val task: String?,
    val budget: Int,
    val embedSeeds: Boolean,
)

internal fun parse(args: Array<String>): Options {
    val command = args.firstOrNull()
    if (command != "pack" && command != "serve") fail(USAGE.trim())

    val rest = args.drop(1)
    val embedSeeds = "--embed-seeds" in rest
    val flags = rest.filter { it != "--embed-seeds" }
        .chunked(2)
        .onEach { if (it.size != 2) fail("missing value for ${it.first()}\n\n${USAGE.trim()}") }
        .associate { (flag, value) -> flag to value }

    val repo = Path.of(flags["--repo"] ?: fail("--repo is required\n\n${USAGE.trim()}"))
    val task = if (command == "pack") {
        flags["--task"] ?: fail("--task is required\n\n${USAGE.trim()}")
    } else {
        null
    }
    val budget = flags["--budget"]?.toIntOrNull() ?: Retriever.DEFAULT_BUDGET
    return Options(command, repo, task, budget, embedSeeds)
}

internal fun jetpacker(repo: Path, embedSeeds: Boolean): Jetpacker {
    val packer = Jetpacker.forRepository(repo)
    if (!embedSeeds) return packer
    System.err.println("ranking seeds with MiniLM ...")
    return Jetpacker(repo, packer.index, dense = EmbeddingSeeds(packer.index, Embedder()))
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
