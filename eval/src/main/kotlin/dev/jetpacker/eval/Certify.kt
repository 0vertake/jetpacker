package dev.jetpacker.eval

import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Decides which tasks Level 2 is allowed to score, before any model is called.
 *
 * A resolved-percentage only means something if the task can be resolved and can be failed. So each
 * task is run twice against its own verifier: once with the gold patch, which must be scored
 * resolved, and once with nothing, which must not. Either result coming out wrong is a broken task
 * and not a model's fault — every one of the 105 tasks declares `[verifier] implemented = false`, so
 * this is not a formality.
 *
 *     ./gradlew :eval:certify -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks \
 *       -Pjetpacker.harbor.repo=detekt -Pjetpacker.tasks=3
 *
 * Each task costs two full runs of its test suite plus an image build, so this is an hours-long job
 * that resumes: a task already recorded is not run again.
 */
fun main() {
    val tasks = Path.of(System.getProperty("jetpacker.harbor") ?: error("set -Djetpacker.harbor=<dir>"))
    val repository = System.getProperty("jetpacker.harbor.repo")
    val wanted = System.getProperty("jetpacker.tasks")?.toInt() ?: Int.MAX_VALUE
    val workspace = Path.of(System.getProperty("jetpacker.l2") ?: "${System.getProperty("user.home")}/.jetpacker-l2")
        .also { it.createDirectories() }

    val record = workspace.resolve("certified.tsv")
    if (!record.exists()) record.writeText("")
    val done = record.readLines().mapNotNull { it.split("\t").firstOrNull() }.toSet()

    val candidates = tasks.listDirectoryEntries()
        .filter { it.isDirectory() && (repository == null || it.repositoryName() == repository) }
        .sortedBy { it.fileName.toString() }
        .take(wanted)

    println("${candidates.size} tasks for ${repository ?: "every repository"}, ${done.size} already recorded")

    for (task in candidates) {
        val id = task.fileName.toString()
        if (id in done) {
            println("[$id] recorded already")
            continue
        }

        val started = TimeSource.Monotonic.markNow()
        val verifier = Verifier(task, workspace.resolve(id).also { it.createDirectories() })
        if (!verifier.prepare()) {
            println("[$id] image build failed; see ${workspace.resolve(id).resolve("build.log")}")
            record.appendText("$id\tNO_IMAGE\t-\n")
            continue
        }

        // The gold patch first: if it does not resolve the task, nothing about the task is usable and
        // the second run is wasted time.
        val gold = verifier.verify(task.resolve("solution/fix.patch").readText(), "gold")
        val bare = if (gold == Outcome.RESOLVED) verifier.verify("", "bare") else Outcome.NO_VERDICT

        val certified = gold == Outcome.RESOLVED && bare == Outcome.UNRESOLVED
        record.appendText("$id\t$gold\t$bare\n")
        println(
            "[$id] gold=$gold bare=$bare ${if (certified) "certified" else "unusable"}" +
                " (${started.elapsedNow().inWholeSeconds.seconds})",
        )
    }

    val certified = record.readLines().count { it.split("\t").drop(1) == listOf("RESOLVED", "UNRESOLVED") }
    println("\n$certified tasks can be scored at Level 2; the record is $record")
    exitProcess(0)
}

private fun Path.repositoryName(): String? =
    runCatching { Regex("""repository_name\s*=\s*"([^"]+)"""").find(resolve("task.toml").readText()) }
        .getOrNull()?.groupValues?.get(1)
