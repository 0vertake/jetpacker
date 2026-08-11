package dev.jetpacker.eval

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Shells out to git. A library would buy nothing here: every call is plumbing around porcelain. */
fun git(repo: Path, vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git", *arguments))
        .directory(repo.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) { "git ${arguments.joinToString(" ")} hung" }
    check(process.exitValue() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
    return output
}

private const val GIT_TIMEOUT_MINUTES = 5L
