package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkihub.data.ai.mcp.McpOAuthState
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.ai.mcp.McpTool
import me.rerere.rikkihub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.rerere.rikkihub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Avatar
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.PromptInjection
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.data.model.SystemPromptMode
import me.rerere.rikkihub.data.model.SystemPromptSection
import me.rerere.rikkihub.data.model.Tag
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsMutationUtilsTest {
    @Test
    fun `mergeAssistant copies writable fields and keeps local ones`() {
        val workspaceId = Uuid.random()
        val existing = Assistant(
            name = "old",
            avatar = Avatar.Emoji("🤖"),
            background = "file://bg.png",
            enableMemory = true,
            localTools = emptyList(),
            temperature = 0.2f,
        )
        val incoming = Assistant(
            name = "new",
            tags = emptyList(),
            temperature = 0.8f,
            topP = 0.9f,
            contextMessageLimit = 16,
            streamOutput = false,
            reasoningLevel = ReasoningLevel.HIGH,
            maxTokens = 1024,
            systemPrompt = "hi",
            messageTemplate = "{{ message }}",
            allowConversationSystemPrompt = true,
            allowConversationPromptInjection = true,
            enableWebSearch = true,
            useAssistantAvatar = true,
            avatar = Avatar.Dummy,
            background = null,
            workspaceId = workspaceId,
            enableMemory = true,
            useGlobalMemory = true,
            enableRecentChatsReference = true,
            enableTimeReminder = true,
            timeReminderIntervalMinutes = 30,
        )

        val merged = mergeAssistant(existing, incoming)

        assertEquals("new", merged.name)
        assertEquals(0.8f, merged.temperature)
        assertEquals(0.9f, merged.topP)
        assertEquals(16, merged.contextMessageLimit)
        assertFalse(merged.streamOutput)
        assertEquals(ReasoningLevel.HIGH, merged.reasoningLevel)
        assertEquals(1024, merged.maxTokens)
        assertEquals("hi", merged.systemPrompt)
        assertTrue(merged.allowConversationSystemPrompt)
        assertTrue(merged.enableWebSearch)
        assertEquals(Avatar.Emoji("🤖"), merged.avatar)
        assertEquals("file://bg.png", merged.background)
        // localTools 已可写，但只在 PUT body 显式携带该键时才被覆盖；
        // 不传 localTools 参数（对应 raw body 缺键）时必须保留 existing。
        assertEquals(existing.localTools, merged.localTools)
        // workspace / memory / reminder 字段可写
        assertEquals(workspaceId, merged.workspaceId)
        assertTrue(merged.enableMemory)
        assertTrue(merged.useGlobalMemory)
        assertTrue(merged.enableRecentChatsReference)
        assertTrue(merged.enableTimeReminder)
        assertEquals(30, merged.timeReminderIntervalMinutes)
    }

    @Test
    fun `mergeAssistant applies localTools only when raw body carries the key`() {
        val existing = Assistant(name = "a", localTools = emptyList())
        val incoming = Assistant(name = "b")

        // 显式提供列表 -> 替换（此时可以写入非默认子集）
        val enabled = mergeAssistant(
            existing,
            incoming,
            localTools = listOf(LocalToolOption.Tts, LocalToolOption.Calendar),
        )
        assertEquals(listOf(LocalToolOption.Tts, LocalToolOption.Calendar), enabled.localTools)

        // 显式空数组 -> 清空全部本地工具
        val cleared = mergeAssistant(existing, incoming, localTools = emptyList())
        assertEquals(emptyList<LocalToolOption>(), cleared.localTools)

        // 缺省 -> 保留 existing，不会被 Kotlin 默认值（空列表）覆盖
        val kept = mergeAssistant(
            existing.copy(localTools = listOf(LocalToolOption.Clipboard)),
            incoming,
        )
        assertEquals(listOf(LocalToolOption.Clipboard), kept.localTools)
    }

    @Test
    fun `mergeAssistant keeps prompt mode and sections when the raw body omits them`() {
        val section = SystemPromptSection(title = "t", content = "c", enabled = false)
        val existing = Assistant(systemPromptMode = SystemPromptMode.SECTIONS, promptSections = listOf(section))
        val incoming = Assistant(systemPrompt = "whole")

        val kept = mergeAssistant(existing, incoming, presentKeys = setOf("systemPrompt"))
        assertEquals(SystemPromptMode.SECTIONS, kept.systemPromptMode)
        assertEquals(listOf(section), kept.promptSections)
        assertEquals("whole", kept.systemPrompt)

        val cleared = mergeAssistant(existing, incoming, presentKeys = setOf("systemPromptMode", "promptSections"))
        assertEquals(SystemPromptMode.WHOLE, cleared.systemPromptMode)
        assertEquals(emptyList<SystemPromptSection>(), cleared.promptSections)
    }

    @Test
    fun `parseAssistantUpdate decodes prompt sections and rejects an unknown mode`() {
        val body = buildJsonObject {
            put("name", "a")
            put("systemPromptMode", "sections")
            put("promptSections", buildJsonArray {
                add(buildJsonObject { put("title", "x"); put("content", "y"); put("enabled", false) })
            })
        }
        val (incoming, _) = parseAssistantUpdate(body)
        assertEquals(SystemPromptMode.SECTIONS, incoming.systemPromptMode)
        assertEquals("y", incoming.promptSections.single().content)
        assertFalse(incoming.promptSections.single().enabled)

        val bad = buildJsonObject { put("name", "a"); put("systemPromptMode", "blocks") }
        try {
            parseAssistantUpdate(bad)
            throw AssertionError("expected BadRequestException")
        } catch (_: BadRequestException) {
        }
    }

    @Test
    fun `validateAssistantWritable rejects duplicate prompt section ids`() {
        val section = SystemPromptSection(content = "c")
        try {
            validateAssistantWritable(Assistant(promptSections = listOf(section, section)), Settings())
            throw AssertionError("expected BadRequestException")
        } catch (e: BadRequestException) {
            assertTrue(e.message!!.contains("promptSections"))
        }
    }

    @Test
    fun `parseLocalToolsField returns null when the key is absent`() {
        assertNull(parseLocalToolsField(null))
        assertNull(parseLocalToolsField(buildJsonObject { put("name", "x") }))
    }

    @Test
    fun `parseLocalToolsField parses empty and full arrays`() {
        val empty = parseLocalToolsField(buildJsonObject { put("localTools", buildJsonArray {}) })
        assertEquals(emptyList<LocalToolOption>(), empty)

        val all = parseLocalToolsField(
            buildJsonObject {
                put(
                    "localTools",
                    buildJsonArray {
                        listOf(
                            "javascript_engine", "time_info", "clipboard", "tts",
                            "ask_user", "screen_time", "calendar", "chart_display",
                        ).forEach { add(buildJsonObject { put("type", it) }) }
                    },
                )
            },
        )
        assertEquals(
            listOf(
                LocalToolOption.JavascriptEngine,
                LocalToolOption.TimeInfo,
                LocalToolOption.Clipboard,
                LocalToolOption.Tts,
                LocalToolOption.AskUser,
                LocalToolOption.ScreenTime,
                LocalToolOption.Calendar,
                LocalToolOption.ChartDisplay,
            ),
            all,
        )
    }

    @Test
    fun `parseLocalToolsField roundtrips through Assistant serialization`() {
        val assistant = Assistant(
            localTools = listOf(LocalToolOption.AskUser, LocalToolOption.TimeInfo),
        )
        val raw = JsonInstant.encodeToJsonElement(assistant).jsonObject
        assertEquals(assistant.localTools, parseLocalToolsField(raw))
    }

    @Test
    fun `parseLocalToolsField dedupes repeated types`() {
        val parsed = parseLocalToolsField(
            buildJsonObject {
                put(
                    "localTools",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "tts") })
                        add(buildJsonObject { put("type", "tts") })
                        add(buildJsonObject { put("type", "clipboard") })
                    },
                )
            },
        )
        assertEquals(listOf(LocalToolOption.Tts, LocalToolOption.Clipboard), parsed)
    }

    @Test(expected = BadRequestException::class)
    fun `parseLocalToolsField rejects unknown type`() {
        parseLocalToolsField(
            buildJsonObject {
                put(
                    "localTools",
                    buildJsonArray { add(buildJsonObject { put("type", "shell_exec") }) },
                )
            },
        )
    }

    @Test(expected = BadRequestException::class)
    fun `parseLocalToolsField rejects non array value`() {
        parseLocalToolsField(buildJsonObject { put("localTools", "tts") })
    }

    @Test(expected = BadRequestException::class)
    fun `parseLocalToolsField rejects null value`() {
        parseLocalToolsField(
            buildJsonObject { put("localTools", JsonNull) },
        )
    }

    @Test(expected = BadRequestException::class)
    fun `parseLocalToolsField rejects malformed entries`() {
        parseLocalToolsField(
            buildJsonObject {
                put("localTools", buildJsonArray { add(buildJsonObject { put("kind", "tts") }) })
            },
        )
    }

    @Test(expected = BadRequestException::class)
    fun `parseLocalToolsField rejects oversized arrays`() {
        parseLocalToolsField(
            buildJsonObject {
                put(
                    "localTools",
                    buildJsonArray {
                        repeat(9) { add(buildJsonObject { put("type", "tts") }) }
                    },
                )
            },
        )
    }

    @Test
    fun `mergeProvider keeps models and transient fields`() {
        val model = Model(modelId = "gpt-4", displayName = "GPT-4")
        val existing = ProviderSetting.OpenAI(
            name = "OpenAI",
            apiKey = "old",
            models = listOf(model),
            builtIn = true,
        )
        val incoming = ProviderSetting.OpenAI(
            name = "Renamed",
            apiKey = "new",
            baseUrl = "https://example.com/v1",
            chatCompletionsPath = "/chat",
            useResponseApi = true,
            includeHistoryReasoning = false,
            responsesPath = "/v2/responses",
            enabled = false,
            models = emptyList(),
            builtIn = false,
        )

        val merged = mergeProvider(
            existing,
            ProviderUpdateDraft(
                incoming = incoming,
                customHeaders = null,
                apiKey = incoming.apiKey,
                privateKey = null,
                useInteractionsApi = null,
            ),
        ) as ProviderSetting.OpenAI

        assertEquals("Renamed", merged.name)
        assertEquals("new", merged.apiKey)
        assertEquals("https://example.com/v1", merged.baseUrl)
        assertEquals("/chat", merged.chatCompletionsPath)
        assertTrue(merged.useResponseApi)
        assertFalse(merged.includeHistoryReasoning)
        assertEquals("/v2/responses", merged.responsesPath)
        assertFalse(merged.enabled)
        assertEquals(listOf(model), merged.models)
        assertTrue(merged.builtIn)
    }

    @Test
    fun `mergeProvider keeps devin deviceSeed`() {
        val existing = ProviderSetting.Devin(
            name = "Devin",
            clientVersion = "1",
            deviceSeed = "seed-abc",
        )
        val incoming = ProviderSetting.Devin(
            name = "Devin Renamed",
            clientVersion = "2",
            enabled = true,
        )

        val merged = mergeProvider(
            existing,
            ProviderUpdateDraft(
                incoming = incoming,
                customHeaders = null,
                apiKey = null,
                privateKey = null,
                useInteractionsApi = null,
            ),
        ) as ProviderSetting.Devin

        assertEquals("Devin Renamed", merged.name)
        assertEquals("2", merged.clientVersion)
        assertTrue(merged.enabled)
        assertEquals("seed-abc", merged.deviceSeed)
    }

    @Test
    fun `mergeProvider keeps antigravity models and takes client version`() {
        val model = Model(modelId = "gemini-3-flash")
        val existing = ProviderSetting.Antigravity(name = "Antigravity", models = listOf(model))
        val incoming = ProviderSetting.Antigravity(name = "AG", clientVersion = "2.9.4", enabled = true)

        val merged = mergeProvider(
            existing,
            ProviderUpdateDraft(
                incoming = incoming,
                customHeaders = null,
                apiKey = null,
                privateKey = null,
                useInteractionsApi = null,
            ),
        ) as ProviderSetting.Antigravity

        assertEquals("AG", merged.name)
        assertEquals("2.9.4", merged.clientVersion)
        assertTrue(merged.enabled)
        assertEquals(listOf(model), merged.models)
        assertEquals(existing.id, merged.id)
    }

    @Test(expected = BadRequestException::class)
    fun `mergeProvider rejects type change`() {
        mergeProvider(
            ProviderSetting.OpenAI(),
            ProviderUpdateDraft(
                incoming = ProviderSetting.Google(),
                customHeaders = null,
                apiKey = null,
                privateKey = null,
                useInteractionsApi = null,
            ),
        )
    }

    @Test
    fun `mergeMcpServer preserves tools oauth and can switch transport`() {
        val existing = McpServerConfig.SseTransportServer(
            commonOptions = McpCommonOptions(
                name = "old",
                tools = listOf(McpTool(name = "search")),
                oauth = McpOAuthState(enabled = true, accessToken = "token"),
            ),
            url = "https://old.example/sse",
        )
        val incoming = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(
                enable = false,
                name = "new",
                headers = listOf("X-Key" to "secret"),
                tools = emptyList(),
                oauth = null,
            ),
            url = "https://new.example/mcp",
        )

        val merged = mergeMcpServer(
            existing,
            McpUpdateDraft(
                incoming = incoming,
                headers = incoming.commonOptions.headers.map { McpHeaderDraft(it.first, it.second) },
            ),
        ) as McpServerConfig.StreamableHTTPServer

        assertEquals(existing.id, merged.id)
        assertEquals("new", merged.commonOptions.name)
        assertFalse(merged.commonOptions.enable)
        assertEquals(listOf("X-Key" to "secret"), merged.commonOptions.headers)
        assertEquals(existing.commonOptions.tools, merged.commonOptions.tools)
        assertEquals(existing.commonOptions.oauth, merged.commonOptions.oauth)
        assertEquals("https://new.example/mcp", merged.url)
    }

    @Test
    fun `validateAssistantWritable accepts known bindings`() {
        val tag = Tag(id = Uuid.random(), name = "work")
        val mcp = McpServerConfig.SseTransportServer()
        val injection = PromptInjection.ModeInjection()
        val lorebook = Lorebook()
        val quick = QuickMessage()
        val model = Model(modelId = "gpt", displayName = "gpt")
        val settings = Settings(
            assistantTags = listOf(tag),
            mcpServers = listOf(mcp),
            modeInjections = listOf(injection),
            lorebooks = listOf(lorebook),
            quickMessages = listOf(quick),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )
        val assistant = Assistant(
            tags = listOf(tag.id),
            chatModelId = model.id,
            mcpServers = setOf(mcp.id),
            modeInjectionIds = setOf(injection.id),
            lorebookIds = setOf(lorebook.id),
            quickMessageIds = setOf(quick.id),
            temperature = 1f,
            topP = 0.5f,
            contextMessageLimit = 0,
            maxTokens = 100,
        )

        validateAssistantWritable(assistant, settings)
    }

    @Test(expected = BadRequestException::class)
    fun `validateAssistantWritable rejects unknown mcp`() {
        validateAssistantWritable(
            Assistant(mcpServers = setOf(Uuid.random())),
            Settings(),
        )
    }

    @Test(expected = BadRequestException::class)
    fun `validateAssistantWritable rejects temperature out of range`() {
        validateAssistantWritable(Assistant(temperature = 2.1f), Settings())
    }

    @Test(expected = BadRequestException::class)
    fun `validateAssistantWritable rejects invalid maxTokens`() {
        validateAssistantWritable(Assistant(maxTokens = 0), Settings())
    }

    @Test(expected = BadRequestException::class)
    fun `validateAssistantWritable rejects time reminder interval below 1`() {
        validateAssistantWritable(Assistant(timeReminderIntervalMinutes = 0), Settings())
    }

    @Test
    fun `reorderByIds permutes existing items`() {
        val a = Assistant(name = "a")
        val b = Assistant(name = "b")
        val c = Assistant(name = "c")

        val reordered = reorderByIds(listOf(a, b, c), listOf(c.id, a.id, b.id)) { it.id }

        assertEquals(listOf(c, a, b), reordered)
    }

    @Test(expected = BadRequestException::class)
    fun `reorderByIds rejects incomplete permutation`() {
        val a = Assistant()
        val b = Assistant()
        reorderByIds(listOf(a, b), listOf(a.id)) { it.id }
    }

    @Test
    fun `default assistant and builtin provider ids match store constants`() {
        assertTrue(isDefaultAssistant(DEFAULT_ASSISTANT_ID))
        DEFAULT_ASSISTANTS_IDS.forEach { assertTrue(isDefaultAssistant(it)) }
        assertFalse(isDefaultAssistant(Uuid.random()))

        DEFAULT_PROVIDERS.forEach { assertTrue(isBuiltInProvider(it.id)) }
        assertFalse(isBuiltInProvider(Uuid.random()))
    }

    @Test
    fun `createProvider uses type serial names`() {
        assertTrue(createProvider("openai", "Custom") is ProviderSetting.OpenAI)
        assertTrue(createProvider("google", null) is ProviderSetting.Google)
        assertTrue(createProvider("claude", " ") is ProviderSetting.Claude)
        assertEquals("Custom", (createProvider("openai", "Custom") as ProviderSetting.OpenAI).name)
        assertEquals("Google", (createProvider("google", null) as ProviderSetting.Google).name)
    }

    @Test(expected = BadRequestException::class)
    fun `createProvider rejects unknown type`() {
        createProvider("azure", null)
    }
}
