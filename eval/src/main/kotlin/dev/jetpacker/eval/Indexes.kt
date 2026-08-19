package dev.jetpacker.eval

import dev.jetpacker.core.index.AnalysisApiIndexer
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.IndexPatch
import dev.jetpacker.core.project.readGradleProject
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json

/** A checkout of one base commit, plus the index built from it. */
data class Snapshot(val root: Path, val index: CodeIndex)

/**
 * Supplies an indexed checkout per base commit, cached on disk.
 *
 * Both halves have to persist. Indexing detekt takes about half a minute, which across forty
 * tasks is the difference between a benchmark you can iterate on and one you run twice a night;
 * and the worktree has to stay because packing reads declaration bodies out of the working tree,
 * at the revision the task was asked against.
 *
 * The Gradle model is read once, at HEAD, and its classpath reused for every commit.
 * ponytail: dependency sets barely move across a few hundred commits, and re-configuring someone
 * else's build per task would dominate the run. Re-read per commit if a repo's deps churn.
 */
class Indexes(private val repo: Path, cacheDir: Path) {
    private val worktrees = cacheDir.resolve("worktrees").also { it.createDirectories() }
    private val indexes = cacheDir.resolve("indexes/v$SCHEMA").also { it.createDirectories() }
    private val json = Json { ignoreUnknownKeys = true }

    private val project by lazy { readGradleProject(repo) }
    private val root by lazy { repo.toRealPath() }

    fun at(commit: String): Snapshot {
        val sha = git(repo, "rev-parse", commit).trim()
        val checkout = checkout(sha)
        val cached = indexes.resolve("$sha.json")

        if (cached.exists()) {
            return Snapshot(checkout, json.decodeFromString(CodeIndex.serializer(), cached.readText()))
        }

        val index = reuse(sha)?.let { (base, changed) -> patch(checkout, base, changed) } ?: index(checkout)
        cached.writeText(json.encodeToString(CodeIndex.serializer(), index))
        return Snapshot(checkout, index)
    }

    /**
     * The cached commit closest to [sha], with the Kotlin files that differ between them.
     *
     * Null when nothing cached is close enough to be worth patching: past about a third of the
     * repository the saved analysis stops covering the risk of reusing anything at all.
     */
    private fun reuse(sha: String): Pair<CodeIndex, Set<String>>? {
        val candidates = indexes.listDirectoryEntries("*.json").map { it.fileName.toString().removeSuffix(".json") }
        val nearest = candidates
            .mapNotNull { base -> changedFiles(base, sha)?.let { base to it } }
            .minByOrNull { (_, changed) -> changed.size }
            ?: return null

        val (base, changed) = nearest
        val index = runCatching {
            json.decodeFromString(CodeIndex.serializer(), indexes.resolve("$base.json").readText())
        }.getOrNull() ?: return null

        val files = index.symbols.mapTo(HashSet()) { it.file }
        if (!IndexPatch.worthReusing(changed.size, files.size)) return null
        return index to changed
    }

    /**
     * Re-analyzes what changed and keeps the rest of [base]. See [IndexPatch].
     */
    private fun patch(checkout: Path, base: CodeIndex, changed: Set<String>): CodeIndex =
        withIndexer(checkout) { indexer ->
            val fresh = timed("${changed.size} changed files") { indexer.index(changed) }
            val referrers = IndexPatch.referrersToRemoved(base, changed, fresh.byId.keys)
            val repaired = if (referrers.isEmpty()) {
                null
            } else {
                timed("${referrers.size} files that referenced something removed") { indexer.index(referrers) }
            }
            IndexPatch.merge(base, changed + referrers, fresh, repaired)
        }

    private fun timed(scope: String, build: () -> CodeIndex): CodeIndex {
        val started = System.nanoTime()
        return build().also {
            // A file the Analysis API threw on contributes nothing, so a run with many of them is
            // measuring a partial index and the results have to say so.
            val lost = it.coverage.failedFiles.takeIf { failed -> failed > 0 }?.let { failed -> ", $failed unanalyzable" }
            System.err.println("    indexed $scope in ${(System.nanoTime() - started) / 1_000_000_000}s${lost.orEmpty()}")
        }
    }

    /**
     * Both sides of a rename, since the old path's declarations have to leave the index.
     *
     * `*.kts` counts: build scripts are Kotlin files the indexer reads, and leaving them out of
     * the comparison kept a stale `build-logic` declaration in an otherwise identical index.
     */
    private fun changedFiles(from: String, to: String): Set<String>? = runCatching {
        git(repo, "diff", "--name-only", "--no-renames", from, to, "--", "*.kt", "*.kts")
            .lines()
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrNull()

    private fun checkout(sha: String): Path {
        val target = worktrees.resolve(sha)
        if (!target.isDirectory()) git(repo, "worktree", "add", "--detach", "--quiet", target.toString(), sha)
        return target
    }

    private fun index(checkout: Path): CodeIndex =
        withIndexer(checkout) { indexer -> timed("the whole repository", indexer::index) }

    private fun <T> withIndexer(checkout: Path, block: (AnalysisApiIndexer) -> T): T {
        // HEAD's model cannot see a module that existed at this revision and was later renamed,
        // so the checkout's own layout supplies the rest: without it, five of detekt's 28 Kotlin
        // Benchmark tasks had no indexed file to find and left the sample as unscorable.
        val conventional = conventionalRoots(checkout)
        val fromModel = project.sourceRoots.mapNotNull { relocate(it, checkout) }
        val fromModelTests = project.testRoots.mapNotNull { relocate(it, checkout) }

        return AnalysisApiIndexer(
            sourceRoots = (fromModel + conventional).distinct().sorted(),
            classpath = project.classpath,
            jdkHome = Path.of(System.getProperty("java.home")),
            repoRoot = checkout,
            testRoots = (fromModelTests + conventional.filter { it.isTestRoot() }).distinct().sorted(),
        ).use(block)
    }

    private fun relocate(path: Path, checkout: Path): Path? =
        checkout.resolve(root.relativize(path.toRealPathOrSelf())).takeIf { it.isDirectory() }

    private fun Path.toRealPathOrSelf(): Path = runCatching { toRealPath() }.getOrDefault(this)

    /** `<module>/src/<source set>/kotlin`, the layout every Gradle JVM module in these repos uses. */
    private fun conventionalRoots(checkout: Path): List<Path> =
        checkout.toFile().walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" }
            .filter { it.isDirectory && (it.name == "kotlin" || it.name == "java") }
            .filter { it.parentFile?.parentFile?.name == "src" }
            .map { it.toPath() }
            .toList()

    /** The source set is the directory above: `test`, but also `functionalTest`, `androidTest`. */
    private fun Path.isTestRoot(): Boolean =
        parent.fileName.toString().let { it == "test" || it.endsWith("Test") }

    private companion object {
        /**
         * Bump whenever what the indexer *produces* changes — symbol ids, signatures, edges.
         * A commit's SHA alone had keyed these files, so changing how a signature is built left
         * every cached index describing the old code with the new code's name for it, and a
         * benchmark run silently measured a mixture of the two.
         */
        const val SCHEMA = 4
    }
}
