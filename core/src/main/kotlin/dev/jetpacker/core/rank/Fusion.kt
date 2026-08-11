package dev.jetpacker.core.rank

/**
 * Reciprocal Rank Fusion over whole rankings.
 *
 * The benchmark's finding: plain BM25 over every declaration scored as well as the graph did,
 * because using BM25 only to pick twenty restart seeds discards a long tail that turns out to
 * carry real signal. The two rankings disagree in useful ways — one knows what the words say,
 * the other knows what the code does — so they are fused rather than chained.
 *
 * The first ranking to explain a symbol supplies its provenance, so graph reasons win over "the
 * words matched", which is the more informative of the two.
 */
fun fuse(rankings: List<List<Ranked>>, k: Int = RRF_K): List<Ranked> {
    val scores = HashMap<String, Double>()
    val best = LinkedHashMap<String, Ranked>()

    for (ranking in rankings) {
        ranking.forEachIndexed { rank, candidate ->
            scores.merge(candidate.symbol.id, 1.0 / (k + rank + 1)) { a, b -> a + b }
            best.putIfAbsent(candidate.symbol.id, candidate)
        }
    }

    return scores.entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        .map { (id, score) -> best.getValue(id).copy(score = score) }
}

/**
 * How far down a ranking still counts for much. The literature's 60 is tuned for fusing many
 * similar rankings; here there are two very different ones, and on detekt anything in 1..20 scored
 * three to four points above it. 20 sits in the middle of that plateau rather than at its edge.
 */
const val RRF_K = 20
