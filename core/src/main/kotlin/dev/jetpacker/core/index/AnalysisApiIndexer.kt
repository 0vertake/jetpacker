package dev.jetpacker.core.index

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.nio.file.Path

/**
 * [CodeIndexer] backed by the Kotlin Analysis API in standalone (headless) mode.
 *
 * [classpath] entries (jars or class directories) and [jdkHome] are what let calls into
 * dependencies resolve; without them only symbols declared under [sourceRoots] are known.
 *
 * All [sourceRoots] land in a single module: the packer ranks symbols across the whole
 * repository, so Gradle's module boundaries would only get in the way of resolution.
 *
 * The session snapshots [sourceRoots] at construction, which suits per-commit indexing
 * (docs/plan.md §6 "Staleness"). Close it to release the IntelliJ platform environment.
 */
class AnalysisApiIndexer(
    sourceRoots: List<Path>,
    classpath: List<Path> = emptyList(),
    jdkHome: Path? = null,
    private val repoRoot: Path? = null,
    private val testRoots: List<Path> = emptyList(),
) : CodeIndexer {
    private val disposable = Disposer.newDisposable("jetpacker.analysis")

    // PSI reports canonical paths, so a repo root reached through a symlink (/tmp on macOS)
    // would otherwise relativize to a ../../ escape and make every file unreadable later.
    private val root = repoRoot?.let { runCatching { it.toRealPath() }.getOrDefault(it) }
    private val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
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
                        moduleName = "main"
                        platform = jvm
                        addSourceRoots(sourceRoots)
                        binaryModules.forEach(::addRegularDependency)
                    },
                )
            }
        }
        files = session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .sortedBy { it.virtualFilePath }
    }

    private val cached: CodeIndex by lazy { build() }

    override fun index(): CodeIndex = cached

    private fun build(): CodeIndex {
        val symbols = mutableListOf<Symbol>()
        val edges = mutableSetOf<Edge>()
        var callSites = 0
        var resolvedCallees = 0
        var attributedToCaller = 0

        for (file in files) {
            val absolute = Path.of(file.virtualFilePath)
            val path = relativePath(absolute)
            val isTest = testRoots.any(absolute::startsWith)
            val declarations = file.collectDescendantsOfType<KtDeclaration> { it.isIndexable() }

            analyze(file) {
                // Two passes: PSI traversal is post-order, so a member is visited before the class
                // that contains it and no single pass can look its parent up.
                val identified = declarations.mapNotNull { declaration ->
                    val symbol = declaration.symbol
                    symbolId(symbol)?.let { Identified(declaration, symbol, it) }
                }
                val idOf = identified.associate { it.declaration to it.id }

                for ((declaration, symbol, id) in identified) {
                    symbols += toSymbol(declaration, symbol, id, path, isTest)
                    edges += structuralEdges(declaration, symbol, id, idOf)
                }

                for (call in file.collectDescendantsOfType<KtCallExpression>()) {
                    callSites++
                    val callee = call.resolveToCall()
                        ?.successfulFunctionCallOrNull()
                        ?.symbol
                        ?.let { symbolId(it) }
                        ?: continue
                    resolvedCallees++
                    val caller = enclosingDeclarationId(call, idOf) ?: continue
                    attributedToCaller++
                    edges += Edge(caller, callee, EdgeKind.CALLS)
                }
            }
        }

        return CodeIndex(
            symbols = symbols.sortedBy { it.id },
            edges = edges.sortedWith(compareBy({ it.kind }, { it.from }, { it.to })),
            coverage = ResolutionCoverage(callSites, resolvedCallees, attributedToCaller),
        )
    }

    private data class Identified(
        val declaration: KtDeclaration,
        val symbol: KaDeclarationSymbol,
        val id: String,
    )

    private fun KaSession.structuralEdges(
        declaration: KtDeclaration,
        symbol: KaDeclarationSymbol,
        id: String,
        idOf: Map<KtDeclaration, String>,
    ): List<Edge> = buildList {
        containingDeclaration(declaration)?.let { parent ->
            idOf[parent]?.let { add(Edge(it, id, EdgeKind.CONTAINS)) }
        }
        if (symbol is KaClassSymbol) {
            symbol.superTypes.mapNotNull { (it as? KaClassType)?.classId?.asFqNameString() }
                .filter { it != "kotlin.Any" }
                .forEach { add(Edge(id, it, EdgeKind.EXTENDS)) }
        }
        // An override is the single most valuable edge for interface-injected code: the call site
        // resolves to the interface method, and the body worth packing is the implementation's.
        if (symbol is KaCallableSymbol) {
            symbol.allOverriddenSymbols.mapNotNull { symbolId(it) }
                .forEach { add(Edge(id, it, EdgeKind.OVERRIDES)) }
        }
    }

    private fun KaSession.toSymbol(
        declaration: KtDeclaration,
        symbol: KaDeclarationSymbol,
        id: String,
        path: String,
        isTest: Boolean,
    ): Symbol {
        val document = declaration.containingFile.viewProvider.document
        val range = declaration.textRange
        val text = declaration.text
        return Symbol(
            id = id,
            fqName = fqNameOf(symbol) ?: id,
            name = (declaration as? KtNamedDeclaration)?.name ?: "<anonymous>",
            kind = kindOf(symbol),
            file = path,
            startLine = document.getLineNumber(range.startOffset) + 1,
            endLine = document.getLineNumber(range.endOffset) + 1,
            signature = signatureOf(declaration),
            doc = declaration.docComment
                ?.getDefaultSection()
                ?.getContent()
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim(),
            tokens = encoding.countTokens(text),
            isTest = isTest,
        )
    }

    /**
     * Identity that survives overloading: the FQN alone collapses `format(Int)` and
     * `format(String)`, and extension receivers are part of the name for the same reason.
     */
    private fun KaSession.symbolId(symbol: KaDeclarationSymbol): String? = when (symbol) {
        is KaClassSymbol -> symbol.classId?.asFqNameString()
        // `Foo()` resolves to the class. A primary constructor is not a retrievable unit of its
        // own — it has no body, and packing one rendered a truncated class header — so relevance
        // for instantiating a type belongs on the type. Secondary constructors are real
        // declarations and keep their own identity.
        is KaConstructorSymbol -> symbol.containingClassId?.asFqNameString()?.let {
            if (symbol.isPrimary) it else "$it.<init>${parameterList(symbol)}"
        }
        is KaNamedFunctionSymbol ->
            symbol.callableId?.asSingleFqName()?.asString()?.let { "$it${parameterList(symbol)}" }
        is KaPropertySymbol -> symbol.callableId?.asSingleFqName()?.asString()
        else -> null
    }

    private fun KaSession.parameterList(symbol: KaDeclarationSymbol): String {
        val receiver = (symbol as? KaNamedFunctionSymbol)?.receiverParameter?.returnType
        val parameters = when (symbol) {
            is KaNamedFunctionSymbol -> symbol.valueParameters
            is KaConstructorSymbol -> symbol.valueParameters
            else -> emptyList()
        }.map { typeName(it.returnType) }
        val receiverPrefix = receiver?.let { "this:${typeName(it)}" }
        return (listOfNotNull(receiverPrefix) + parameters).joinToString(",", "(", ")")
    }

    /**
     * An unresolved type renders as `ERROR CLASS: Symbol not found for ...`, which would put a
     * compiler diagnostic inside a symbol's identity — different messages for the same declaration
     * across runs, and edges that can never match it. It degrades to `?` instead.
     */
    private fun KaSession.typeName(type: KaType): String = when (type) {
        is KaClassType -> type.classId?.asFqNameString() ?: UNRESOLVED
        is KaErrorType -> UNRESOLVED
        else -> type.toString()
    }

    private fun KaSession.fqNameOf(symbol: KaDeclarationSymbol): String? = when (symbol) {
        is KaClassSymbol -> symbol.classId?.asFqNameString()
        is KaConstructorSymbol -> symbol.containingClassId?.asFqNameString()?.plus(".<init>")
        is KaNamedFunctionSymbol -> symbol.callableId?.asSingleFqName()?.asString()
        is KaPropertySymbol -> symbol.callableId?.asSingleFqName()?.asString()
        else -> null
    }

    private fun kindOf(symbol: KaDeclarationSymbol): SymbolKind = when (symbol) {
        is KaClassSymbol -> when (symbol.classKind) {
            KaClassKind.INTERFACE -> SymbolKind.INTERFACE
            KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT, KaClassKind.ANONYMOUS_OBJECT -> SymbolKind.OBJECT
            else -> SymbolKind.CLASS
        }
        is KaConstructorSymbol -> SymbolKind.CONSTRUCTOR
        is KaPropertySymbol -> SymbolKind.PROPERTY
        else -> SymbolKind.FUNCTION
    }

    /** The declaration header: everything before the body, which is what a stub tier renders. */
    /**
     * The declaration's header: everything before its body, minus the doc comment.
     *
     * The doc is carried separately by [Symbol.doc], so leaving it here charged for it twice — and
     * on detekt, whose rules document themselves with whole code examples, that made a class stub
     * cost hundreds of tokens. One task's gold ranked first and still could not afford its way
     * into the pack.
     */
    private fun signatureOf(declaration: KtDeclaration): String {
        val afterDoc = declaration.docComment
            ?.let { it.startOffsetInParent + it.textLength }
            ?.coerceAtMost(declaration.textLength)
            ?: 0
        val bodyOffset = when (declaration) {
            is KtNamedFunction -> declaration.bodyExpression?.startOffsetInParent
            is KtProperty -> declaration.initializer?.startOffsetInParent
            is KtClassOrObject -> declaration.body?.startOffsetInParent
            else -> null
        } ?: declaration.textLength
        if (bodyOffset <= afterDoc) return declaration.text.take(bodyOffset).trim()
        return declaration.text.substring(afterDoc, bodyOffset).trim().removeSuffix("=").trim()
    }

    /**
     * The nearest enclosing declaration, not the nearest enclosing function: calls in property
     * initializers, `init` blocks and accessors are real edges, and requiring a `KtNamedFunction`
     * dropped about 6% of resolved calls on detekt.
     */
    private fun enclosingDeclarationId(call: PsiElement, idOf: Map<KtDeclaration, String>): String? {
        var element: PsiElement? = call.parent
        while (element != null) {
            if (element is KtDeclaration) idOf[element]?.let { return it }
            element = element.parent
        }
        return null
    }

    private fun containingDeclaration(declaration: KtDeclaration): KtDeclaration? {
        var element: PsiElement? = declaration.parent
        while (element != null) {
            if (element is KtDeclaration) return element
            element = element.parent
        }
        return null
    }

    private fun relativePath(path: Path): String =
        (root?.takeIf { path.startsWith(it) }?.relativize(path) ?: path)
            .toString()
            .replace('\\', '/')

    private fun KtDeclaration.isIndexable(): Boolean =
        this is KtClassOrObject || this is KtNamedFunction || this is KtProperty || this is KtSecondaryConstructor

    override fun close() = Disposer.dispose(disposable)

    private companion object {
        const val UNRESOLVED = "?"
    }
}
