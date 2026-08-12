package dev.jetpacker.eval

import dev.jetpacker.baselines.Bm25Retriever
import dev.jetpacker.baselines.ChunkRetriever
import dev.jetpacker.core.Jetpacker
import dev.jetpacker.core.Retriever
import dev.jetpacker.core.rank.EdgeWeights
import dev.jetpacker.core.pack.toMarkdown
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Level 2 (docs/plan.md §7 Phase 4): does a better pack produce a better patch?
 *
 * One model, one prompt, one shot. The only thing that changes between arms is the context block,
 * so a difference in how many tasks come out resolved is a difference the retrieval made. Tasks are
 * the ones [main] in `Certify.kt` proved can be resolved and can be failed; the scorer is the
 * suite's own verifier.
 *
 *     ./gradlew :eval:level2 -Pjetpacker.repo=/tmp/detekt \
 *       -Pjetpacker.harbor=/tmp/kotlin-swe-bench/tasks -Pjetpacker.harbor.repo=detekt
 *
 * Every arm spends its whole budget on bodies. At Level 1 a signature is enough to say retrieval
 * found the right declaration; here the model has to edit the thing, and no one can edit a
 * signature. That makes this a different question from the Level-1 tables, not a rerun of them.
 *
 * It also means no arm here is the shipped default, which gives bodies 15% of the budget because
 * that is what maximized recall (`Packer.DEFAULT_FULL_TIER_SHARE`). Whether recall-optimal packing
 * is also patch-optimal is a real question and this sample cannot answer it: nine tasks cannot
 * separate one arm from another. Any writeup of these numbers has to say which configuration ran.
 */
fun main() {
    val repo = Path.of(System.getProperty("jetpacker.repo") ?: error("set -Djetpacker.repo=<path>"))
    val harbor = Path.of(System.getProperty("jetpacker.harbor") ?: error("set -Djetpacker.harbor=<dir>"))
    val repository = System.getProperty("jetpacker.harbor.repo") ?: repo.fileName.toString()
    val budget = System.getProperty("jetpacker.budgets")?.toInt() ?: 4000
    val cache = Path.of(System.getProperty("jetpacker.cache") ?: "${System.getProperty("user.home")}/.jetpacker")
    val workspace = Path.of(System.getProperty("jetpacker.l2") ?: "${System.getProperty("user.home")}/.jetpacker-l2")
        .also { it.createDirectories() }
    val wanted = System.getProperty("jetpacker.tasks")?.toInt() ?: Int.MAX_VALUE

    val certified = certified(workspace.resolve("certified.tsv"))
    if (certified.isEmpty()) error("no certified tasks in $workspace; run :eval:certify first")

    val tasks = harborTasks(harbor, repository).filter { it.id in certified }.take(wanted)
    println("${tasks.size} certified tasks for $repository at $budget tokens")

    // `jetpacker.patcher` swaps the helper script, for a different backend or for a stub that
    // exercises the loop without spending a model call.
    val script = System.getProperty("jetpacker.patcher")?.let { Path.of(it) } ?: cursorScript()
    val patcher = CursorPatcher(python(workspace), script)
    val results = workspace.resolve("level2.tsv")
    if (!results.exists()) results.writeText("")
    val done = results.readLines().mapNotNull { it.split("\t").take(2).takeIf { row -> row.size == 2 } }
        .map { (task, arm) -> task to arm }.toSet()

    val indexes = Indexes(repo, cache)
    var silent = 0

    for (task in tasks) {
        val snapshot = runCatching { indexes.at(task.baseCommit) }.getOrElse {
            println("[${task.id}] skipped: ${it.message?.lines()?.first()}")
            continue
        }
        val verifier = Verifier(harbor.resolve(task.id), workspace.resolve(task.id).also { it.createDirectories() })
        if (!verifier.prepare()) {
            println("[${task.id}] image build failed")
            continue
        }

        for ((name, retriever) in arms(snapshot)) {
            if (task.id to name in done) continue
            val started = TimeSource.Monotonic.markNow()

            val pack = retriever?.pack(task.text, budget)
            val context = pack?.toMarkdown()
            // The prompt is a constant in source; the context is the whole experiment. Keep it beside
            // the verifier's logs so any resolved-count in the table can be read back to what caused it.
            context?.let { workspace.resolve(task.id).resolve("$name.context.md").writeText(it) }
            val patch = patcher.patch(task.text, context)
            val outcome = if (patch.isBlank()) Outcome.NO_ANSWER else verifier.verify(patch, name.replace(':', '-'))

            results.appendText("${task.id}\t$name\t$outcome\t${pack?.tokens ?: 0}\n")
            println("  [${task.id}] $name -> $outcome (${started.elapsedNow().inWholeSeconds.seconds})")

            // A model that answers nothing for a whole task's worth of arms is rate-limited, out of
            // credit or refusing, and the run is no longer measuring retrieval. Stop rather than
            // spend the night collecting zeros that a reader would take for a result.
            silent = if (outcome == Outcome.NO_ANSWER) silent + 1 else 0
            if (silent >= SILENT_LIMIT) error("$SILENT_LIMIT calls in a row answered nothing; see the reasons above")
        }
    }

    report(results)
    exitProcess(0)
}

