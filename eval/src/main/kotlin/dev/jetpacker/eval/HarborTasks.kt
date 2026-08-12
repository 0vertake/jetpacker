package dev.jetpacker.eval

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Tasks from the Kotlin Benchmark ([Kotlin/kotlin-swe-bench](https://github.com/Kotlin/kotlin-swe-bench)),
 * JetBrains' 105-task Harbor suite.
 *
 * Worth the extra source because it fixes the sharpest limitation of mining git history: a commit
 * message is written by the person who already found the code, and often names it. A Harbor task's
 * text is the *issue* — a bug report written by someone who did not know where the fix would go —
 * which is the input a retriever will actually be given.
 *
 * Only the parts that Level 1 needs are read: the issue, the gold patch and the base commit. The
 * Docker environment and the test verifier belong to Level 2 and are ignored here, so no container
 * runs and the suite works from a plain checkout of the benchmark repository.
 */
fun harborTasks(tasksDir: Path, repository: String, limit: Int = Int.MAX_VALUE): List<Task> =
    tasksDir.listDirectoryEntries()
        .filter { it.isDirectory() }
        .sortedBy { it.fileName.toString() }
        .mapNotNull { task -> taskFor(task, repository) }
        .take(limit)

private fun taskFor(task: Path, repository: String): Task? {
    val metadata = task.resolve("task.toml").readTextOrNull() ?: return null
    if (TOML_REPOSITORY.find(metadata)?.groupValues?.get(1) != repository) return null

    val issue = task.resolve("instruction.md").readTextOrNull() ?: return null
    val base = task.resolve("environment/prepare.sh").readTextOrNull()
        ?.let { CHECKOUT.find(it)?.groupValues?.get(1) }
        ?: return null

    // The gold patch is the fix alone; Harbor keeps the regression test in a separate patch, which
    // is the split this benchmark wants anyway — a test written with the fix is not findable from
    // the issue that preceded both.
    val changed = changedLines(task.resolve("solution/fix.patch").readTextOrNull() ?: return null)
        .filterKeys { it.endsWith(".kt") && "/test" !in it && "/androidTest" !in it }
    if (changed.isEmpty()) return null

    return Task(
        id = task.fileName.toString(),
        text = issue,
        baseCommit = base,
        changedLines = changed,
    )
}

private fun Path.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

private val TOML_REPOSITORY = Regex("""repository_name\s*=\s*"([^"]+)"""")
private val CHECKOUT = Regex("""git checkout ([0-9a-f]{7,40})""")
