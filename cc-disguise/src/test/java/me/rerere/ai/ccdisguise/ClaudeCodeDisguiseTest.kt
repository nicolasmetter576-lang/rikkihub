package me.rerere.ai.ccdisguise

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClaudeCodeDisguiseTest {
    @Test
    fun `applyBody with no system inserts identity block`() {
        val body = ClaudeCodeDisguise.applyBody(JsonObject(emptyMap()), "user_test")
        val system = body["system"]!!.jsonArray
        assertEquals(1, system.size)
        assertEquals(ClaudeCodeDisguise.IDENTITY, system[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `applyBody prepends identity to system string`() {
        val body = ClaudeCodeDisguise.applyBody(
            buildJsonObject { put("system", "assistant prompt") },
            "user_test"
        )
        val system = body["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertEquals(ClaudeCodeDisguise.IDENTITY, system[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("assistant prompt", system[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `applyBody prepends identity to system array without duplicating`() {
        val original = buildJsonObject {
            put(
                "system",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", ClaudeCodeDisguise.IDENTITY)
                    })
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "assistant prompt")
                    })
                }
            )
        }
        val body = ClaudeCodeDisguise.applyBody(original, "user_test")
        val system = body["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertEquals(ClaudeCodeDisguise.IDENTITY, system[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("assistant prompt", system[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `applyBody writes user_id and keeps other metadata`() {
        val body = ClaudeCodeDisguise.applyBody(
            buildJsonObject {
                put("metadata", buildJsonObject {
                    put("keep", "yes")
                    put("user_id", "old")
                })
            },
            "user_new"
        )
        val metadata = body["metadata"]!!.jsonObject
        assertEquals("yes", metadata["keep"]!!.jsonPrimitive.content)
        assertEquals("user_new", metadata["user_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mergeBeta appends missing tag and keeps existing`() {
        assertEquals(ClaudeCodeDisguise.BETA_TAG, ClaudeCodeDisguise.mergeBeta(null))
        assertEquals(ClaudeCodeDisguise.BETA_TAG, ClaudeCodeDisguise.mergeBeta("   "))
        assertEquals(
            "prompt-caching-2024-07-31,${ClaudeCodeDisguise.BETA_TAG}",
            ClaudeCodeDisguise.mergeBeta("prompt-caching-2024-07-31")
        )
        assertEquals(
            "claude-code-20250219,prompt-caching-2024-07-31",
            ClaudeCodeDisguise.mergeBeta("claude-code-20250219,prompt-caching-2024-07-31")
        )
    }

    @Test
    fun `applyHeaders overwrites ua and x-app and merges beta`() {
        val request = ClaudeCodeDisguise.applyHeaders(
            Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("User-Agent", "Rikkihub-Android/2.4.16")
                .header("anthropic-beta", "prompt-caching-2024-07-31"),
            "prompt-caching-2024-07-31"
        ).build()
        assertEquals(ClaudeCodeDisguise.USER_AGENT, request.header("User-Agent"))
        assertEquals(ClaudeCodeDisguise.X_APP, request.header("x-app"))
        assertEquals(
            "prompt-caching-2024-07-31,${ClaudeCodeDisguise.BETA_TAG}",
            request.header("anthropic-beta")
        )
    }

    @Test
    fun `identity generate matches claude code shape and persists`() {
        val id = ClaudeCodeIdentity.generate()
        assertTrue(ClaudeCodeIdentity.isValid(id))
        assertFalse(ClaudeCodeIdentity.isValid("user_short"))
        assertFalse(ClaudeCodeIdentity.isValid("not-a-user"))

        val dir = File.createTempFile("cc-disguise", "dir").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val file = File(dir, "user-id")
        val first = ClaudeCodeIdentity.loadOrCreate(file)
        val second = ClaudeCodeIdentity.loadOrCreate(file)
        assertEquals(first, second)
        assertTrue(ClaudeCodeIdentity.isValid(first))
    }

    // ── Config-aware tests ──────────────────────────────────────

    @Test
    fun `default config produces identical output to legacy constants`() {
        val config = ClaudeCodeDisguiseConfig().copy(
            autoUserId = "user_abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234_account__session_550e8400-e29b-41d4-a716-446655440000"
        )
        // Body: same system identity text, same user_id
        val bodyLegacy = ClaudeCodeDisguise.applyBody(JsonObject(emptyMap()), config.effectiveUserId())
        val bodyConfig = ClaudeCodeDisguise.applyBody(JsonObject(emptyMap()), config)
        assertEquals(
            bodyLegacy["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
            bodyConfig["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
        assertEquals(
            bodyLegacy["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content,
            bodyConfig["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content
        )
        // Headers: same UA, x-app, beta
        val reqLegacy = ClaudeCodeDisguise.applyHeaders(
            Request.Builder().url("https://api.anthropic.com/v1/messages"), null
        ).build()
        val reqConfig = ClaudeCodeDisguise.applyHeaders(
            Request.Builder().url("https://api.anthropic.com/v1/messages"), null, config
        ).build()
        assertEquals(reqLegacy.header("User-Agent"), reqConfig.header("User-Agent"))
        assertEquals(reqLegacy.header("x-app"), reqConfig.header("x-app"))
        assertEquals(reqLegacy.header("anthropic-beta"), reqConfig.header("anthropic-beta"))
    }

    @Test
    fun `config with custom system text inserts custom text`() {
        val config = ClaudeCodeDisguiseConfig(
            systemPrompt = "I am a custom assistant.",
            autoUserId = "user_abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234_account__session_550e8400-e29b-41d4-a716-446655440000"
        )
        val body = ClaudeCodeDisguise.applyBody(JsonObject(emptyMap()), config)
        val system = body["system"]!!.jsonArray
        assertEquals(1, system.size)
        assertEquals("I am a custom assistant.", system[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `config with system disabled does not modify system`() {
        val config = ClaudeCodeDisguiseConfig(
            systemPromptEnabled = false,
            autoUserId = "user_abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234_account__session_550e8400-e29b-41d4-a716-446655440000"
        )
        val original = buildJsonObject { put("system", "original prompt") }
        val body = ClaudeCodeDisguise.applyBody(original, config)
        // system should remain a string, not converted to array
        assertEquals("original prompt", body["system"]!!.jsonPrimitive.content)
    }

    @Test
    fun `config with system disabled and no existing system keeps no system`() {
        val config = ClaudeCodeDisguiseConfig(
            systemPromptEnabled = false,
            autoUserId = "user_abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234_account__session_550e8400-e29b-41d4-a716-446655440000"
        )
        val body = ClaudeCodeDisguise.applyBody(JsonObject(emptyMap()), config)
        assertNull(body["system"])
        // user_id should still be set
        assertEquals(
            config.autoUserId,
            body["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `config manual userId mode uses manual value`() {
        val config = ClaudeCodeDisguiseConfig(
            userIdMode = UserIdMode.MANUAL,
            autoUserId = "user_abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234_account__session_550e8400-e29b-41d4-a716-446655440000",
            manualUserId = "my-custom-desktop-id"
        )
        val body = ClaudeCodeDisguise.applyBody(JsonObject(emptyMap()), config)
        assertEquals(
            "my-custom-desktop-id",
            body["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `config manual userId mode falls back to auto when manual is blank`() {
        val config = ClaudeCodeDisguiseConfig(
            userIdMode = UserIdMode.MANUAL,
            autoUserId = "user_abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234_account__session_550e8400-e29b-41d4-a716-446655440000",
            manualUserId = ""
        )
        assertEquals(config.autoUserId, config.effectiveUserId())
    }

    @Test
    fun `config custom headers apply correctly`() {
        val config = ClaudeCodeDisguiseConfig(
            userAgent = "custom-agent/2.0",
            xApp = "desktop",
            anthropicBeta = "beta-one,beta-two",
            autoUserId = "test"
        )
        val request = ClaudeCodeDisguise.applyHeaders(
            Request.Builder().url("https://api.anthropic.com/v1/messages"),
            null,
            config
        ).build()
        assertEquals("custom-agent/2.0", request.header("User-Agent"))
        assertEquals("desktop", request.header("x-app"))
        assertEquals("beta-one,beta-two", request.header("anthropic-beta"))
    }

    @Test
    fun `mergeBeta with config tags deduplicates and preserves order`() {
        val result = ClaudeCodeDisguise.mergeBeta(
            "prompt-caching-2024-07-31,claude-code-20250219",
            listOf("claude-code-20250219", "new-feature")
        )
        assertEquals("prompt-caching-2024-07-31,claude-code-20250219,new-feature", result)
    }

    @Test
    fun `mergeBeta with empty existing and config tags`() {
        val result = ClaudeCodeDisguise.mergeBeta(null, listOf("tag-a", "tag-b"))
        assertEquals("tag-a,tag-b", result)
    }

    @Test
    fun `sanitized config replaces blank headers with defaults`() {
        val config = ClaudeCodeDisguiseConfig(
            userAgent = "",
            xApp = "  ",
            anthropicBeta = ",,,",
            autoUserId = "test"
        )
        val safe = config.sanitized()
        assertEquals(ClaudeCodeDisguise.USER_AGENT, safe.userAgent)
        assertEquals(ClaudeCodeDisguise.X_APP, safe.xApp)
        assertEquals(ClaudeCodeDisguise.BETA_TAG, safe.anthropicBeta)
    }

    @Test
    fun `sanitized config strips CR LF from headers`() {
        val config = ClaudeCodeDisguiseConfig(
            userAgent = "agent\r\nEvil: header",
            xApp = "cli\nevil",
            anthropicBeta = "tag-a\r\n,tag-b",
            autoUserId = "test"
        )
        val safe = config.sanitized()
        assertFalse(safe.userAgent.contains('\n'))
        assertFalse(safe.userAgent.contains('\r'))
        assertFalse(safe.xApp.contains('\n'))
        assertFalse(safe.anthropicBeta.contains('\n'))
    }

    @Test
    fun `config serialization round trip`() {
        val config = ClaudeCodeDisguiseConfig(
            systemPromptEnabled = false,
            systemPrompt = "Custom identity.",
            userIdMode = UserIdMode.MANUAL,
            autoUserId = "user_abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234_account__session_test",
            manualUserId = "desktop-id-123",
            userAgent = "custom-cli/3.0",
            xApp = "desktop",
            anthropicBeta = "beta-a,beta-b"
        )
        val json = Json.encodeToString(ClaudeCodeDisguiseConfig.serializer(), config)
        val decoded = Json.decodeFromString(ClaudeCodeDisguiseConfig.serializer(), json)
        assertEquals(config, decoded)
    }

    @Test
    fun `config deserialization with missing fields uses defaults`() {
        // Simulates an old backup without the ccDisguiseConfig field
        val json = "{}"
        val decoded = Json.decodeFromString(ClaudeCodeDisguiseConfig.serializer(), json)
        assertEquals(ClaudeCodeDisguiseConfig.DEFAULT.systemPromptEnabled, decoded.systemPromptEnabled)
        assertEquals(ClaudeCodeDisguiseConfig.DEFAULT.systemPrompt, decoded.systemPrompt)
        assertEquals(ClaudeCodeDisguiseConfig.DEFAULT.userIdMode, decoded.userIdMode)
        assertEquals(ClaudeCodeDisguiseConfig.DEFAULT.userAgent, decoded.userAgent)
        assertEquals(ClaudeCodeDisguiseConfig.DEFAULT.xApp, decoded.xApp)
        assertEquals(ClaudeCodeDisguiseConfig.DEFAULT.anthropicBeta, decoded.anthropicBeta)
    }

    @Test
    fun `identity readLegacyFile returns null for missing file`() {
        assertNull(ClaudeCodeIdentity.readLegacyFile(null))
        val nonExistent = File("/tmp/cc-disguise-test-nonexistent/user-id")
        assertNull(ClaudeCodeIdentity.readLegacyFile(nonExistent))
    }

    @Test
    fun `identity readLegacyFile returns valid id from existing file`() {
        val dir = File.createTempFile("cc-test", "dir").apply { delete(); mkdirs(); deleteOnExit() }
        val file = File(dir, "user-id")
        val id = ClaudeCodeIdentity.generate()
        file.writeText(id)
        assertEquals(id, ClaudeCodeIdentity.readLegacyFile(file))
        file.delete(); dir.delete()
    }

    @Test
    fun `identity readLegacyFile returns null for invalid content`() {
        val dir = File.createTempFile("cc-test", "dir").apply { delete(); mkdirs(); deleteOnExit() }
        val file = File(dir, "user-id")
        file.writeText("not-valid")
        assertNull(ClaudeCodeIdentity.readLegacyFile(file))
        file.delete(); dir.delete()
    }

    @Test
    fun `custom system deduplication with config`() {
        val customText = "I am a custom CC."
        val config = ClaudeCodeDisguiseConfig(
            systemPrompt = customText,
            autoUserId = "test"
        )
        val original = buildJsonObject {
            put("system", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", customText)
                })
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "assistant prompt")
                })
            })
        }
        val body = ClaudeCodeDisguise.applyBody(original, config)
        val system = body["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertEquals(customText, system[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prependIdentity keeps later blocks including cache_control`() {
        val system = ClaudeCodeDisguise.prependIdentity(
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "assistant prompt")
                    put("cache_control", buildJsonObject { put("type", "ephemeral") })
                })
            }
        )
        assertEquals(2, system.size)
        assertEquals(ClaudeCodeDisguise.IDENTITY, system[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(system[0].jsonObject["cache_control"])
        assertEquals("assistant prompt", system[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system[1].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
