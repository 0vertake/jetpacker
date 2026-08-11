package dev.jetpacker.core

import dev.jetpacker.core.pack.Pack

/**
 * Anything that turns task text into a budgeted pack.
 *
 * Baselines implement this and are scored by the same harness with the same budget and the same
 * token accounting — never special-cased (AGENTS.md). If a baseline looks weak, that has to be
 * because the idea is weaker, not because it was given a worse packer.
 */
interface Retriever {
    val name: String

    fun pack(task: String, budget: Int = DEFAULT_BUDGET): Pack

    companion object {
        const val DEFAULT_BUDGET = 4000
    }
}
