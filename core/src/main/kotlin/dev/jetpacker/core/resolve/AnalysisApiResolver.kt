package dev.jetpacker.core.resolve

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.nio.file.Path

/**
 * [CodeResolver] backed by the Kotlin Analysis API in standalone (headless) mode.
 *
 * [classpath] entries (jars or class directories) and [jdkHome] are what let calls into
 * dependencies resolve; without them only symbols declared under [sourceRoots] are known.
 *
 * All [sourceRoots] land in a single module: the packer ranks symbols across the whole
 * repository, so Gradle's module boundaries would only get in the way of resolution.
 *
 * The session is immutable: it snapshots [sourceRoots] at construction, which suits per-commit
 * indexing (docs/plan.md §6 "Staleness"). Close it to release the IntelliJ platform environment.
 */
class AnalysisApiResolver(
    sourceRoots: List<Path>,
    classpath: List<Path> = emptyList(),
    jdkHome: Path? = null,
    moduleName: String = "main",
) : CodeResolver {
    private val disposable = Disposer.newDisposable("jetpacker.analysis")

    private val files: List<KtFile>

    init {
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                val jvm = JvmPlatforms.defaultJvmPlatform
                platform = jvm

                val binaryModules = buildList {
                    if (jdkHome != null) {
                        add(
                            addModule(
                                buildKtSdkModule {
                                    platform = jvm
                                    libraryName = "jdk"
                                    addBinaryRootsFromJdkHome(jdkHome, isJre = false)
                                },
                            ),
                        )
                    }
                    classpath.forEach { entry ->
                        add(
                            addModule(
                                buildKtLibraryModule {
                                    platform = jvm
                                    libraryName = entry.fileName.toString()
                                    addBinaryRoot(entry)
                                },
                            ),
                        )
                    }
                }

                addModule(
                    buildKtSourceModule {
                        this.moduleName = moduleName
                        platform = jvm
                        addSourceRoots(sourceRoots)
                        binaryModules.forEach(::addRegularDependency)
                    },
                )
            }
        }
        files = session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
    }

    override fun callEdges(): List<CallEdge> =
        files
            .flatMap { file ->
                val calls = file.collectDescendantsOfType<KtCallExpression>()
                analyze(file) {
                    calls.mapNotNull { call ->
                        val callee = call.resolveToCall()
                            ?.successfulFunctionCallOrNull()
                            ?.symbol
                            ?.callableId
                            ?.asSingleFqName()
                            ?.asString()
                            ?: return@mapNotNull null
                        val caller = call.getStrictParentOfType<KtNamedFunction>()
                            ?.fqName
                            ?.asString()
                            ?: return@mapNotNull null
                        CallEdge(caller, callee)
                    }
                }
            }.distinct()
            .sortedWith(compareBy({ it.callerFqName }, { it.calleeFqName }))

    override fun implementationsOf(fqName: String): List<String> =
        files
            .flatMap { file ->
                val declarations = file.collectDescendantsOfType<KtClassOrObject>()
                analyze(file) {
                    declarations.mapNotNull { declaration ->
                        val symbol = declaration.symbol as? KaClassSymbol ?: return@mapNotNull null
                        val extendsTarget = symbol.superTypes.any { supertype ->
                            (supertype as? KaClassType)?.classId?.asFqNameString() == fqName
                        }
                        if (extendsTarget) symbol.classId?.asFqNameString() else null
                    }
                }
            }.distinct()
            .sorted()

    override fun close() = Disposer.dispose(disposable)
}
