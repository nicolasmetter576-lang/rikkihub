package me.rerere.rikkihub.data.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.uuid.Uuid

class AcpMcpGatewayTest {

    @Test
    fun authRejectsMissingAndWrongBearerTokens() = runBlocking {
        val gateway = AcpMcpGateway(
            listTools = { emptyList() },
            callTool = { _, _, _ -> emptyList() },
        ).start()

        try {
            val payload = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
            val missingAuth = postRaw(gateway.endpointUrl, payload, authHeader = null)
            assertEquals(401, missingAuth.status)

            val wrongAuth = postRaw(gateway.endpointUrl, payload, authHeader = "Bearer wrong-token")
            assertEquals(401, wrongAuth.status)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun initializeAndToolsListAndCallWorkEndToEnd() = runBlocking {
        val serverId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val enabledTool = McpTool(
            enable = true,
            name = "get_weather",
            description = "Get weather forecast",
            inputSchema = InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("city") { put("type", "string") }
                },
                required = listOf("city"),
            ),
        )
        val disabledTool = McpTool(
            enable = false,
            name = "secret_admin",
            description = "Should never be exposed",
        )

        var capturedServerId: Uuid? = null
        var capturedToolName: String? = null
        var capturedArgs: JsonObject? = null

        val gateway = AcpMcpGateway(
            listTools = {
                listOf(
                    GatewayTool(serverId, "Weather Server", enabledTool),
                    GatewayTool(serverId, "Weather Server", disabledTool),
                )
            },
            callTool = { sid, name, args ->
                capturedServerId = sid
                capturedToolName = name
                capturedArgs = args
                listOf(UIMessagePart.Text("Sunny in ${args.str("city")}"))
            },
        ).start()

        try {
            val serverJson = gateway.toAcpMcpServerJson()
            assertEquals("http", serverJson.str("type"))
            assertEquals(AcpMcpGateway.SERVER_NAME, serverJson.str("name"))
            assertEquals(gateway.endpointUrl, serverJson.str("url"))
            val authHeader = serverJson["headers"]!!.jsonArray[0].jsonObject.str("value")!!
            assertTrue(authHeader.startsWith("Bearer "))

            // 1. initialize
            val initResp = postJsonRpc(
                url = gateway.endpointUrl,
                authHeader = authHeader,
                body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""",
            )
            assertEquals(200, initResp.status)
            val initResult = initResp.json!!["result"]!!.jsonObject
            assertEquals("2025-03-26", initResult.str("protocolVersion"))
            assertNotNull(initResult["capabilities"]?.jsonObject?.get("tools"))
            assertEquals(AcpMcpGateway.SERVER_NAME, initResult["serverInfo"]!!.jsonObject.str("name"))

            // 2. notifications/initialized
            val notifyResp = postRaw(
                url = gateway.endpointUrl,
                body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                authHeader = authHeader,
            )
            assertTrue(notifyResp.status in setOf(200, 202, 204))

            // 3. tools/list only returns enabled tool
            val listResp = postJsonRpc(
                url = gateway.endpointUrl,
                authHeader = authHeader,
                body = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
            )
            assertEquals(200, listResp.status)
            val tools = listResp.json!!["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals(1, tools.size)
            val exposedTool = tools[0].jsonObject
            assertEquals("Weather_Server__get_weather", exposedTool.str("name"))
            assertEquals("Get weather forecast", exposedTool.str("description"))
            assertEquals("object", exposedTool["inputSchema"]!!.jsonObject.str("type"))

            // 4. tools/call executes allowed tool
            val callResp = postJsonRpc(
                url = gateway.endpointUrl,
                authHeader = authHeader,
                body = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"Weather_Server__get_weather","arguments":{"city":"Tokyo"}}}""",
            )
            assertEquals(200, callResp.status)
            val callResult = callResp.json!!["result"]!!.jsonObject
            assertFalse(callResult["isError"]!!.jsonPrimitive.content.toBoolean())
            val content = callResult["content"]!!.jsonArray
            assertEquals(1, content.size)
            assertEquals("text", content[0].jsonObject.str("type"))
            assertEquals("Sunny in Tokyo", content[0].jsonObject.str("text"))
            assertEquals(serverId, capturedServerId)
            assertEquals("get_weather", capturedToolName)
            assertEquals("Tokyo", capturedArgs?.str("city"))

            // 5. tools/call rejects disabled/non-whitelisted tool with -32602
            val rejectedResp = postJsonRpc(
                url = gateway.endpointUrl,
                authHeader = authHeader,
                body = """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"Weather_Server__secret_admin","arguments":{}}}""",
            )
            assertEquals(200, rejectedResp.status)
            val errorObj = rejectedResp.json!!["error"]!!.jsonObject
            assertEquals(-32602, errorObj["code"]!!.jsonPrimitive.intOrNull)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun closeStopsServerCleanly() = runBlocking {
        val gateway = AcpMcpGateway(
            listTools = { emptyList() },
            callTool = { _, _, _ -> emptyList() },
        ).start()
        val url = gateway.endpointUrl

        gateway.close()

        try {
            postRaw(url, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""", authHeader = null)
            fail("Expected connection failure after gateway.close()")
        } catch (_: IOException) {
            // Expected once the loopback server socket has been closed
        }
    }

    private data class HttpResult(val status: Int, val body: String, val json: JsonObject?)

    private fun postJsonRpc(url: String, authHeader: String, body: String): HttpResult {
        val raw = postRaw(url, body, authHeader)
        val parsed = runCatching { Json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        return raw.copy(json = parsed)
    }

    private fun postRaw(url: String, body: String, authHeader: String?): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 2_000
            readTimeout = 2_000
            setRequestProperty("Content-Type", "application/json")
            if (authHeader != null) {
                setRequestProperty("Authorization", authHeader)
            }
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(status, text, null)
        } finally {
            connection.disconnect()
        }
    }
}
