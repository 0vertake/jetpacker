package dev.jetpacker.cli

import dev.jetpacker.core.Retriever
import dev.jetpacker.core.graph.GraphQuery
import dev.jetpacker.core.index.CodeIndex
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.toMarkdown
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * The MCP surface: `get_context_pack` for the briefing, `explain_context_pack` for why each
 * declaration is in it (docs/plan.md §6 pack explainability).
 *
 * The first index still happens before the client is answered, because resolving a real project
 * costs about a minute. Later tool calls restamp the sources and reuse that index when nothing
 * changed, or patch it when the agent has written files.
 */
fun serve(repo: Path, embedSeeds: Boolean = false) {
    // stdout is the protocol, so nothing else may write to it — and the IntelliJ platform behind
    // the indexer does. It is captured here and everything else is pointed at stderr.
    val protocol = PrintWriter(OutputStreamWriter(System.out, StandardCharsets.UTF_8))
    System.setOut(System.err)

    System.err.println("indexing $repo ...")
    val packer = liveJetpacker(repo, embedSeeds)
    System.err.println("indexed ${packer().index.symbols.size} declarations; ready")

    McpServer({ packer() }, { packer().index }).serve(System.`in`.bufferedReader(), protocol)
}

/**
 * Newline-delimited JSON-RPC 2.0 over stdio, written by hand rather than with the Kotlin MCP SDK.
 *
 * The SDK cannot run in this process. It is compiled against vanilla coroutines, the Analysis API
 * requires JetBrains' patched fork (AGENTS.md), and the fork is built without the `DefaultImpls`
 * classes that vanilla-compiled code calls into — so the two cannot share a classpath, and the SDK
 * dies at the first `channel.close()` with a `NoSuchMethodError`. Against that, two tools over four
 * methods of a well-specified protocol is the smaller thing to own.
 */
internal class McpServer(
    private val packer: () -> Retriever,
    private val index: () -> CodeIndex,
) {
    constructor(packer: Retriever, index: CodeIndex) : this({ packer }, { index })
    constructor(packer: Retriever) : this({ packer }, { error("graph queries need a CodeIndex") })
    constructor(packer: () -> Retriever) : this(packer, { error("graph queries need a CodeIndex") })

    fun serve(input: BufferedReader, output: PrintWriter) {
        for (line in input.lineSequence()) {
            val response = respond(line) ?: continue
            output.println(response)
            output.flush()
        }
    }

    /** One message in, one line of JSON out — or nothing, for a notification that wants no reply. */
    internal fun respond(line: String): String? {
        if (line.isBlank()) return null
        val request = runCatching { Json.parseToJsonElement(line) as JsonObject }.getOrNull()
            ?: return encode(failure(JsonNull, PARSE_ERROR, "not a JSON-RPC message"))

        // A notification is a request without an id, and answering one is a protocol violation.
        val id = request["id"] ?: return null
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
            ?: return encode(failure(id, INVALID_REQUEST, "no method"))
        val params = request["params"] as? JsonObject

        return encode(
            when (method) {
                "initialize" -> reply(id, initialize(params))
                "ping" -> reply(id, JsonObject(emptyMap()))
                "tools/list" -> reply(id, buildJsonObject { putJsonArray("tools") { TOOLS.forEach(::add) } })
                "tools/call" -> call(id, params)
                else -> failure(id, METHOD_NOT_FOUND, "unknown method: $method")
            },
        )
    }

    private fun initialize(params: JsonObject?): JsonObject {
        val asked = (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            // Answer in the client's version when it is one we speak, otherwise in the newest we
            // do and let the client decide whether to go on.
            put("protocolVersion", if (asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") {} }
            putJsonObject("serverInfo") {
                put("name", "jetpacker")
                put("version", VERSION)
            }
        }
    }

    private fun call(id: JsonElement, params: JsonObject?): JsonObject {
        val tool = (params?.get("name") as? JsonPrimitive)?.contentOrNull
        val arguments = params?.get("arguments") as? JsonObject
        return reply(
            id,
            when (tool) {
                PACK_TOOL_NAME -> contextPack(packer(), arguments)
                EXPLAIN_TOOL_NAME -> explainPack(packer(), arguments)
                CALLERS_TOOL_NAME -> callersTool(index(), arguments)
                IMPLEMENTATIONS_TOOL_NAME -> implementationsTool(index(), arguments)
                else -> return failure(id, INVALID_PARAMS, "unknown tool: $tool")
            },
        )
    }

    private companion object {
        const val VERSION = "0.1.0"

        /** Newest first: the one offered to a client whose version we do not speak. */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    }
}

/**
 * The tools' bodies, kept out of the transport so they can be tested without one.
 *
 * Everything that can go wrong here comes back as a tool result with `isError`, not as a JSON-RPC
 * error: an agent that is told what was wrong with its arguments can fix them on the next call,
 * while a protocol error tells it only that the server broke.
 */
internal fun contextPack(packer: Retriever, arguments: JsonObject?): JsonObject =
    withPack(packer, arguments) { it.toMarkdown() }

internal fun explainPack(packer: Retriever, arguments: JsonObject?): JsonObject =
    withPack(packer, arguments) { encode(explained(it)) }

internal fun callersTool(index: CodeIndex, arguments: JsonObject?): JsonObject =
    graphTool(index, arguments, GraphQuery::callersOf, "caller")

internal fun implementationsTool(index: CodeIndex, arguments: JsonObject?): JsonObject =
    graphTool(index, arguments, GraphQuery::implementationsOf, "implementation")

private fun withPack(packer: Retriever, arguments: JsonObject?, render: (Pack) -> String): JsonObject {
    val task = (arguments?.get("task") as? JsonPrimitive)?.contentOrNull
    if (task.isNullOrBlank()) return failed("`task` is required and must not be empty")

    val budget = (arguments["budget"] as? JsonPrimitive)?.intOrNull ?: Retriever.DEFAULT_BUDGET
    if (budget <= 0) return failed("`budget` must be a positive number of tokens, got $budget")

    val pack = runCatching { packer.pack(task, budget) }
        .getOrElse { return failed("packing failed: ${it.message ?: it::class.simpleName}") }
    return text(render(pack), isError = false)
}

private fun graphTool(
    index: CodeIndex,
    arguments: JsonObject?,
    lookup: (CodeIndex, String) -> List<Symbol>,
    role: String,
): JsonObject {
    val symbol = (arguments?.get("symbol") as? JsonPrimitive)?.contentOrNull
    if (symbol.isNullOrBlank()) return failed("`symbol` is required and must not be empty")

    val hits = runCatching { lookup(index, symbol) }
        .getOrElse { return failed("lookup failed: ${it.message ?: it::class.simpleName}") }
    if (hits.isEmpty()) return text("no $role found for `$symbol`", isError = false)

    return text(encode(graphHits(symbol, role, hits)), isError = false)
}

private fun graphHits(query: String, role: String, symbols: List<Symbol>) = buildJsonObject {
    put("query", query)
    put("role", role)
    put("count", symbols.size)
    putJsonArray("symbols") {
        for (symbol in symbols) {
            add(
                buildJsonObject {
                    put("id", symbol.id)
                    put("name", symbol.name)
                    put("file", symbol.file)
                    put("line", symbol.startLine)
                    put("signature", symbol.signature)
                },
            )
        }
    }
}

/** Ids, paths, `why`, and diagnostics — the briefing without the bodies. */
private fun explained(pack: Pack) = buildJsonObject {
    put("tokens", pack.tokens)
    put("budget", pack.budget)
    putJsonArray("items") {
        for (item in pack.items) {
            add(
                buildJsonObject {
                    put("id", item.symbol.id)
                    put("name", item.symbol.name)
                    put("file", item.symbol.file)
                    put("line", item.symbol.startLine)
                    put("why", item.why)
                    put("fidelity", item.fidelity.name.lowercase())
                    put("tokens", item.tokens)
                },
            )
        }
    }
    putJsonArray("errors") {
        for (error in pack.errors) {
            add(
                buildJsonObject {
                    put("file", error.file)
                    put("line", error.line)
                    put("message", error.message)
                },
            )
        }
    }
}

private fun failed(message: String) = text(message, isError = true)

private fun text(body: String, isError: Boolean) = buildJsonObject {
    putJsonArray("content") {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", body)
            },
        )
    }
    put("isError", isError)
}

