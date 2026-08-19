package dev.jetpacker.cli

import dev.jetpacker.core.Retriever
import dev.jetpacker.core.index.Symbol
import dev.jetpacker.core.index.SymbolKind
import dev.jetpacker.core.pack.Fidelity
import dev.jetpacker.core.pack.Pack
import dev.jetpacker.core.pack.PackItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The protocol is hand-written (see [McpServer]), so every rule it has to keep is a rule this file
 * has to check — an agent that cannot parse a reply gets no context at all, and says nothing about
 * why.
 */
class McpTest {
    @Test
    fun `answers initialize in the client's protocol version`() {
        val result = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")

        assertEquals("2025-03-26", result.string("protocolVersion"))
        assertEquals("jetpacker", result["serverInfo"]!!.jsonObject.string("name"))
        assertTrue("tools" in result["capabilities"]!!.jsonObject, "a server with no tools is not worth connecting to")
    }

    @Test
    fun `offers its own version to a client speaking one it does not`() {
        val result = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")

        assertEquals("2025-11-25", result.string("protocolVersion"))
    }

    @Test
    fun `advertises both tools with the same required argument`() {
        val listed = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["tools"]!!.jsonArray
        assertEquals(
            listOf("get_context_pack", "explain_context_pack"),
            listed.map { it.jsonObject.string("name") },
        )
        for (tool in listed) {
            assertEquals(
                listOf("task"),
                tool.jsonObject["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `asks a fresh packer for each tool call`() {
        val seen = mutableListOf<Recording>()
        val server = McpServer {
            Recording().also { seen += it }
        }
        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_context_pack",""" +
            """"arguments":{"task":"fix the ranker"}}}"""

        server.respond(request)
        server.respond(request.replace("\"id\":1", "\"id\":2"))

        assertEquals(2, seen.size)
        assertEquals("fix the ranker", seen[0].asked?.first)
        assertEquals("fix the ranker", seen[1].asked?.first)
    }

    @Test
    fun `packs the task at the requested budget`() {
        val packer = Recording()

        val result = call(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_context_pack",""" +
                """"arguments":{"task":"fix the ranker","budget":1500}}}""",
            packer,
        )

        assertEquals("fix the ranker" to 1500, packer.asked)
        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(
            result.text().startsWith("# Context pack"),
            "the pack itself is the content of the reply, got ${result.text()}",
        )
    }

    @Test
    fun `falls back to the default budget`() {
        val packer = Recording()

        call(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_context_pack",""" +
                """"arguments":{"task":"fix the ranker"}}}""",
            packer,
        )

        assertEquals(Retriever.DEFAULT_BUDGET, packer.asked?.second)
    }

    @Test
    fun `explains why each packed declaration is there, without the bodies`() {
        val packer = Recording()

        val result = call(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"explain_context_pack",""" +
                """"arguments":{"task":"fix the ranker","budget":1500}}}""",
            packer,
        )

        assertEquals("fix the ranker" to 1500, packer.asked)
        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
        val body = Json.parseToJsonElement(result.text()).jsonObject
        val item = body["items"]!!.jsonArray.single().jsonObject
        assertEquals("fixture.Greeter", item.string("id"))
        assertEquals("seed", item.string("why"))
        assertEquals("src/Greeter.kt", item.string("file"))
        assertTrue("class Greeter" !in result.text(), "the code belongs on get_context_pack, got ${result.text()}")
    }

    @Test
    fun `keeps a pack on one line however many the code runs to`() {
        val line = McpServer(Recording()).respond(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_context_pack",""" +
                """"arguments":{"task":"fix the ranker"}}}""",
        )

        assertEquals(1, line!!.lines().size, "a raw newline in the reply would end the message early")
    }

    @Test
    fun `refuses a call with no task instead of packing nothing`() {
        val arguments = listOf("""{}""", """{"task":" "}""", """{"task":null}""")

        for (given in arguments) {
            val result = call(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_context_pack",""" +
                    """"arguments":$given}}""",
            )

            assertEquals(true, result["isError"]!!.jsonPrimitive.boolean, "for $given")
            assertTrue(result.text().contains("`task` is required"), "for $given, got ${result.text()}")
        }
    }

    @Test
    fun `refuses a budget that cannot hold anything`() {
        val result = call(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_context_pack",""" +
                """"arguments":{"task":"x","budget":0}}}""",
        )

        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `reports a failed pack as a tool error rather than dying`() {
        val exploding = object : Retriever {
            override val name = "exploding"
            override fun pack(task: String, budget: Int): Pack = error("index is gone")
        }

        val result = call(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_context_pack",""" +
                """"arguments":{"task":"x"}}}""",
            exploding,
        )

        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(result.text().contains("index is gone"), "got ${result.text()}")
    }

    @Test
    fun `rejects an unknown tool and an unknown method`() {
        val unknownTool = respond("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nope"}}""")
        val unknownMethod = respond("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")

        assertEquals(-32602, unknownTool["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32601, unknownMethod["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `says nothing back to a notification`() {
        assertNull(McpServer(Recording()).respond("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(McpServer(Recording()).respond(""))
    }

    @Test
    fun `answers a request it cannot parse instead of stalling the client`() {
        val result = respond("not json")

        assertEquals(-32700, result["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `serves requests until the client closes the stream`() {
        val written = StringWriter()

        McpServer(Recording()).serve(
            """
            {"jsonrpc":"2.0","id":1,"method":"ping"}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            {"jsonrpc":"2.0","id":2,"method":"ping"}
            """.trimIndent().reader().buffered(),
            PrintWriter(written),
        )

        assertEquals(
            listOf(1, 2),
            written.toString().trim().lines().map { Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.int },
            "the notification in the middle must not shift the replies onto the wrong requests",
        )
    }

    /** Answers every task with the same one-item pack, and remembers what it was asked for. */
    private class Recording : Retriever {
        override val name = "recording"
        var asked: Pair<String, Int>? = null

        override fun pack(task: String, budget: Int): Pack {
            asked = task to budget
            val symbol = Symbol(
                id = "fixture.Greeter",
                fqName = "fixture.Greeter",
                name = "Greeter",
                kind = SymbolKind.CLASS,
                file = "src/Greeter.kt",
                startLine = 1,
                endLine = 3,
                signature = "class Greeter",
                doc = null,
                tokens = 4,
                isTest = false,
            )
            return Pack(listOf(PackItem(symbol, Fidelity.FULL, "seed", "class Greeter", 4)), 4, budget)
        }
    }

    private fun respond(request: String, packer: Retriever = Recording()): JsonObject =
        Json.parseToJsonElement(McpServer(packer).respond(request)!!).jsonObject

    /** The `result` of a successful call; a test that wanted an error asks for it by name. */
    private fun call(request: String, packer: Retriever = Recording()): JsonObject =
        respond(request, packer)["result"]!!.jsonObject

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.text() =
        this["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
}
