package dev.jetpacker.eval

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Turns a task and the context an arm retrieved for it into a patch, or into nothing. */
fun interface Patcher {
    fun patch(task: String, pack: String?): String
}

/**
 * The control: the gold patch, whatever it was asked for.
 *
 * Every arm's plumbing — prompt, apply, verify, score — runs the same way for this one, so a run
 * that scores zero everywhere can be told apart from a harness that is broken.
 */
class OraclePatcher(private val fix: String) : Patcher {
    override fun patch(task: String, pack: String?) = fix
}

/**
 * A Cursor agent, one shot, in an empty directory (see `cursor_patch.py`).
 *
 * Cursor rather than a vendor API because it costs nothing to run against an existing subscription.
 * The trade is that the model is not pinned the way `gpt-x-2026-01-01` is pinned, so this measures
 * whether the Level-1 gap survives into patches, and does not produce a number to put beside
 * published SWE-bench scores.
 */
class CursorPatcher(private val python: Path, private val script: Path, private val timeout: Long = 600) : Patcher {
    override fun patch(task: String, pack: String?): String {
        val empty = createTempDirectory("jetpacker-l2-")
        val complaints = createTempFile("cursor_patch", ".err")
        try {
            val process = ProcessBuilder(python.toString(), script.toString())
                .directory(empty.toFile())
                .redirectError(complaints.toFile())
                .start()

            process.outputStream.bufferedWriter().use { it.write(prompt(task, pack)) }
            val reply = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ""
            }

            if (process.exitValue() != 0) {
                val reason = complaints.readText().trim().ifEmpty { "no reason given" }
                // A backend that never started — no key, no SDK, wrong interpreter — must not read as a
                // model with nothing to say: every arm scores zero and the table looks plausible.
                if (process.exitValue() == UNUSABLE) error("the patcher could not run: $reason")
                // A refusal, a rate limit or a dropped run is per-call, but it has to leave a reason
                // behind or a night of them looks like retrieval that taught the model nothing.
                println("    no answer: ${reason.lines().last()}")
            }
            return diffIn(reply)
        } finally {
            empty.toFile().deleteRecursively()
        }
    }

    private companion object {
        /** `cursor_patch.py` exits with this when it never reached the model at all. */
        const val UNUSABLE = 1

        /**
         * Identical for every arm but the context block, which is the only thing being compared.
         * A missing block, rather than an empty one, is the no-context floor.
         */
        fun prompt(task: String, pack: String?) = buildString {
            appendLine("Fix the issue below in a Kotlin repository.")
            appendLine()
            appendLine("<issue>")
            appendLine(task.trim())
            appendLine("</issue>")
            if (pack != null) {
                appendLine()
                appendLine("<context>")
                appendLine(pack.trim())
                appendLine("</context>")
            }
            appendLine()
            appendLine("Reply with a unified diff and nothing else: no explanation, no fenced block.")
            appendLine("It must apply with `git apply` from the repository root, so use real paths in")
            appendLine("`diff --git a/<path> b/<path>` headers and correct @@ hunk lines.")
            appendLine("Do not create or edit any file on disk; the diff in your reply is the whole answer.")
        }
    }
}

/**
 * The diff out of whatever the agent said around it. Asking for a bare diff does not reliably get
 * one, and a reply wrapped in prose is a formatting failure rather than a wrong fix.
 */
internal fun diffIn(reply: String): String {
    val body = FENCED.find(reply)?.groupValues?.get(1) ?: reply
    val start = body.indexOf("diff --git ")
    if (start < 0) return ""
    return body.substring(start).trimEnd() + "\n"
}

private val FENCED = Regex("""```(?:diff|patch)?\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)

/** Unpacks the Python helper next to the run, so the harness works from a jar as well as a checkout. */
fun cursorScript(): Path {
    val body = object {}.javaClass.getResourceAsStream("/cursor_patch.py")?.bufferedReader()?.readText()
        ?: error("cursor_patch.py is missing from the eval resources")
    return createTempFile("cursor_patch", ".py").also { it.writeText(body) }
}