/** One task's worth of arms: enough silence to mean the backend, not the packs. */
private const val SILENT_LIMIT = 4

/**
 * The comparison. `none` is the floor — what the model does from the issue alone — and without it a
 * resolved-percentage says nothing about whether any of this retrieval was worth doing.
 */
private fun arms(snapshot: Snapshot): List<Pair<String, Retriever?>> = listOf(
    "none" to null,
    "chunk-bm25" to ChunkRetriever(snapshot.index, snapshot.root),
    "bm25" to Bm25Retriever(snapshot.index, snapshot.root, "bm25", fullTierShare = 1.0),
    "jp" to Jetpacker(
        snapshot.root,
        snapshot.index,
        EdgeWeights(sameFile = 1.0),
        "jp",
        fullTierShare = 1.0,
        testShare = 0.1,
    ),
)

private fun certified(record: Path): Set<String> {
    if (!record.exists()) return emptySet()
    return record.readLines()
        .map { it.split("\t") }
        .filter { it.size >= 3 && it[1] == "RESOLVED" && it[2] == "UNRESOLVED" }
        .map { it.first() }
        .toSet()
}

private fun report(results: Path) {
    val rows = results.readLines().map { it.split("\t") }.filter { it.size >= 4 }
    if (rows.isEmpty()) {
        println("\nnothing scored")
        return
    }

    println("\n| arm | resolved | no answer | not applied | no verdict | tokens |")
    println("|-----|----------|-----------|-------------|------------|--------|")
    for ((arm, scored) in rows.groupBy { it[1] }.entries.sortedBy { it.key }) {
        println(
            "| %-4s | %3d/%-3d | %9d | %11d | %10d | %6d |".format(
                arm,
                scored.count { it[2] == "${Outcome.RESOLVED}" },
                scored.size,
                scored.count { it[2] == "${Outcome.NO_ANSWER}" },
                scored.count { it[2] == "${Outcome.NOT_APPLIED}" },
                scored.count { it[2] == "${Outcome.NO_VERDICT}" },
                scored.mapNotNull { it[3].toIntOrNull() }.average().toInt(),
            ),
        )
    }
}

/**
 * The interpreter that has the Cursor SDK. Kept beside the run's own state rather than in the
 * system Python, which does not have it:
 *
 *     python3 -m venv ~/.jetpacker-l2/venv && ~/.jetpacker-l2/venv/bin/pip install cursor-sdk
 */
private fun python(workspace: Path): Path {
    System.getProperty("jetpacker.python")?.let { return Path.of(it) }
    val venv = workspace.resolve("venv/bin/python")
    return if (venv.exists()) venv else Path.of("python3")
}
