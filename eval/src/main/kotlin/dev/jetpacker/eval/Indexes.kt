package dev.jetpacker.eval

import dev.jetpacker.core.index.AnalysisApiIndexer
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.project.readGradleProject
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
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

        val index = index(checkout)
        cached.writeText(json.encodeToString(CodeIndex.serializer(), index))
        return Snapshot(checkout, index)
    }

    private fun checkout(sha: String): Path {
        val target = worktrees.resolve(sha)
        if (!target.isDirectory()) git(repo, "worktree", "add", "--detach", "--quiet", target.toString(), sha)
        return target
    }

    private fun index(checkout: Path): CodeIndex {
        // Source roots come from HEAD's model; map them onto this revision and drop any that a
        // module did not have yet.
        val roots = project.sourceRoots.mapNotNull { relocate(it, checkout) }
        return AnalysisApiIndexer(
            sourceRoots = roots,
            classpath = project.classpath,
            jdkHome = Path.of(System.getProperty("java.home")),
            repoRoot = checkout,
            testRoots = project.testRoots.mapNotNull { relocate(it, checkout) },
        ).use { it.index() }
    }

    private fun relocate(path: Path, checkout: Path): Path? =
        checkout.resolve(root.relativize(path.toRealPathOrSelf())).takeIf { it.isDirectory() }

    private fun Path.toRealPathOrSelf(): Path = runCatching { toRealPath() }.getOrDefault(this)

    private companion object {
        /**
         * Bump whenever what the indexer *produces* changes — symbol ids, signatures, edges.
         * A commit's SHA alone had keyed these files, so changing how a signature is built left
         * every cached index describing the old code with the new code's name for it, and a
         * benchmark run silently measured a mixture of the two.
         */
        const val SCHEMA = 2
    }
}
