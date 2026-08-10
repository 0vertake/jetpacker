package dev.jetpacker.core.index

import com.google.common.base.Strings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real repositories spend most of their calls crossing into dependencies, so resolution has to
 * reach through jars, not just source. Covers both directions of the Java interop the indexer
 * needs (docs/plan.md §4): the Kotlin stdlib and a plain Java library.
 *
 * Assertions match on prefix because a symbol id carries parameter types, and Java signatures
 * come back as platform types whose exact rendering is not worth pinning.
 */
class BinaryDependencyResolutionTest {
    @Test
    fun `resolves calls into the kotlin stdlib and a java library`() {
        val callees = index.edges
            .filter { it.kind == EdgeKind.CALLS && it.from.startsWith("fixture.dependent.shout") }
            .map { it.to }

        assertResolved("com.google.common.base.Strings.nullToEmpty", callees, "a call into the Guava jar")
        assertResolved("kotlin.collections.firstOrNull", callees, "a call into the Kotlin stdlib jar")
        assertResolved("kotlin.text.uppercase", callees, "the stdlib extension receiver")
    }

    private fun assertResolved(prefix: String, callees: List<String>, what: String) =
        assertTrue(callees.any { it.startsWith(prefix) }, "expected $what, got $callees")

    private companion object {
        /** The jar (or class directory) a class was loaded from. */
        fun originOf(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI())

        val index: CodeIndex by lazy {
            val root = Path.of(
                requireNotNull(
                    BinaryDependencyResolutionTest::class.java.getResource("/fixtures/dependent"),
                ).toURI(),
            )
            AnalysisApiIndexer(
                sourceRoots = listOf(root),
                classpath = listOf(originOf(Unit::class.java), originOf(Strings::class.java)),
                jdkHome = Path.of(System.getProperty("java.home")),
            ).use { it.index() }
        }
    }
}
