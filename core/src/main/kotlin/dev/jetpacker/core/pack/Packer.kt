package dev.jetpacker.core.pack

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.EdgeKind
import dev.jetpacker.core.index.ResolutionCoverage
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.rank.Ranked
import java.nio.file.Path
import kotlin.io.path.readLines

/** How much of a declaration a pack spends tokens on. */
enum class Fidelity { FULL, STUB }

/** One packed declaration, already rendered to the text the pack will show. */
data class PackItem(
    val symbol: Symbol,
    val fidelity: Fidelity,
    val why: String,
    val text: String,
    val tokens: Int,
)

/** The finished pack: what fit, and what it cost. */
data class Pack(
    val items: List<PackItem>,
    val tokens: Int,
    val budget: Int,
    val coverage: ResolutionCoverage = ResolutionCoverage(0, 0, 0),
)

/**
 * Fills a token budget with the highest-value declarations.
 *
 * Greedy by density (`score / tokens`) rather than by score, which is the standard knapsack
 * approximation and the one thing that stops a single 900-token class from evicting thirty
 * useful signatures.
 *
 * Two fidelity tiers. §4 also lists an FQN-only tier below stubs; a bare name with no signature
 * is close to worthless per token, so it is left out until an ablation argues for it.
 *
 * Token counts are exact, not estimated: [Symbol.tokens] measures PSI text while a pack renders
 * whole lines, and a budget that is only approximately respected is not a budget (§6).
 */
class Packer(
    private val index: CodeIndex,
    private val repoRoot: Path,
    private val budget: Int = DEFAULT_BUDGET,
    private val fullTierShare: Double = DEFAULT_FULL_TIER_SHARE,
    private val testShare: Double = DEFAULT_TEST_SHARE,
) {
    private val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
    private val fileCache = HashMap<String, List<String>>()
    private val contains = index.edges.filter { it.kind == EdgeKind.CONTAINS }
    private val members: Map<String, List<String>> = contains.groupBy({ it.from }, { it.to })
    private val owner: Map<String, String> = contains.associate { it.to to it.from }

    fun pack(ranked: List<Ranked>): Pack {
        val candidates = ranked.take(CANDIDATES)
        // Section titles and the summary line are also tokens the model pays for.
        val overhead = encoding.countTokens(header(budget, budget, candidates.size, index.coverage)) + SECTION_OVERHEAD
        val rankOf = candidates.withIndex().associate { (at, candidate) -> candidate.symbol.id to at }
        val selection = Selection()

        // Bodies first, under their own sub-budget, so the tail of the ranking still gets to
        // contribute signatures instead of being crowded out by whatever ranked highest.
        val spendable = (budget - overhead).coerceAtLeast(0)
        val fullBudget = (spendable * fullTierShare).toInt()
        byDensity(candidates, Fidelity.FULL).forEach { selection.consider(it, Fidelity.FULL, fullBudget) }
        byDensity(candidates, Fidelity.STUB).forEach { selection.consider(it, Fidelity.STUB, spendable) }

        val items = selection.taken.values.sortedWith(
            compareBy<PackItem> { it.fidelity }.thenBy { rankOf[it.symbol.id] ?: Int.MAX_VALUE },
        )
        return Pack(items, selection.spent + overhead, budget, index.coverage)
    }

    private inner class Selection {
        val taken = LinkedHashMap<String, PackItem>()
        var spent = 0
        private var testSpent = 0
        private val testLimit = (budget * testShare).toInt()

        /** Stub groups whose file heading is already paid for; see [Renderer]'s grouping. */
        private val headed = HashSet<String>()

        fun consider(candidate: Ranked, fidelity: Fidelity, limit: Int) {
            val id = candidate.symbol.id
            if (id in taken) return
            val item = render(candidate, fidelity) ?: return

            val group = groupOf(candidate.symbol, fidelity)
            val heading = if (group != null && group !in headed) encoding.countTokens(fileHeading(candidate.symbol.file)) else 0
            val cost = item.tokens + heading

            // Tests are worth naming but not worth the budget they will take if left uncapped:
            // on detekt they are two thirds of all declarations and swallowed 42% of a pack.
            if (candidate.symbol.isTest) {
                if (testSpent + cost > testLimit) return
                testSpent += cost
            }

            // A class body already contains its methods; packing both spends the budget twice on
            // the same lines. Whichever arrives second wins, and the other is refunded.
            val nested = if (fidelity == Fidelity.FULL) descendantsOf(id) else emptySet()
            if (nested.isEmpty() && enclosingTakenFully(id)) return
            val refund = nested.sumOf { taken[it]?.tokens ?: 0 }

            if (spent - refund + cost > limit) return
            nested.forEach { taken.remove(it) }
            taken[id] = item
            group?.let { headed += it }
            spent += cost - refund
        }

        /** Null for bodies, which carry their own path; stubs share one heading per file per section. */
        private fun groupOf(symbol: Symbol, fidelity: Fidelity): String? =
            if (fidelity == Fidelity.FULL) null else "${symbol.isTest}:${symbol.file}"

        private fun enclosingTakenFully(id: String): Boolean =
            generateSequence(owner[id]) { owner[it] }.any { taken[it]?.fidelity == Fidelity.FULL }
    }

    private fun descendantsOf(id: String): Set<String> {
        val found = LinkedHashSet<String>()
        val queue = ArrayDeque(members[id].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (found.add(next)) queue += members[next].orEmpty()
        }
        return found
    }

    private fun byDensity(candidates: List<Ranked>, fidelity: Fidelity): List<Ranked> =
        candidates.sortedWith(
            compareByDescending<Ranked> { it.score / estimate(it.symbol, fidelity) }
                .thenBy { it.symbol.id },
        )

    /** Ordering only; every packed item is counted exactly. */
    private fun estimate(symbol: Symbol, fidelity: Fidelity): Double = when (fidelity) {
        Fidelity.FULL -> symbol.tokens.coerceAtLeast(1).toDouble()
        Fidelity.STUB -> (symbol.signature.length / CHARS_PER_TOKEN).coerceAtLeast(1.0)
    }

    private fun render(candidate: Ranked, fidelity: Fidelity): PackItem? {
        val symbol = candidate.symbol
        val text = when (fidelity) {
            Fidelity.FULL -> body(symbol) ?: return null
            Fidelity.STUB -> symbol.signature
        }
        if (text.isBlank()) return null
        val rendered = block(symbol, candidate.why, text, fidelity)
        return PackItem(symbol, fidelity, candidate.why, text, encoding.countTokens(rendered))
    }

    private fun body(symbol: Symbol): String? {
        val lines = fileCache.getOrPut(symbol.file) {
            runCatching { repoRoot.resolve(symbol.file).readLines() }.getOrDefault(emptyList())
        }
        if (lines.isEmpty() || symbol.endLine > lines.size) return null
        return lines.subList(symbol.startLine - 1, symbol.endLine).joinToString("\n")
    }

    companion object {
        const val DEFAULT_BUDGET = 4000
        /**
         * Bodies are what an agent edits, so a pack is not all signatures — but they are expensive:
         * on detekt, raising this above 0.15 cost recall and lowering it to 0 bought three points
         * that a signature-only pack would make the agent spend a file read to recover.
         */
        const val DEFAULT_FULL_TIER_SHARE = 0.15
        const val DEFAULT_TEST_SHARE = 0.1
        const val CANDIDATES = 400
        const val CHARS_PER_TOKEN = 3.6

        /** The three section titles and their blank lines. */
        const val SECTION_OVERHEAD = 12
    }
}
