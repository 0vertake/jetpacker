package dev.jetpacker.core.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * On-disk [CodeIndex] keyed by the checkout path, reused when Kotlin sources and the classpath
 * have not changed, patched via [IndexPatch] when only a few files have.
 */
object IndexCache {
    fun defaultDir(): Path = Path.of(System.getProperty("user.home"), ".jetpacker", "indexes", "v$SCHEMA")

    fun loadOrIndex(
        repoRoot: Path,
        sourceRoots: List<Path>,
        classpath: List<Path> = emptyList(),
        testRoots: List<Path> = emptyList(),
        jdkHome: Path? = Path.of(System.getProperty("java.home")),
        cacheDir: Path = defaultDir(),
    ): CodeIndex {
        val stamps = kotlinStamps(repoRoot, sourceRoots)
        val classpathStamp = classpathIdentity(classpath)
        val file = cacheFile(cacheDir, repoRoot)
        val previous = load(file)

        if (previous != null && previous.stamps == stamps && previous.classpath == classpathStamp) {
            return previous.index
        }

        val index = AnalysisApiIndexer(sourceRoots, classpath, jdkHome, repoRoot, testRoots).use { indexer ->
            val changed = previous?.let { dirty(it.stamps, stamps) }.orEmpty()
            val files = previous?.index?.symbols?.mapTo(HashSet()) { it.file }.orEmpty()
            when {
                previous == null -> indexer.index()
                previous.classpath != classpathStamp -> indexer.index()
                !IndexPatch.worthReusing(changed.size, files.size) -> indexer.index()
                else -> {
                    val fresh = indexer.index(changed)
                    val referrers = IndexPatch.referrersToRemoved(previous.index, changed, fresh.byId.keys)
                    val repaired = if (referrers.isEmpty()) null else indexer.index(referrers)
                    IndexPatch.merge(previous.index, changed + referrers, fresh, repaired)
                }
            }
        }

        cacheDir.createDirectories()
        file.writeText(json.encodeToString(CachedIndex.serializer(), CachedIndex(stamps, classpathStamp, index)))
        return index
    }

    private fun kotlinStamps(repoRoot: Path, sourceRoots: List<Path>): Map<String, String> {
        val root = repoRoot.toRealPathOrSelf()
        val stamps = sortedMapOf<String, String>()
        for (dir in sourceRoots) {
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { walk ->
                walk.filter { Files.isRegularFile(it) && it.isKotlin() }.forEach { path ->
                    val absolute = path.toRealPathOrSelf()
                    val relative = (root.takeIf { absolute.startsWith(it) }?.relativize(absolute) ?: absolute)
                        .toString()
                        .replace('\\', '/')
                    stamps[relative] = hash(path)
                }
            }
        }
        return stamps
    }

    private fun dirty(old: Map<String, String>, new: Map<String, String>): Set<String> =
        (old.keys + new.keys).filter { old[it] != new[it] }.toSet()

    private fun load(file: Path): CachedIndex? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(CachedIndex.serializer(), file.readText()) }.getOrNull()
    }

    private fun cacheFile(cacheDir: Path, repoRoot: Path): Path =
        cacheDir.resolve(hash(repoRoot.toRealPathOrSelf().toString().toByteArray()) + ".json")

    private fun classpathIdentity(classpath: List<Path>): List<String> =
        classpath.map { "${it.fileName}:${it.toFile().length()}" }.sorted()

    private fun hash(path: Path): String = hash(Files.readAllBytes(path))

    private fun hash(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun Path.isKotlin(): Boolean =
        fileName.toString().let { it.endsWith(".kt") || it.endsWith(".kts") }

    private fun Path.toRealPathOrSelf(): Path = runCatching { toRealPath() }.getOrDefault(this)

    private val json = Json { ignoreUnknownKeys = true }

    private const val SCHEMA = 2
}

@Serializable
private data class CachedIndex(
    val stamps: Map<String, String>,
    val classpath: List<String>,
    val index: CodeIndex,
)
