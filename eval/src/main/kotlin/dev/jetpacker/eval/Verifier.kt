package dev.jetpacker.eval

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Level 2's ground truth (docs/plan.md §7 Phase 4): does a patch make the task's own tests pass?
 *
 * Every task in the Kotlin Benchmark ships a Docker environment and a verifier that classifies each
 * expected test by how its status moved — fail-to-pass for the bug, pass-to-pass for everything that
 * must not regress — and writes `1` or `0` to `reward.txt`. That script is the scorer here, run
 * exactly as the suite defines it. Level 2 is worth nothing if the judge is ours.
 *
 * Each run starts from the task image, which is already at the base commit with a warm Gradle cache,
 * and the container is thrown away afterwards, so one arm's patch cannot influence the next.
 */
class Verifier(private val task: Path, private val workspace: Path) {
    private val metadata = task.resolve("task.toml").readText()
    private val image = "jetpacker-l2/${task.fileName}"

    /** Where the repository sits inside the image, as the task's own `prepare.sh` puts it. */
    private val checkout = "/home/" + (TOML_REPOSITORY.find(metadata)?.groupValues?.get(1) ?: "repo")

    /**
     * Builds the task image, which runs the repository's whole test suite as a build step and so
     * takes the better part of half an hour the first time. Cached by Docker after that.
     */
    fun prepare(): Boolean {
        if (docker(workspace.resolve("inspect.log"), "image", "inspect", image) == 0) return true
        return docker(
            workspace.resolve("build.log"),
            "build", "-f", "$task/environment/Dockerfile", "-t", image, "$task/environment",
            timeout = seconds("build_timeout_sec", default = 1800),
        ) == 0
    }

    /**
     * Applies [patch] and runs the verifier. An empty patch runs the tests untouched, which is the
     * control that says whether the task is broken to begin with.
     */
    fun verify(patch: String, label: String): Outcome {
        val logs = workspace.resolve(label).also { it.createDirectories() }
        val handoff = logs.resolve("patch").also { it.createDirectories() }
        handoff.resolve("agent.diff").writeText(patch)

        // `git apply` failing is not a test failure, and must not be scored as one: a model that
        // returns prose instead of a diff has to be visible as exactly that.
        val script = buildString {
            append("set -e; cd $checkout; ")
            if (patch.isNotBlank()) append("git apply --whitespace=nowarn /patch/agent.diff || exit 66; ")
            append("bash /tests/test.sh")
        }

        val status = docker(
            logs.resolve("run.log"),
            "run", "--rm",
            "--cpus", (TOML_CPUS.find(metadata)?.groupValues?.get(1) ?: "4"),
            "--memory", "${TOML_MEMORY.find(metadata)?.groupValues?.get(1) ?: "8192"}m",
            "-v", "$task/tests:/tests:ro",
            "-v", "$handoff:/patch:ro",
            "-v", "$logs:/logs",
            image, "bash", "-c", script,
            timeout = seconds("timeout_sec", default = 900),
        )

        if (status == 66) return Outcome.NOT_APPLIED
        val reward = runCatching { logs.resolve("verifier/reward.txt").readText().trim() }.getOrNull()
        return when (reward) {
            "1" -> Outcome.RESOLVED
            "0" -> Outcome.UNRESOLVED
            // No reward file: the suite timed out, the container died, or the collector found no
            // JUnit XML. Not the same as a patch that failed its tests, and not scored as one.
            else -> Outcome.NO_VERDICT
        }
    }

    private fun seconds(key: String, default: Long): Long =
        Regex("""$key\s*=\s*([0-9.]+)""").find(metadata)?.groupValues?.get(1)?.toDouble()?.toLong() ?: default

    /**
     * Output goes to [log] rather than to a pipe this process reads: a Gradle build that hangs would
     * hold a `readText()` open for as long as it liked, and the timeout that is supposed to catch it
     * would never be reached. It is also the only record of why a run failed.
     */
    private fun docker(log: Path, vararg command: String, timeout: Long = 120): Int {
        val process = ProcessBuilder("docker", *command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return TIMED_OUT
        }
        return process.exitValue()
    }

    private companion object {
        const val TIMED_OUT = -1
        val TOML_REPOSITORY = Regex("""repository_name\s*=\s*"([^"]+)"""")
        val TOML_CPUS = Regex("""cpus\s*=\s*([0-9]+)""")
        val TOML_MEMORY = Regex("""memory_mb\s*=\s*([0-9]+)""")
    }
}

enum class Outcome {
    /** The verifier's own reward: every expected test landed in the category it had to. */
    RESOLVED,
    UNRESOLVED,

    /** The patch did not apply. Never counted as a failed fix. */
    NOT_APPLIED,

    /** No reward was written: timeout, crashed container, or no test report to read. */
    NO_VERDICT,
}
