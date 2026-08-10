package dev.jetpacker.core.resolve

import com.google.common.base.Strings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real repositories spend most of their calls crossing into dependencies, so resolution has to
 * reach through jars, not just source. Covers both directions of the Java interop the indexer
 * needs (docs/plan.md §4): the Kotlin stdlib and a plain Java library.
 */
class BinaryDependencyResolutionTest {
    @Test
    fun `resolves calls into the kotlin stdlib and a java library`() {
        val callees = resolver.callEdges()
            .filter { it.callerFqName == "fixture.dependent.shout" }
            .map { it.calleeFqName }

        assertTrue(
            "com.google.common.base.Strings.nullToEmpty" in callees,
            "expected a resolved call into the Guava jar, got $callees",
        )
        assertTrue(
            "kotlin.collections.firstOrNull" in callees,
            "expected a resolved call into the Kotlin stdlib jar, got $callees",
        )
        assertTrue(
            "kotlin.text.uppercase" in callees,
            "expected the stdlib extension receiver to resolve, got $callees",
        )
    }

    private companion object {
        /** The jar (or class directory) a class was loaded from. */
        fun originOf(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI())

        val resolver: CodeResolver by lazy {
            val root = Path.of(
                requireNotNull(
                    BinaryDependencyResolutionTest::class.java.getResource("/fixtures/dependent"),
                ).toURI(),
            )
            AnalysisApiResolver(
                sourceRoot = root,
                classpath = listOf(originOf(Unit::class.java), originOf(Strings::class.java)),
                jdkHome = Path.of(System.getProperty("java.home")),
            )
        }
    }
}
