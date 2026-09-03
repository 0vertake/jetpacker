package dev.jetpacker.eval

/** Normalizes a unified diff before `git apply` sees it. */
object Patch {
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var patch = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (!patch.endsWith("\n")) patch += "\n"
        return patch
    }
}
