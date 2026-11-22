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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Canary tests for [projectSettingsForWeb]: every credential field in the
 * Settings graph must be masked ("" / null + `*Configured` flag) — never the
 * raw value.
 */
class WebSettingsProjectionTest {

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
            customCliAgents = listOf(
                me.rerere.rikkihub.data.cli.CustomCliAgentConfig(
                    id = "custom-cli",
                    name = "My agent",
                    executable = "my-agent",
                    envVars = listOf(CustomHeader("MY_AGENT_KEY", "cli-custom-secret-canary")),
                )
            ),
            builtInCliEnvOverrides = listOf(
                me.rerere.rikkihub.data.cli.CliEnvOverride(
                    id = me.rerere.rikkihub.data.cli.CliAgent.AGY,
                    envVars = listOf(CustomHeader("GEMINI_API_KEY", "cli-gemini-secret-canary")),
                )
            ),
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
            "cli-custom-secret-canary", "cli-gemini-secret-canary",
        )) {
            assertFalse("projection leaked canary: $canary", encoded.contains(canary))
        }
    }

    @Test
    fun `cli agent environment values are masked but names and the rest stay`() {
        val projected = projectSettingsForWeb(canarySettings())
        val custom = projected["customCliAgents"]!!.jsonArray.single().jsonObject
        assertEquals("my-agent", custom.str("executable"))
        val customVar = custom["envVars"]!!.jsonArray.single().jsonObject
        assertEquals("MY_AGENT_KEY", customVar.str("name"))
        assertEquals("", customVar.str("value"))
        assertTrue(customVar["valueConfigured"]!!.jsonPrimitive.boolean)
        val builtIn = projected["builtInCliEnvOverrides"]!!.jsonArray.single().jsonObject
        assertEquals(me.rerere.rikkihub.data.cli.CliAgent.AGY, builtIn.str("id"))
        val builtInVar = builtIn["envVars"]!!.jsonArray.single().jsonObject
        assertEquals("GEMINI_API_KEY", builtInVar.str("name"))
        assertEquals("", builtInVar.str("value"))
    }

    @Test
    fun `the creator assistant is projected like any other assistant`() {
        val creatorId = me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ID
        val settings = Settings(
            assistants = listOf(Assistant(name = "ordinary"), Assistant(id = creatorId, name = "creator")),
            assistantId = creatorId,
        )
        val projected = projectSettingsForWeb(settings)
        assertEquals(listOf("ordinary", "creator"), projected["assistants"]!!.jsonArray.map { it.jsonObject.str("name") })
        assertEquals(creatorId.toString(), projected["assistantId"]!!.jsonPrimitive.content)
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

    @Test
    fun `settings snapshot carries native tavern data and lorebook summaries`() {
        val library = me.rerere.rikkihub.data.model.newLibraryEntry("note")
        val book = me.rerere.rikkihub.data.model.Lorebook(
            name = "world",
            entries = listOf(me.rerere.tavern.worldinfo.WorldInfoEntry(uid = 1, comment = "dragons", content = "secret body")),
        )
        val assistant = Assistant(name = "a", libraryEntryIds = setOf(Uuid.parse(library.identifier)))
        val settings = Settings(
            assistants = listOf(assistant),
            promptLibrary = listOf(library),
            lorebooks = listOf(book),
            tavern = me.rerere.rikkihub.data.model.TavernSettings(
                globalVariables = kotlinx.serialization.json.buildJsonObject {
                    put("password", kotlinx.serialization.json.JsonPrimitive("hunter2"))
                    put("apiKey", kotlinx.serialization.json.JsonPrimitive("not-a-credential"))
                },
            ),
        )
        val projected = projectSettingsForWeb(settings)

        val projectedAssistant = projected["assistants"]!!.jsonArray.single().jsonObject
        assertTrue(projectedAssistant.containsKey("promptPreset"))
        assertEquals(listOf(library.identifier), projectedAssistant["libraryEntryIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        listOf("systemPrompt", "systemPromptMode", "promptSections", "regexes", "modeInjectionIds").forEach {
            assertFalse("$it must not be sent any more", projectedAssistant.containsKey(it))
        }
        assertFalse(projected.containsKey("modeInjections"))
        assertEquals(library.identifier, projected["promptLibrary"]!!.jsonArray.single().jsonObject["identifier"]!!.jsonPrimitive.content)

        // 世界书只带摘要，条目走 /api/lorebooks/{id}
        val summary = projected["lorebooks"]!!.jsonArray.single().jsonObject
        assertEquals("world", summary["name"]!!.jsonPrimitive.content)
        assertEquals(1, summary["entryCount"]!!.jsonPrimitive.content.toInt())
        assertFalse(summary.containsKey("entries"))
        assertFalse(projected.toString().contains("secret body"))

        // 变量名叫 password / apiKey 不会被当成凭据遮掉
        val variables = projected["tavern"]!!.jsonObject["globalVariables"]!!.jsonObject
        assertEquals("hunter2", variables["password"]!!.jsonPrimitive.content)
        assertEquals("not-a-credential", variables["apiKey"]!!.jsonPrimitive.content)
        assertFalse(variables.containsKey("passwordConfigured"))
    }

    @Test
    fun `the preset json passes through the assistant projection untouched`() {
        val preset = me.rerere.rikkihub.data.model.AssistantPresets.withSystemPrompt("be nice").let {
            it.copy(extra = kotlinx.serialization.json.buildJsonObject {
                put("proxy_password", kotlinx.serialization.json.JsonPrimitive("imported-extra"))
            })
        }
        val projected = projectAssistantForWeb(Assistant(name = "a", promptPreset = preset))
        assertEquals(preset.toJson(), projected["promptPreset"])
    }

    @Test
    fun `the settings snapshot carries the cliché cleanup switch but not its rule packs`() {
        val packs = listOf(
            me.rerere.rikkihub.data.ai.qubagu.QubaguRulePack(
                id = "p",
                rules = listOf(me.rerere.rikkihub.data.ai.qubagu.QubaguRule.Replace(id = "r", find = "瞳孔地震")),
            ),
        )
        val projected = projectSettingsForWeb(Settings(qubagu = me.rerere.rikkihub.data.ai.qubagu.QubaguConfig(enabled = true, packs = packs)))
        val qubagu = projected["qubagu"]!!.jsonObject
        assertTrue(qubagu["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(qubagu.containsKey("packs"))
        assertFalse(projected.toString().contains("瞳孔地震"))
    }

    @Test
    fun `structured apiKeys and serviceAccounts are projected with secrets stripped and legacy synthesized`() {
        val openAi = ProviderSetting.OpenAI(
            apiKeys = listOf(
                me.rerere.ai.provider.ApiKeyEntry(id = "k1", key = "sk-structured-secret-abcd", label = "Primary", enabled = true),
            ),
        )
        val googleLegacyMulti = ProviderSetting.Google(
            apiKey = "AIza-first-secret-1234, AIza-second-secret-5678",
            vertexAI = true,
            useServiceAccount = true,
            serviceAccounts = listOf(
                me.rerere.ai.provider.VertexServiceAccount(
                    id = "sa1",
                    label = "SA One",
                    projectId = "proj-sa-1",
                    serviceAccountEmail = "sa1@example.invalid",
                    privateKey = "-----BEGIN PRIVATE KEY-----\nsa-secret-pem\n-----END PRIVATE KEY-----",
                    location = "global",
                    enabled = true,
                )
            ),
        )
        val projected = projectSettingsForWeb(Settings(providers = listOf(openAi, googleLegacyMulti)))
        val text = projected.toString()
        for (secret in listOf("sk-structured-secret-abcd", "AIza-first-secret-1234", "AIza-second-secret-5678", "sa-secret-pem")) {
            assertFalse("projection leaked secret: $secret", text.contains(secret))
        }

        val p0 = projected["providers"]!!.jsonArray[0].jsonObject
        assertEquals(true, p0["apiKeyConfigured"]!!.jsonPrimitive.boolean)
        val k1 = p0["apiKeys"]!!.jsonArray.single().jsonObject
        assertEquals("k1", k1.str("id"))
        assertEquals("", k1.str("key"))
        assertEquals(true, k1["keyConfigured"]!!.jsonPrimitive.boolean)
        assertEquals("••••abcd", k1.str("keyPreview"))

        val p1 = projected["providers"]!!.jsonArray[1].jsonObject
        assertEquals(2, p1["apiKeys"]!!.jsonArray.size)
        assertEquals(true, p1["privateKeyConfigured"]!!.jsonPrimitive.boolean)
        val sa1 = p1["serviceAccounts"]!!.jsonArray.single().jsonObject
        assertEquals("proj-sa-1", sa1.str("projectId"))
        assertEquals("", sa1.str("privateKey"))
        assertEquals(true, sa1["privateKeyConfigured"]!!.jsonPrimitive.boolean)
    }
}