private fun reply(id: JsonElement, result: JsonObject) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

private fun failure(id: JsonElement, code: Int, message: String) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

/** Never pretty-printed: a message is one line, and a pack is full of newlines that must escape. */
private fun encode(message: JsonObject) = Json.encodeToString(JsonObject.serializer(), message)

private const val PACK_TOOL_NAME = "get_context_pack"
private const val EXPLAIN_TOOL_NAME = "explain_context_pack"
private const val CALLERS_TOOL_NAME = "callers_of"
private const val IMPLEMENTATIONS_TOOL_NAME = "implementations_of"

private val PACK_TOOL = tool(
    PACK_TOOL_NAME,
    "Build a token-budgeted context pack of whole Kotlin declarations for a task, selected by " +
        "compiler-resolved structure: the declarations the task names, what calls them, what " +
        "implements them, and the tests around them. Returns Markdown.",
)

private val EXPLAIN_TOOL = tool(
    EXPLAIN_TOOL_NAME,
    "Same pack as get_context_pack, but as a list of declarations with why each is there " +
        "(seed, caller-of, impl-of, test-of) instead of the code, plus any diagnostics in " +
        "those files. Use this to audit a pack.",
)

private val CALLERS_TOOL = symbolTool(
    CALLERS_TOOL_NAME,
    "Resolved callers of a declaration — symbols that call the named target.",
)

private val IMPLEMENTATIONS_TOOL = symbolTool(
    IMPLEMENTATIONS_TOOL_NAME,
    "Resolved implementations of a type — classes that extend or override the named target.",
)

private val TOOLS = listOf(PACK_TOOL, EXPLAIN_TOOL, CALLERS_TOOL, IMPLEMENTATIONS_TOOL)

private fun tool(name: String, description: String) = buildJsonObject {
    put("name", name)
    put("description", description)
    putJsonObject("inputSchema") {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("task") {
                put("type", "string")
                put("description", "What needs doing — an issue, a bug report, or a description of the change")
            }
            putJsonObject("budget") {
                put("type", "integer")
                put("description", "Token budget for the pack (default ${Retriever.DEFAULT_BUDGET})")
            }
        }
        putJsonArray("required") { add("task") }
    }
}

private fun symbolTool(name: String, description: String) = buildJsonObject {
    put("name", name)
    put("description", description)
    putJsonObject("inputSchema") {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("symbol") {
                put("type", "string")
                put("description", "Symbol id, fqName, or simple name to look up")
            }
        }
        putJsonArray("required") { add("symbol") }
    }
}

private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
