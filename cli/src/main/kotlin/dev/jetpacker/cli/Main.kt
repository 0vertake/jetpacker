package dev.jetpacker.cli

import dev.jetpacker.baselines.Embedder
import dev.jetpacker.baselines.EmbeddingSeeds
import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.index.IndexCache
import dev.jetpacker.core.pack.toMarkdown
import dev.jetpacker.core.project.readGradleProject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
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

/**
 * Rebuilds from [IndexCache] on each call, keeping the same [Jetpacker] when the sources have
 * not changed. Gradle is re-imported only when a build file changes, which is how a new module
 * becomes visible without restarting the server.
 */
internal fun liveJetpacker(repo: Path, embedSeeds: Boolean): () -> Jetpacker {
    var project = readGradleProject(repo)
    var gradle = gradleFingerprint(repo)
    val embedder by lazy { Embedder() }
    var current: Jetpacker? = null
    return {
        val now = gradleFingerprint(repo)
        if (now != gradle) {
            System.err.println("reloading Gradle model")
            project = readGradleProject(repo)
            gradle = now
        }
        val index = IndexCache.loadOrIndex(
            repoRoot = repo,
            sourceRoots = project.sourceRoots,
            classpath = project.classpath,
            testRoots = project.testRoots,
        )
        val held = current
        if (held != null && held.index === index) {
            held
        } else {
            if (held != null) System.err.println("reindexed ${index.symbols.size} declarations")
            val dense = if (embedSeeds) {
                if (held == null) System.err.println("ranking seeds with MiniLM ...")
                EmbeddingSeeds(index, embedder)
            } else {
                null
            }
            Jetpacker(repo, index, dense = dense).also { current = it }
        }
    }
}

internal fun gradleFingerprint(repo: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val files = mutableListOf<Path>()
    if (Files.isDirectory(repo)) {
        Files.walk(repo).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.isGradleStamp() }.forEach { files += it }
        }
    }
    files.sortBy { it.toString() }
    for (file in files) {
        val relative = repo.relativize(file).toString().replace('\\', '/')
        digest.update(relative.toByteArray())
        digest.update(Files.readAllBytes(file))
    }
    return HexFormat.of().formatHex(digest.digest())
}

private fun Path.isGradleStamp(): Boolean {
    val name = fileName.toString()
    if (name !in GRADLE_STAMP_NAMES) return false
    return generateSequence(parent) { it.parent }.none {
        val dir = it.fileName?.toString() ?: return@none false
        dir == "build" || dir == ".gradle"
    }
}

private val GRADLE_STAMP_NAMES = setOf(
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
    "libs.versions.toml",
)

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
