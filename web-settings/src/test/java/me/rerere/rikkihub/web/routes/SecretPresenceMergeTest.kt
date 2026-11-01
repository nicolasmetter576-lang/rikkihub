package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkihub.data.ai.mcp.McpOAuthState
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.ai.mcp.McpTool
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projected (secret-free) client can never clobber stored
 * credentials on write-back — every credential slot is presence-aware:
 * absent/null/masked-echo = preserve, "" = explicit clear, literal = set.
 */
class SecretPresenceMergeTest {

    private fun raw(provider: ProviderSetting): JsonObject =
        JsonInstant.encodeToJsonElement<ProviderSetting>(provider).jsonObject

    // ---- provider apiKey / privateKey slots ----

    @Test
    fun `absent apiKey preserves stored credential`() {
        val existing = ProviderSetting.OpenAI(apiKey = "stored-secret")
        val body = JsonObject(raw(existing) - "apiKey")
        val merged = mergeProvider(existing, parseProviderUpdate(body)) as ProviderSetting.OpenAI
        assertEquals("stored-secret", merged.apiKey)
    }

    @Test
    fun `empty apiKey clears and literal sets`() {
        val existing = ProviderSetting.OpenAI(apiKey = "stored-secret")
        val cleared = mergeProvider(
            existing,
            parseProviderUpdate(JsonObject(raw(existing) + ("apiKey" to JsonPrimitive("")))),
        ) as ProviderSetting.OpenAI
        assertEquals("", cleared.apiKey)

        val set = mergeProvider(
            existing,
            parseProviderUpdate(JsonObject(raw(existing) + ("apiKey" to JsonPrimitive("new-key")))),
        ) as ProviderSetting.OpenAI
        assertEquals("new-key", set.apiKey)
    }

    @Test
    fun `masked echo preserves privateKey`() {
        val existing = ProviderSetting.Google(privateKey = "PRIV-KEY", apiKey = "k")
        val body = JsonObject(
            raw(existing) + mapOf(
                "privateKey" to JsonPrimitive(""),
                "privateKeyConfigured" to JsonPrimitive(true),
            )
        )
        val merged = mergeProvider(existing, parseProviderUpdate(body)) as ProviderSetting.Google
        assertEquals("PRIV-KEY", merged.privateKey)
    }

    // ---- customHeaders slots ----

    @Test
    fun `masked header entries preserve stored values by name`() {
        val existing = ProviderSetting.OpenAI(
            customHeaders = listOf(
                CustomHeader("Authorization", "real-secret"),
                CustomHeader("X-Plain", "plain"),
            )
        )
        // Projected echo: values masked, configured flagged.
        val body = JsonObject(
            raw(existing) + ("customHeaders" to JsonArray(
                listOf(
                    buildJsonObject {
                        put("name", "Authorization")
                        put("value", "")
                        put("valueConfigured", true)
                    },
                    buildJsonObject {
                        put("name", "X-Plain")
                        put("value", "plain-edited")
                        put("valueConfigured", false)
                    },
                )
            ))
        )
        val merged = mergeProvider(existing, parseProviderUpdate(body))
        assertEquals("real-secret", merged.customHeaders[0].value)
        assertEquals("plain-edited", merged.customHeaders[1].value)
    }

    @Test
    fun `null header value preserves and empty string clears`() {
        val existing = ProviderSetting.OpenAI(
            customHeaders = listOf(CustomHeader("A", "secret-a"), CustomHeader("B", "secret-b"))
        )
        val body = JsonObject(
            raw(existing) + ("customHeaders" to JsonArray(
                listOf(
                    buildJsonObject { put("name", "A"); put("value", JsonNull) },
                    buildJsonObject { put("name", "B"); put("value", "") },
                )
            ))
        )
        val merged = mergeProvider(existing, parseProviderUpdate(body))
        assertEquals("secret-a", merged.customHeaders[0].value)
        assertEquals("", merged.customHeaders[1].value)
    }

