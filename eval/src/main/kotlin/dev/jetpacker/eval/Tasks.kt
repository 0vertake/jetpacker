package dev.jetpacker.eval

import java.nio.file.Path

/**
 * A retrieval task: what was asked, and where the code stood when it was asked.
 *
 * [changedLines] are line numbers in the *parent* commit — the state a retriever sees — because
 * those are what locate the declarations the fix went on to touch.
 */
data class Task(
    val id: String,
    val text: String,
    val baseCommit: String,
    val changedLines: Map<String, Set<Int>>,
)

/**
 * Mines tasks from a repository's own history.
 *
 * This is how SWE-bench itself is built — commit message as the request, diff as ground truth —
 * and doing it from local history rather than a published dataset means no network, no dataset
 * format, and it works on any repo. The cost is that commit messages are terser than issue
 * threads, which makes these tasks *harder* than Kotlin-SWE-bench's, not easier.
 */
fun mineTasks(repo: Path, limit: Int = 40, maxFiles: Int = 5): List<Task> =
    git(repo, "log", "--no-merges", "--format=%H", "-n", "$COMMIT_SCAN", "--", "*.kt")
        .lines()
        .filter { it.isNotBlank() }
        .mapNotNull { sha -> taskFor(repo, sha, maxFiles) }
        .take(limit)

private fun taskFor(repo: Path, sha: String, maxFiles: Int): Task? {
    val message = git(repo, "log", "-1", "--format=%s%n%n%b", sha).trim()
    if (message.length < MIN_MESSAGE) return null

    val changed = changedLines(git(repo, "show", "--format=", "--unified=0", sha, "--", "*.kt"))
        // Tests are not the target of localization; a fix's own regression test is not something a
        // retriever could have found from the issue text.
        .filterKeys { "/test" !in it && "/androidTest" !in it }
    if (changed.isEmpty() || changed.size > maxFiles) return null

    return Task(
        id = sha.take(SHORT_SHA),
        text = message,
        baseCommit = "$sha^",
        changedLines = changed,
    )
}

/**
 * Pre-image line numbers per file, from a `--unified=0` diff.
 *
 * A hunk that only adds lines has no pre-image span; the insertion point still tells us which
 * declaration grew, so it is recorded as the lines either side of the seam.
 */
internal fun changedLines(diff: String): Map<String, Set<Int>> {
    val changed = mutableMapOf<String, MutableSet<Int>>()
    var file: String? = null

    for (line in diff.lines()) {
        when {
            line.startsWith("--- a/") -> file = line.removePrefix("--- a/")
            line.startsWith("--- /dev/null") -> file = null
            line.startsWith("@@") -> {
                val current = file ?: continue
                val hunk = HUNK.find(line) ?: continue
                val start = hunk.groupValues[1].toInt()
                val count = hunk.groupValues[2].ifEmpty { "1" }.toInt()
                val lines = if (count == 0) setOf(start, start + 1) else (start until start + count).toSet()
                changed.getOrPut(current) { mutableSetOf() } += lines
            }
        }
    }
    return changed
}

private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? """)
private const val COMMIT_SCAN = 400
private const val MIN_MESSAGE = 30
private const val SHORT_SHA = 10
