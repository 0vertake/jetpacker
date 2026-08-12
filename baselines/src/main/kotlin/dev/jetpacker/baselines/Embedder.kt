package dev.jetpacker.baselines

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.repository.zoo.Criteria
import kotlin.math.sqrt

/**
 * A sentence-transformer running in this process, on the CPU.
 *
 * `all-MiniLM-L6-v2` is the model the plan names, and the one most JVM and Python RAG stacks reach
 * for first: small, fast, and good enough that nobody replaces it without measuring. Running it
 * locally keeps the benchmark free to run and reproducible offline — an API-backed embedding would
 * make the numbers depend on a vendor's current weights.
 *
 * Vectors come back normalized, so a dot product is the cosine.
 *
 * Every text seen is remembered. The benchmark embeds one repository at dozens of commits, and
 * almost every window is identical between two of them, so the cache is the difference between
 * embedding a repository once and embedding it once per task.
 */
class Embedder : AutoCloseable {
    private val loaded = Criteria.builder()
        .setTypes(String::class.java, FloatArray::class.java)
        .optModelUrls(MINILM)
        .optEngine("OnnxRuntime")
        .optTranslatorFactory(TextEmbeddingTranslatorFactory())
        // A window longer than the model's context loses its tail rather than being rejected, which
        // is what a chunk-RAG stack does at this window size.
        .optArgument("maxLength", MAX_TOKENS)
        .build()
        .loadModel()

    private val predictor = loaded.newPredictor()
    private val known = HashMap<String, FloatArray>()

    fun embed(text: String): FloatArray = embed(listOf(text)).single()

    fun embed(texts: List<String>): List<FloatArray> {
        val fresh = texts.filterNot { it in known }.distinct()
        for (batch in fresh.chunked(BATCH)) {
            predictor.batchPredict(batch).forEachIndexed { at, vector -> known[batch[at]] = normalize(vector) }
        }
        return texts.map { known.getValue(it) }
    }

    override fun close() {
        predictor.close()
        loaded.close()
    }

    companion object {
        private const val MINILM =
            "djl://ai.djl.huggingface.onnxruntime/sentence-transformers/all-MiniLM-L6-v2"

        /** The model's own limit. */
        private const val MAX_TOKENS = 512

        /** Cosine, for vectors [embed] has already normalized. */
        fun similarity(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0
            for (at in a.indices) dot += a[at].toDouble() * b[at]
            return dot
        }

        private const val BATCH = 32

        private fun normalize(vector: FloatArray): FloatArray {
            val length = sqrt(vector.sumOf { it.toDouble() * it })
            if (length == 0.0) return vector
            return FloatArray(vector.size) { (vector[it] / length).toFloat() }
        }
    }
}
