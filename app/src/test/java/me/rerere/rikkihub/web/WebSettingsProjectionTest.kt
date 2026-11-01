package me.rerere.rikkihub.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkihub.data.ai.mcp.McpOAuthState
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.datastore.NetworkSetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.WebDavConfig
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.sync.s3.S3Config
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Canary tests for [projectSettingsForWeb]: every credential field in the
 * Settings graph must be masked ("" / null + `*Configured` flag) — never the
 * raw value — and creator resources hidden via the registered lookup hook.
 */
class WebSettingsProjectionTest {

    @After
    fun tearDown() = WebCreatorResources.unregisterForTests()

    private fun canarySettings(): Settings {
        val openAi = ProviderSetting.OpenAI(
            name = "Canary",
            apiKey = "sk-canary-apikey",
            customHeaders = listOf(
                CustomHeader("Authorization", "Bearer canary-header-secret"),
                CustomHeader("X-Plain", "plain-value"),
            ),
        ).let { provider ->
            provider.copy(
                models = listOf(
                    Model(
                        modelId = "m1",
                        customHeaders = listOf(CustomHeader("x-key", "model-header-secret")),
                        customBodies = listOf(
                            CustomBody("auth", kotlinx.serialization.json.JsonPrimitive("body-secret")),
                        ),
                        providerOverwrite = ProviderSetting.OpenAI(apiKey = "overwrite-secret"),
                    )
                )
            )
        }
        val google = ProviderSetting.Google(
            apiKey = "google-key-canary",
            privateKey = "-----BEGIN PRIVATE KEY-----\ncanary\n-----END PRIVATE KEY-----",
        )
        val mcp = McpServerConfig.SseTransportServer(
            commonOptions = McpCommonOptions(
                name = "mcp",
                headers = listOf("Authorization" to "Bearer mcp-secret"),
                oauth = McpOAuthState(
                    enabled = true,
                    clientSecret = "mcp-client-secret-canary",
                    accessToken = "mcp-access-token-canary",
                    refreshToken = "mcp-refresh-token-canary",
                    tokenEndpoint = "https://auth.example/token",
                ),
            ),
            url = "https://mcp.example/sse",
        )
        return Settings(
            providers = listOf(openAi, google),
            mcpServers = listOf(mcp),
            networkSetting = NetworkSetting(
                proxyUrl = "http://user:proxypass@proxy.local:3128",
                proxyPassword = "proxy-secret-canary",
                proxyUsername = "proxyuser",
            ),
            webDavConfig = WebDavConfig(
                url = "https://dav.example",
                username = "dav-user",
                password = "dav-secret-canary",
            ),
            s3Config = S3Config(
                endpoint = "https://s3.example",
                accessKeyId = "AKIACANARY",
                secretAccessKey = "s3-secret-canary",
                bucket = "backups",
            ),
            webServerAccessPassword = "web-access-secret-canary",
            webServerJwtEnabled = true,
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `projection masks every credential field and exposes configured flags`() {
        val projected = projectSettingsForWeb(canarySettings())

        // Provider secrets.
        val openAi = projected["providers"]!!.jsonArray[0].jsonObject
        assertEquals("", openAi.str("apiKey"))
        assertEquals(true, openAi["apiKeyConfigured"]?.jsonPrimitive?.boolean)
        val google = projected["providers"]!!.jsonArray[1].jsonObject
        assertEquals("", google.str("privateKey"))
        assertEquals(true, google["privateKeyConfigured"]?.jsonPrimitive?.boolean)

        // Provider customHeaders masked, names preserved.
        val headers = openAi["customHeaders"]!!.jsonArray
        assertEquals("Authorization", headers[0].jsonObject.str("name"))
        assertEquals("", headers[0].jsonObject.str("value"))
        assertEquals(true, headers[0].jsonObject["valueConfigured"]?.jsonPrimitive?.boolean)
        assertEquals("plain-value".let { "" }, headers[1].jsonObject.str("value")) // masked too

        // Nested model credentials.
        val model = openAi["models"]!!.jsonArray[0].jsonObject
        val modelHeaders = model["customHeaders"]!!.jsonArray
        assertEquals("", modelHeaders[0].jsonObject.str("value"))
        assertEquals(true, modelHeaders[0].jsonObject["valueConfigured"]?.jsonPrimitive?.boolean)
        val bodies = model["customBodies"]!!.jsonArray
        assertEquals(JsonNull, bodies[0].jsonObject["value"])
        assertEquals(true, bodies[0].jsonObject["valueConfigured"]?.jsonPrimitive?.boolean)
        val overwrite = model["providerOverwrite"]!!.jsonObject
        assertEquals("", overwrite.str("apiKey"))
        assertEquals(true, overwrite["apiKeyConfigured"]?.jsonPrimitive?.boolean)

        // MCP: headers + oauth masked.
        val mcp = projected["mcpServers"]!!.jsonArray[0].jsonObject
        val mcpHeaders = mcp["commonOptions"]!!.jsonObject["headers"]!!.jsonArray
        assertEquals("", mcpHeaders[0].jsonObject.str("second"))
        assertEquals(true, mcpHeaders[0].jsonObject["valueConfigured"]?.jsonPrimitive?.boolean)
        val oauth = mcp["commonOptions"]!!.jsonObject["oauth"]!!.jsonObject
        assertEquals(JsonNull, oauth["clientSecret"])
        assertEquals(JsonNull, oauth["accessToken"])
        assertEquals(JsonNull, oauth["refreshToken"])
        assertEquals(true, oauth["clientSecretConfigured"]?.jsonPrimitive?.boolean)
        assertEquals(true, oauth["accessTokenConfigured"]?.jsonPrimitive?.boolean)
        assertEquals("https://auth.example/token", oauth.str("tokenEndpoint"))

        // Network proxy: password masked, userinfo stripped, username kept.
        val net = projected["networkSetting"]!!.jsonObject
        assertEquals("", net.str("proxyPassword"))
        assertEquals(true, net["proxyPasswordConfigured"]?.jsonPrimitive?.boolean)
        assertEquals("proxyuser", net.str("proxyUsername"))
        assertFalse(net.str("proxyUrl")!!.contains("proxypass"))
        assertFalse(net.str("proxyUrl")!!.contains("user@"))

        // Backup credentials.
        val dav = projected["webDavConfig"]!!.jsonObject
        assertEquals("", dav.str("password"))
        assertEquals(true, dav["passwordConfigured"]?.jsonPrimitive?.boolean)
        assertEquals("dav-user", dav.str("username"))
        val s3 = projected["s3Config"]!!.jsonObject
        assertEquals("", s3.str("secretAccessKey"))
        assertEquals("", s3.str("accessKeyId"))
        assertEquals(true, s3["secretAccessKeyConfigured"]?.jsonPrimitive?.boolean)
        assertEquals("backups", s3.str("bucket"))

        // The web access password itself never leaves the host.
        assertEquals("", projected.str("webServerAccessPassword"))
        assertEquals(true, projected["webServerAccessPasswordConfigured"]?.jsonPrimitive?.boolean)

        // Non-secret fields pass through.
        assertEquals(true, projected["webServerJwtEnabled"]?.jsonPrimitive?.boolean)
        assertEquals("Canary", openAi.str("name"))
    }

    @Test
    fun `projection emits no secret canary anywhere in the tree`() {
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            JsonObject.serializer(),
            projectSettingsForWeb(canarySettings()),
        )
        for (canary in listOf(
            "sk-canary-apikey", "canary-header-secret", "model-header-secret",
            "body-secret", "overwrite-secret", "google-key-canary",
            "BEGIN PRIVATE KEY", "mcp-secret", "mcp-client-secret-canary",
            "mcp-access-token-canary", "mcp-refresh-token-canary",
            "proxy-secret-canary", "proxypass", "dav-secret-canary",
            "AKIACANARY", "s3-secret-canary", "web-access-secret-canary",
        )) {
            assertFalse("projection leaked canary: $canary", encoded.contains(canary))
        }
    }

    @Test
    fun `creator assistant ids are hidden from the projection`() {
        val creatorId = Uuid.random()
        WebCreatorResources.register(CreatorResourceLookup { it == creatorId })

        val ordinary = Assistant(name = "ordinary")
        val settings = Settings(
            assistants = listOf(ordinary, Assistant(id = creatorId, name = "creator")),
            assistantId = creatorId,
        )
        val projected = projectSettingsForWeb(settings)
        val assistants = projected["assistants"]!!.jsonArray
        assertEquals(1, assistants.size)
        assertEquals("ordinary", assistants[0].jsonObject.str("name"))
        // assistantId pointing at a creator resource is hidden, not leaked.
        assertEquals(JsonNull, projected["assistantId"])
    }

    @Test
    fun `requireVisibleAssistant throws notfound for creator ids`() {
        val creatorId = Uuid.random()
        WebCreatorResources.register(CreatorResourceLookup { it == creatorId })

        val failure = runCatching { creatorId.requireVisibleAssistant() }.exceptionOrNull()
        assertTrue(failure is NotFoundException)

        // Ordinary ids pass; unregistered lookup (no creator package) passes.
        Uuid.random().requireVisibleAssistant()
        WebCreatorResources.unregisterForTests()
        creatorId.requireVisibleAssistant()
    }

    @Test
    fun `proxy url userinfo sanitization is best effort and never crashes`() {
        assertEquals("http://proxy.local:3128/x", sanitizeUrlUserinfo("http://u:p@proxy.local:3128/x"))
        assertEquals("", sanitizeUrlUserinfo(""))
        assertEquals("not a url", sanitizeUrlUserinfo("not a url"))
        assertEquals("http://proxy.local:3128", sanitizeUrlUserinfo("http://proxy.local:3128"))
    }

    @Test
    fun `media generation providers and the upload bucket are masked`() {
        val settings = Settings(
            mediaGenerationProviders = listOf(
                me.rerere.mediagen.provider.MediaGenerationProviderSetting.Aliyun(
                    apiKey = "media-canary-key",
                    workspaceId = "ws-visible",
                ),
            ),
            uploadS3Config = S3Config(
                endpoint = "https://s3.example.com",
                accessKeyId = "upload-access-canary",
                secretAccessKey = "upload-secret-canary",
                bucket = "media",
            ),
        )
        val text = projectSettingsForWeb(settings).toString()
        listOf("media-canary-key", "upload-access-canary", "upload-secret-canary").forEach {
            assertFalse("$it leaked", text.contains(it))
        }
        val provider = projectSettingsForWeb(settings)["mediaGenerationProviders"]!!.jsonArray.single().jsonObject
        assertEquals("", provider["apiKey"]!!.jsonPrimitive.content)
        assertTrue(provider["apiKeyConfigured"]!!.jsonPrimitive.boolean)
        assertEquals("ws-visible", provider["workspaceId"]!!.jsonPrimitive.content)
    }
}