    // ---- model customBodies / providerOverwrite ----

    @Test
    fun `model update preserves masked body values and nested provider secrets`() {
        val existing = Model(
            modelId = "m1",
            customBodies = listOf(CustomBody("auth", JsonPrimitive("body-secret"))),
            customHeaders = listOf(CustomHeader("x-k", "header-secret")),
            providerOverwrite = ProviderSetting.OpenAI(apiKey = "ov-secret"),
        )
        val rawModel = JsonInstant.encodeToJsonElement(Model.serializer(), existing).jsonObject
        val body = JsonObject(
            rawModel + mapOf(
                "customBodies" to JsonArray(
                    listOf(buildJsonObject {
                        put("key", "auth")
                        put("value", JsonNull)
                        put("valueConfigured", true)
                    })
                ),
                "customHeaders" to JsonArray(
                    listOf(buildJsonObject {
                        put("name", "x-k")
                        put("value", "")
                        put("valueConfigured", true)
                    })
                ),
                // providerOverwrite projected: apiKey masked.
                "providerOverwrite" to buildJsonObject {
                    put("type", "openai")
                    put("apiKey", "")
                    put("apiKeyConfigured", true)
                },
                "displayName" to JsonPrimitive("renamed"),
            )
        )
        val merged = mergeModel(existing, parseModelUpdate(body))
        assertEquals("renamed", merged.displayName)
        assertEquals(JsonPrimitive("body-secret"), merged.customBodies[0].value)
        assertEquals("header-secret", merged.customHeaders[0].value)
        assertEquals("ov-secret", (merged.providerOverwrite as ProviderSetting.OpenAI).apiKey)
    }

    // ---- MCP headers ----

    @Test
    fun `mcp header masked echo preserves and missing header key keeps list`() {
        val existing = McpServerConfig.SseTransportServer(
            commonOptions = McpCommonOptions(
                headers = listOf("Authorization" to "Bearer mcp-secret"),
                tools = listOf(McpTool(name = "search")),
                oauth = McpOAuthState(accessToken = "tok"),
            ),
            url = "https://mcp/sse",
        )
        val raw = JsonInstant.encodeToJsonElement(McpServerConfig.serializer(), existing).jsonObject

        // Headers key absent → whole list preserved.
        val common = raw["commonOptions"]!!.jsonObject
        val noHeaders = JsonObject(raw + ("commonOptions" to JsonObject(common - "headers")))
        val kept = mergeMcpServer(existing, parseMcpUpdate(noHeaders)) as McpServerConfig.SseTransportServer
        assertEquals(listOf("Authorization" to "Bearer mcp-secret"), kept.commonOptions.headers)

        // Masked echo + explicit clear.
        val masked = JsonObject(
            raw + ("commonOptions" to JsonObject(
                common + ("headers" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("first", "Authorization")
                            put("second", "")
                            put("valueConfigured", true)
                        },
                        buildJsonObject { put("first", "X-New"); put("second", "v2") },
                    )
                ))
            ))
        )
        val merged = mergeMcpServer(existing, parseMcpUpdate(masked)) as McpServerConfig.SseTransportServer
        assertEquals("Bearer mcp-secret", merged.commonOptions.headers[0].second)
        assertEquals("v2", merged.commonOptions.headers[1].second)
        // Tools/oauth always server-owned.
        assertEquals(existing.commonOptions.tools, merged.commonOptions.tools)
        assertEquals(existing.commonOptions.oauth, merged.commonOptions.oauth)
    }

    @Test
    fun `malformed header entries without value are rejected`() {
        val body = buildJsonObject {
            put("type", "openai")
            put("customHeaders", JsonArray(listOf(buildJsonObject { put("name", "X") })))
        }
        assertTrue(
            runCatching { parseProviderUpdate(body) }.exceptionOrNull() is BadRequestException
        )
    }
}
