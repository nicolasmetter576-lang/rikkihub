package me.rerere.rikkihub.data.ai.baseline

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolConcurrency
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.ai.ScriptedAttempt
import me.rerere.rikkihub.data.ai.ScriptedProvider
import me.rerere.rikkihub.data.ai.testChatModel
import me.rerere.rikkihub.data.ai.testGenerationLoop
import me.rerere.rikkihub.data.ai.testSettings
import me.rerere.rikkihub.data.ai.textStepScript
import me.rerere.rikkihub.data.ai.tools.ToolExecutionConfig
import me.rerere.rikkihub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkihub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.OcrTransformer
import me.rerere.rikkihub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkihub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.ai.tavern.RikkihubMacros
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.ai.tavern.TurnPreparation
import me.rerere.rikkihub.data.ai.tavern.TurnRequest
import me.rerere.rikkihub.data.datastore.migration.TavernSettingsMigration
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.legacy2320.LegacyAssistant as Assistant
import me.rerere.rikkihub.legacy2320.LegacyInjectionPosition as InjectionPosition
import me.rerere.rikkihub.legacy2320.LegacyLorebook as Lorebook
import me.rerere.rikkihub.legacy2320.LegacyPromptInjection as PromptInjection
import me.rerere.rikkihub.legacy2320.LegacySystemPromptMode as SystemPromptMode
import me.rerere.rikkihub.legacy2320.LegacySystemPromptSection as SystemPromptSection
import me.rerere.rikkihub.utils.JsonInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.tavern.core.GenerationType
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.data.workspace.WORKSPACE_PATH_METADATA_KEY
import me.rerere.rikkihub.data.workspace.WorkspaceFileSnapshotTransformer
import me.rerere.rikkihub.data.workspace.withWorkspaceFileSnapshot
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * 旧管线（2.32.0 的请求组装）基准。
 *
 * 把一批固定夹具——助手、设置、记忆、工具、对话历史——经**真实的** `GenerationLoop` 与**真实的**输入转换器链
 * （和 `ChatService.generationInputTransformers` 同一顺序）跑一遍，把最终交给 provider 的 messages 与关键参数
 * 记成 golden。请求组装迁到酒馆管线之后，用同一批夹具重放：默认布局下必须逐字相同；
 * 有意的差异（比如多分块助手提示词块与块之间的分隔）只允许出现在迁移报告列出的项上，并且改的是 golden 对应用例的期望，
 * 不是放宽比较。
 *
 * 环境固定：时区 UTC、语言 en-US（时间提醒与占位符会用到）；平台相关的三个占位符（电量/系统版本/设备）在测试里换成固定值；
 * `{cur_date}` 的值取当天日期，序列化时换成 `<TODAY>`。
 *
 * 链上没有覆盖的：`WorkspaceReminderTransformer` 与创造助手的转换器（要数据库/工作区，且只对绑定工作区或创造助手生效）；
 * 文档内容读取（`DocumentAsPromptTransformer` 用 `android.net.Uri`，JVM 单测里恒走“invalid file uri”分支，
 * 所以这里的文档用例只固定包裹格式，内容是该错误文本）；OCR（要模型和真实文件）。这些要在设备上验证。
 */
internal class LegacyCase(
    val name: String,
    val note: String,
    val assistant: Assistant,
    val messages: List<UIMessage>,
    val configure: (Settings) -> Settings = { it },
    val injections: Injections = Injections(),
    val memories: List<MemoryFile>? = null,
    val tools: List<Tool> = emptyList(),
    val conversationSystemPrompt: String? = null,
    val conversationModeInjectionIds: Set<Uuid> = emptySet(),
    val conversationLorebookIds: Set<Uuid> = emptySet(),
)

/** 2.32.0 的模式注入与世界书夹具（旧形状）；重放时经设置迁移转成提示词库和酒馆世界书。 */
internal class Injections(
    val modes: List<PromptInjection.ModeInjection> = emptyList(),
    val books: List<Lorebook> = emptyList(),
)

internal object LegacyRequestBaseline {
    const val NICKNAME = "Alice"
    const val MODEL_ID = "scripted-chat"

    // ---------------------------------------------------------------- 夹具

    private fun id(n: Int): Uuid = Uuid.parse("00000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

    private val T0: LocalDateTime = LocalDateTime(2026, 3, 4, 9, 0, 0)
    private fun at(minutesFromT0: Long): LocalDateTime =
        (T0.toInstant(TimeZone.UTC) + minutesFromT0.minutes).toLocalDateTime(TimeZone.UTC)

    private fun user(text: String, minute: Long = 0, synthetic: Boolean = false) =
        UIMessage.user(text).copy(createdAt = at(minute), isSynthetic = synthetic)

    private fun assistantMsg(text: String, minute: Long = 0) =
        UIMessage.assistant(text).copy(createdAt = at(minute))

    private fun assistantWithTool(text: String, toolCallId: String, toolName: String, minute: Long = 0) =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(text),
                UIMessagePart.Tool(
                    toolCallId = toolCallId, toolName = toolName, input = """{"q":"x"}""",
                    output = listOf(UIMessagePart.Text("""{"ok":true}""")),
                ),
            ),
            createdAt = at(minute),
        )

    private val chat3 = listOf(
        user("Hello", 0),
        assistantMsg("Hi there!", 1),
        user("How are you today?", 2),
    )

    private val chat7 = listOf(
        user("first question", 0),
        assistantMsg("first answer", 1),
        user("second question", 2),
        assistantMsg("second answer", 3),
        user("third question", 4),
        assistantMsg("third answer", 5),
        user("fourth question", 6),
    )

    private fun assistant(
        n: Int,
        systemPrompt: String = "You are a helpful assistant.",
        block: Assistant.() -> Assistant = { this },
    ) = Assistant(id = id(n), name = "A$n", systemPrompt = systemPrompt).block()

    private fun mode(
        n: Int, content: String, position: InjectionPosition, priority: Int = 0,
        role: MessageRole = MessageRole.USER, depth: Int = 4, enabled: Boolean = true,
    ) = PromptInjection.ModeInjection(
        id = id(1000 + n), name = "mode$n", enabled = enabled, priority = priority,
        position = position, content = content, injectDepth = depth, role = role,
    )

    private fun entry(
        n: Int, content: String, position: InjectionPosition,
        keywords: List<String> = emptyList(), useRegex: Boolean = false, caseSensitive: Boolean = false,
        scanDepth: Int = 4, constant: Boolean = false, priority: Int = 0,
        role: MessageRole = MessageRole.USER, depth: Int = 4,
    ) = PromptInjection.RegexInjection(
        id = id(2000 + n), name = "entry$n", priority = priority, position = position, content = content,
        injectDepth = depth, role = role, keywords = keywords, useRegex = useRegex,
        caseSensitive = caseSensitive, scanDepth = scanDepth, constantActive = constant,
    )

    private fun book(n: Int, vararg entries: PromptInjection.RegexInjection, enabled: Boolean = true) =
        Lorebook(id = id(3000 + n), name = "book$n", enabled = enabled, entries = entries.toList())

    private fun withInjections(modes: List<PromptInjection.ModeInjection> = emptyList(), books: List<Lorebook> = emptyList()) =
        Injections(modes, books)

    private fun tool(name: String, prompt: String, parallel: Boolean = false) = Tool(
        name = name,
        description = "tool $name",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
        systemPrompt = { _, _ -> prompt },
        execute = { listOf(UIMessagePart.Text("ok")) },
        concurrency = if (parallel) ToolConcurrency.Parallel else ToolConcurrency.Exclusive,
    )

    fun cases(): List<LegacyCase> = buildList {
        add(LegacyCase("plain", "单段系统提示词 + 三条历史", assistant(1), chat3))
        add(LegacyCase("no-system-prompt", "系统提示词为空：不发系统消息", assistant(2, systemPrompt = ""), chat3))
        add(LegacyCase("blank-system-prompt", "系统提示词只有空白：不发系统消息", assistant(3, systemPrompt = "  \n "), chat3))

        add(LegacyCase(
            "sections-compose", "分块：开启且非空白的块按序、块之间空一行；标题不发送",
            assistant(4, systemPrompt = "stale whole prompt") {
                copy(
                    systemPromptMode = SystemPromptMode.SECTIONS,
                    promptSections = listOf(
                        SystemPromptSection(id(41), "Persona", "You are Seraphina."),
                        SystemPromptSection(id(42), "Off", "NOT SENT", enabled = false),
                        SystemPromptSection(id(43), "Blank", "   "),
                        SystemPromptSection(id(44), "Style", "Answer in short sentences."),
                    ),
                )
            },
            chat3,
        ))
        add(LegacyCase(
            "conversation-prompt-allowed", "助手允许对话重写：会话提示词整个替换助手提示词",
            assistant(5) { copy(allowConversationSystemPrompt = true) }, chat3,
            conversationSystemPrompt = "Conversation-level prompt.",
        ))
        add(LegacyCase(
            "conversation-prompt-not-allowed", "助手不允许对话重写：会话提示词被忽略",
            assistant(6), chat3, conversationSystemPrompt = "Ignored conversation prompt.",
        ))
        add(LegacyCase(
            "conversation-prompt-blank", "允许重写但会话提示词是空白：回到助手提示词",
            assistant(7) { copy(allowConversationSystemPrompt = true) }, chat3, conversationSystemPrompt = "   ",
        ))

        add(LegacyCase(
            "memory", "记忆开启：系统消息后追加 <memory_system>（profile 全文 + 其余清单）",
            assistant(8) { copy(enableMemory = true) }, chat3,
            memories = listOf(
                MemoryFile("/profile.md", "# Profile\nThe user is Alice. She likes tea.", 1L),
                MemoryFile("/preferences.md", "# Preferences\nShort answers.", 2L),
                MemoryFile("/notes/trip.md", "---\ndescription: Trip plan\n---\nGo to Kyoto in April.", 3L),
            ),
        ))
        add(LegacyCase(
            "memory-empty", "记忆开启但没有任何文件", assistant(9) { copy(enableMemory = true) }, chat3,
            memories = emptyList(),
        ))
        add(LegacyCase(
            "memory-disabled", "记忆没开：即使传了文件也不拼", assistant(10), chat3,
            memories = listOf(MemoryFile("/profile.md", "should not appear", 1L)),
        ))

        add(LegacyCase(
            "tools-prompts", "每个工具的 systemPrompt 逐个换行追加在系统提示词后",
            assistant(11), chat3,
            tools = listOf(tool("search_web", "<search>Use search_web for fresh facts.</search>"), tool("calc", "")),
        ))
        add(LegacyCase(
            "tools-parallel-prompt", "两个以上可并行工具：追加并行调用提示",
            assistant(12), chat3,
            tools = listOf(tool("read_a", "<a/>", parallel = true), tool("read_b", "<b/>", parallel = true)),
        ))
        add(LegacyCase(
            "tools-parallel-off", "并行总开关关闭：不追加并行提示", assistant(13), chat3,
            configure = { it.copy(toolExecution = ToolExecutionConfig(parallel = false)) },
            tools = listOf(tool("read_a", "<a/>", parallel = true), tool("read_b", "<b/>", parallel = true)),
        ))
        add(LegacyCase(
            "memory-and-tools", "系统提示词 + 记忆 + 工具提示的拼接顺序",
            assistant(14) { copy(enableMemory = true) }, chat3,
            memories = listOf(MemoryFile("/profile.md", "# Profile\nAlice.", 1L)),
            tools = listOf(tool("search_web", "<search>go</search>")),
        ))

        add(LegacyCase(
            "placeholders", "占位符：{key}/{{key}}、大小写不敏感、未知键原样；系统消息与对话文字都会替换",
            assistant(15, systemPrompt = "Model {model_id} ({MODEL_NAME}). Today {cur_date}. User {user} / {{nickname}} / {char}. Unknown {foo}. Battery {battery_level}%. {system_version} on {device_info}. {locale} {timezone}"),
            listOf(user("I am {user}; talk to {{char}} on {Cur_Date}."), assistantMsg("{model_id} here"), user("ok {nickname}")),
        ))

        val allPositions = listOf(
            mode(1, "BEFORE-LOW", InjectionPosition.BEFORE_SYSTEM_PROMPT, priority = 1),
            mode(2, "BEFORE-HIGH", InjectionPosition.BEFORE_SYSTEM_PROMPT, priority = 9),
            mode(3, "AFTER-A", InjectionPosition.AFTER_SYSTEM_PROMPT, priority = 5),
            mode(4, "AFTER-B", InjectionPosition.AFTER_SYSTEM_PROMPT, priority = 5),
            mode(5, "TOP-USER", InjectionPosition.TOP_OF_CHAT),
            mode(6, "TOP-ASSISTANT", InjectionPosition.TOP_OF_CHAT, role = MessageRole.ASSISTANT),
            mode(7, "BOTTOM-USER", InjectionPosition.BOTTOM_OF_CHAT),
            mode(8, "DEPTH1-USER", InjectionPosition.AT_DEPTH, depth = 1),
            mode(9, "DEPTH1-ASSISTANT", InjectionPosition.AT_DEPTH, depth = 1, role = MessageRole.ASSISTANT),
            mode(10, "DEPTH3-USER", InjectionPosition.AT_DEPTH, depth = 3),
            mode(11, "DEPTH99-USER", InjectionPosition.AT_DEPTH, depth = 99),
            mode(12, "DISABLED", InjectionPosition.AFTER_SYSTEM_PROMPT, enabled = false),
        )
        add(LegacyCase(
            "mode-injections-all-positions", "五种位置：优先级降序、同位置同角色合并、深度 1/3/越界、禁用的不发",
            assistant(16) { copy(modeInjectionIds = (1..12).map { id(1000 + it) }.toSet()) },
            chat7, injections = withInjections(modes = allPositions),
        ))
        add(LegacyCase(
            "mode-injection-no-system", "没有系统消息时 BEFORE/AFTER 自己新建一条系统消息",
            assistant(17, systemPrompt = "") {
                copy(modeInjectionIds = setOf(id(1001), id(1003)))
            },
            chat3, injections = withInjections(modes = listOf(
                mode(1, "BEFORE-ONLY", InjectionPosition.BEFORE_SYSTEM_PROMPT),
                mode(3, "AFTER-ONLY", InjectionPosition.AFTER_SYSTEM_PROMPT),
            )),
        ))
        add(LegacyCase(
            "mode-injection-unbound", "助手没绑定的模式注入不发", assistant(18), chat3,
            injections = withInjections(modes = listOf(mode(1, "NOT BOUND", InjectionPosition.AFTER_SYSTEM_PROMPT))),
        ))

        add(LegacyCase(
            "lorebook-triggers", "世界书：关键词（大小写）、正则、扫描深度、常驻、未触发、禁用的书",
            assistant(19) { copy(lorebookIds = setOf(id(3001), id(3002))) },
            listOf(
                user("Tell me about the Dragon in the castle.", 0),
                assistantMsg("The dragon sleeps.", 1),
                user("What about a knight named Bob-42?", 2),
            ),
            injections = withInjections(books = listOf(
                book(1,
                    entry(1, "LORE-dragon-insensitive", InjectionPosition.AFTER_SYSTEM_PROMPT, keywords = listOf("dragon")),
                    entry(2, "LORE-DRAGON-sensitive-miss", InjectionPosition.AFTER_SYSTEM_PROMPT, keywords = listOf("DRAGON"), caseSensitive = true),
                    entry(3, "LORE-Dragon-sensitive-hit", InjectionPosition.AFTER_SYSTEM_PROMPT, keywords = listOf("Dragon"), caseSensitive = true),
                    entry(4, "LORE-regex-hit", InjectionPosition.BOTTOM_OF_CHAT, keywords = listOf("Bob-\\d+"), useRegex = true),
                    entry(5, "LORE-regex-miss", InjectionPosition.BOTTOM_OF_CHAT, keywords = listOf("^castle$"), useRegex = true),
                    entry(6, "LORE-scan1-miss", InjectionPosition.AFTER_SYSTEM_PROMPT, keywords = listOf("castle"), scanDepth = 1),
                    entry(7, "LORE-scan3-hit", InjectionPosition.AFTER_SYSTEM_PROMPT, keywords = listOf("castle"), scanDepth = 3),
                    entry(8, "LORE-constant", InjectionPosition.TOP_OF_CHAT, constant = true),
                    entry(9, "LORE-depth-assistant", InjectionPosition.AT_DEPTH, keywords = listOf("knight"), depth = 1, role = MessageRole.ASSISTANT),
                    entry(10, "LORE-no-keywords-miss", InjectionPosition.AFTER_SYSTEM_PROMPT),
                ),
                book(2, entry(11, "LORE-disabled-book", InjectionPosition.AFTER_SYSTEM_PROMPT, constant = true), enabled = false),
            )),
        ))
        add(LegacyCase(
            "lorebook-priority-merge", "世界书与模式注入同位置：优先级降序后合并",
            assistant(20) { copy(lorebookIds = setOf(id(3001)), modeInjectionIds = setOf(id(1001))) },
            chat3, injections = withInjections(
                modes = listOf(mode(1, "MODE-PRIO-1", InjectionPosition.AFTER_SYSTEM_PROMPT, priority = 1)),
                books = listOf(book(1, entry(1, "LORE-PRIO-7", InjectionPosition.AFTER_SYSTEM_PROMPT, constant = true, priority = 7))),
            ),
        ))

        add(LegacyCase(
            "conversation-bindings-on", "助手允许对话绑定：用会话绑定的注入，助手自己绑定的被忽略",
            assistant(21) { copy(allowConversationPromptInjection = true, modeInjectionIds = setOf(id(1001)), lorebookIds = setOf(id(3001))) },
            chat3,
            injections = withInjections(
                modes = listOf(
                    mode(1, "ASSISTANT-BOUND", InjectionPosition.AFTER_SYSTEM_PROMPT),
                    mode(2, "CONVERSATION-BOUND", InjectionPosition.AFTER_SYSTEM_PROMPT),
                ),
                books = listOf(
                    book(1, entry(1, "LORE-ASSISTANT-BOUND", InjectionPosition.AFTER_SYSTEM_PROMPT, constant = true)),
                    book(2, entry(2, "LORE-CONVERSATION-BOUND", InjectionPosition.AFTER_SYSTEM_PROMPT, constant = true)),
                ),
            ),
            conversationModeInjectionIds = setOf(id(1002)), conversationLorebookIds = setOf(id(3002)),
        ))
        add(LegacyCase(
            "conversation-bindings-off", "助手不允许对话绑定：会话里的绑定被忽略",
            assistant(22) { copy(modeInjectionIds = setOf(id(1001))) }, chat3,
            injections = withInjections(modes = listOf(
                mode(1, "ASSISTANT-BOUND", InjectionPosition.AFTER_SYSTEM_PROMPT),
                mode(2, "CONVERSATION-BOUND", InjectionPosition.AFTER_SYSTEM_PROMPT),
            )),
            conversationModeInjectionIds = setOf(id(1002)),
        ))

        val withTools = listOf(
            user("look this up", 0),
            assistantWithTool("searching", "call-1", "search_web", 1),
            user("and this", 2),
            assistantWithTool("searching again", "call-2", "search_web", 3),
            user("final", 4),
        )
        add(LegacyCase(
            "injection-around-tool-history", "混合位置 + 带工具调用的历史",
            assistant(23) { copy(modeInjectionIds = setOf(id(1001), id(1002), id(1003), id(1004))) },
            withTools, injections = withInjections(modes = listOf(
                mode(1, "TOP", InjectionPosition.TOP_OF_CHAT),
                mode(2, "BOTTOM", InjectionPosition.BOTTOM_OF_CHAT),
                mode(3, "DEPTH2", InjectionPosition.AT_DEPTH, depth = 2),
                mode(4, "DEPTH4", InjectionPosition.AT_DEPTH, depth = 4, role = MessageRole.ASSISTANT),
            )),
        ))
        val toolPairs = listOf(
            user("one", 0), assistantWithTool("calling one", "c1", "search_web", 1),
            user("two", 2), assistantWithTool("calling two", "c2", "search_web", 3),
            user("three", 4),
        )
        add(LegacyCase(
            "injection-safe-index-depth",
            "AT_DEPTH 的目标位置落在 USER→带工具的 ASSISTANT 之间时向前挪到该 USER 之前（findSafeInsertIndex）：深度 2 与 4",
            assistant(33) { copy(modeInjectionIds = setOf(id(1001), id(1002))) },
            toolPairs, injections = withInjections(modes = listOf(
                mode(1, "DEPTH2", InjectionPosition.AT_DEPTH, depth = 2),
                mode(2, "DEPTH4", InjectionPosition.AT_DEPTH, depth = 4, role = MessageRole.ASSISTANT),
            )),
        ))
        add(LegacyCase(
            "injection-safe-index-bottom",
            "BOTTOM_OF_CHAT 的目标是最后一条（带工具的 ASSISTANT）且前一条是 USER：挪到该 USER 之前",
            assistant(34) { copy(modeInjectionIds = setOf(id(1001))) },
            listOf(user("one", 0), assistantWithTool("calling one", "c1", "search_web", 1)),
            injections = withInjections(modes = listOf(mode(1, "BOTTOM", InjectionPosition.BOTTOM_OF_CHAT))),
        ))
        add(LegacyCase(
            "history-with-tool-and-reasoning", "历史里的工具调用/结果与思考原样传给 provider",
            assistant(24),
            listOf(
                user("compute", 0),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning("thinking about it"),
                        UIMessagePart.Text("let me call a tool"),
                        UIMessagePart.Tool("call-9", "calc", """{"expr":"1+1"}""", listOf(UIMessagePart.Text("2"))),
                    ),
                    createdAt = at(1),
                ),
                user("thanks", 2),
            ),
        ))

        add(LegacyCase(
            "time-reminder", "时间提醒：第一条用户消息、间隔超阈值（分/小时/天）、阈值内不提醒、合成用户消息不算",
            assistant(25) { copy(enableTimeReminder = true, timeReminderIntervalMinutes = 30) },
            listOf(
                user("morning", 0),
                assistantMsg("hi", 1),
                user("five minutes later", 5),
                assistantMsg("sure", 6),
                user("synthetic note", 100, synthetic = true),
                user("two hours later", 150),
                assistantMsg("ok", 151),
                user("next day", 150 + 26 * 60),
                user("three days on", 150 + 26 * 60 + 3 * 24 * 60),
            ),
        ))
        add(LegacyCase(
            "time-reminder-off", "时间提醒没开：不注入", assistant(26),
            listOf(user("a", 0), user("b", 1000)),
        ))

        add(LegacyCase(
            "message-template", "Pebble 消息模板：message/role/time/date；合成消息（时间提醒）不套模板",
            assistant(27) {
                copy(
                    messageTemplate = "[{{ role }} {{ date }} {{ time }}] {{ message }}",
                    enableTimeReminder = true, timeReminderIntervalMinutes = 30,
                )
            },
            listOf(user("hello", 0), assistantMsg("hi", 1), user("later", 120)),
        ))
        add(LegacyCase(
            "message-template-default", "默认模板 {{ message }} 对文字无影响，非文字 part 不动", assistant(28),
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("see image"), UIMessagePart.Image("https://example.com/a.png")), createdAt = at(0))),
        ))

        add(LegacyCase(
            "context-limit", "contextMessageLimit：阶梯式截断（保留条数落在 [limit×比例, limit)，起点对齐，见 limitContext）；系统消息不计", assistant(29) { copy(contextMessageLimit = 3) }, chat7,
        ))

        val snapshotted = user("first message").copy(
            parts = listOf(UIMessagePart.Text("first message")).withWorkspaceFileSnapshot("<workspace_files>\nsrc/\nREADME.md (12 B)\n</workspace_files>"),
        )
        add(LegacyCase(
            "workspace-file-snapshot", "首条消息冻结的工作区文件列表：作为一段文字接在该用户消息末尾",
            assistant(30), listOf(snapshotted, assistantMsg("noted", 1), user("second", 2)),
        ))
        add(LegacyCase(
            "attachments-wrapper", "附件包裹格式：图片带工作区路径、文档的 <UploadFile>（文档内容在 JVM 单测里是错误文本，见类注释）",
            assistant(31),
            listOf(UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("what is in these?"),
                    UIMessagePart.Image("https://example.com/a.png", metadata = buildJsonObject { put(WORKSPACE_PATH_METADATA_KEY, "/workspace/a.png") }),
                    UIMessagePart.Document("file:///nonexistent/notes.txt", "notes.txt", metadata = buildJsonObject { put(WORKSPACE_PATH_METADATA_KEY, "/workspace/notes.txt") }),
                ),
                createdAt = at(0),
            )),
        ))

        add(LegacyCase(
            "kitchen-sink", "全部叠加：分块提示词 + 记忆 + 工具 + 模式注入 + 世界书 + 占位符 + 时间提醒 + 模板 + 上下文截断",
            assistant(32) {
                copy(
                    systemPromptMode = SystemPromptMode.SECTIONS,
                    promptSections = listOf(
                        SystemPromptSection(id(321), "A", "You are {char} talking to {user} on {model_id}."),
                        SystemPromptSection(id(322), "B", "Be brief."),
                    ),
                    enableMemory = true, enableTimeReminder = true, timeReminderIntervalMinutes = 30,
                    messageTemplate = "<{{ role }}> {{ message }}", contextMessageLimit = 5,
                    modeInjectionIds = setOf(id(1001), id(1007)), lorebookIds = setOf(id(3001)),
                )
            },
            listOf(
                user("old one", 0), assistantMsg("old reply", 1),
                user("tell me about the dragon", 200), assistantMsg("it sleeps", 201),
                assistantWithTool("checking", "call-5", "search_web", 202),
                user("thanks {user}", 500),
            ),
            injections = withInjections(
                modes = listOf(
                    mode(1, "MODE-AFTER", InjectionPosition.AFTER_SYSTEM_PROMPT),
                    mode(7, "MODE-BOTTOM", InjectionPosition.BOTTOM_OF_CHAT),
                ),
                books = listOf(book(1, entry(1, "LORE-dragon", InjectionPosition.AT_DEPTH, keywords = listOf("dragon"), depth = 2, scanDepth = 8))),
            ),
            memories = listOf(MemoryFile("/profile.md", "# Profile\nLikes dragons.", 1L)),
            tools = listOf(tool("search_web", "<search>go</search>"), tool("read_a", "<a/>", parallel = true), tool("read_b", "<b/>", parallel = true)),
        ))
    }

    // ---------------------------------------------------------------- 运行

    /**
     * 以 UTC / en-US 跑，返回该用例发给 provider 的请求（序列化后的稳定形态）。
     * 夹具是 2.32.0 的旧形状：助手、模式注入、世界书经设置结构迁移转成现在的模型，再走现在的请求管线。
     */
    suspend fun run(case: LegacyCase, viaBackup: Boolean = false): JsonObject {
        val root = Files.createTempDirectory("legacy-baseline-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtime = TavernRuntime()
        try {
            val model = testChatModel()
            val migrated = if (viaBackup) migrateViaBackup(case) else migrate(case)
            val configure = { s: Settings ->
                case.configure(s).let {
                    it.copy(
                        displaySetting = it.displaySetting.copy(userNickname = NICKNAME),
                        promptLibrary = migrated.library,
                        lorebooks = migrated.lorebooks,
                        tavern = migrated.tavern ?: it.tavern,
                    )
                }
            }
            val settings = configure(testSettings(model))

            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
            val store = SettingsStore.forTesting(
                dataStore, MutationRuntime(File(root, "runtime"), File(root, "restore.marker")), scope,
            )
            store.update { configure(it).copy(assistants = listOf(migrated.assistant) + it.assistants) }
            withTimeout(10_000) { store.settingsFlow.first { s -> s.assistants.any { it.id == migrated.assistant.id } } }

            val engine = PebbleEngine.Builder()
                .loader(AssistantTemplateLoader(store))
                .defaultLocale(Locale.getDefault())
                .autoEscaping(false)
                .build()
            // 与 ChatService.generationInputTransformers 同一顺序（workspaceReminder 与创造助手的转换器见类注释）
            val chain: List<InputMessageTransformer> = listOf(
                TimeReminderTransformer,
                DocumentAsPromptTransformer,
                WorkspaceFileSnapshotTransformer,
                OcrTransformer,
                TemplateTransformer(engine, store),
            )

            val provider = ScriptedProvider(attempts = ArrayDeque(listOf<ScriptedAttempt>(textStepScript("ok"))))
            val context = FakeContext(File(root, "files").apply { mkdirs() })
            val loop = testGenerationLoop(provider, context)

            startKoin { modules(module { single { store } }) }
            try {
                withPlatformPlaceholdersPinned {
                    val turn = TurnPreparation(runtime).prepare(
                        TurnRequest(
                            assistant = migrated.assistant,
                            settings = settings,
                            model = model,
                            conversationId = null,
                            metadata = JsonObject(emptyMap()),
                            conversationLibraryEntryIds = case.conversationModeInjectionIds,
                            conversationLorebookIds = case.conversationLorebookIds,
                            customSystemPrompt = case.conversationSystemPrompt,
                            messages = case.messages,
                            generationType = GenerationType.NORMAL,
                            userName = RikkihubMacros.userName(settings),
                            charName = RikkihubMacros.charName(migrated.assistant),
                            ownMacros = RikkihubMacros.resolvers(context, settings, model, migrated.assistant),
                        )
                    )
                    loop.generateText(
                        settings = settings,
                        model = model,
                        messages = case.messages,
                        inputTransformers = chain,
                        assistant = migrated.assistant,
                        memories = case.memories,
                        tools = case.tools,
                        maxSteps = 1,
                        conversationSystemPrompt = case.conversationSystemPrompt,
                        turn = turn,
                    ).toList()
                }
            } finally {
                stopKoin()
            }

            check(provider.streamMessages.size == 1) { "${case.name}: expected one provider request, got ${provider.streamMessages.size}" }
            return buildJsonObject {
                put("name", case.name)
                put("note", case.note)
                put("messages", serializeMessages(provider.streamMessages.single()))
                put("params", serializeParams(provider.streamParams.single()))
            }
        } finally {
            runtime.close()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private class Migrated(
        val assistant: me.rerere.rikkihub.data.model.Assistant,
        val library: List<me.rerere.tavern.prompt.PromptEntry>,
        val lorebooks: List<me.rerere.rikkihub.data.model.Lorebook>,
        /** 只有走备份路径时有：迁移补出来的全局酒馆设置。 */
        val tavern: me.rerere.rikkihub.data.model.TavernSettings? = null,
    )

    /** 旧夹具 → 旧形状 JSON → V5、V6、V7 迁移 → 现在的模型。和老用户升级到这一版时走的是同一条路（夹具里没有角色会话）。 */
    private fun migrate(case: LegacyCase): Migrated {
        val assistantJson = JsonInstant.encodeToJsonElement(Assistant.serializer(), case.assistant).jsonObject
        val v5 = kotlinx.serialization.json.JsonArray(listOf(TavernSettingsMigration.migrateAssistant(assistantJson)))
        val v7 = TavernSettingsMigration.addTavernMode(TavernSettingsMigration.addCardMarkers(v5), tavern = null, tavernAssistantIds = emptySet())
        val assistant = JsonInstant.decodeFromJsonElement(
            me.rerere.rikkihub.data.model.Assistant.serializer(),
            v7.single(),
        )
        val modes = JsonInstant.encodeToJsonElement(ListSerializer(PromptInjection.ModeInjection.serializer()), case.injections.modes).jsonArray
        val books = JsonInstant.encodeToJsonElement(ListSerializer(Lorebook.serializer()), case.injections.books).jsonArray
        val library = TavernSettingsMigration.migrateModeInjections(modes).map {
            me.rerere.tavern.prompt.PromptEntry.fromJson(it.jsonObject)
        }
        val lorebooks = JsonInstant.decodeFromJsonElement(
            ListSerializer(me.rerere.rikkihub.data.model.Lorebook.serializer()),
            TavernSettingsMigration.migrateLorebooks(books),
        )
        return Migrated(assistant, library, lorebooks)
    }

    /**
     * 同一份旧夹具，但走备份恢复的路：整份 2.32.0 的 settings.json → `SettingsJsonMigrator`（暂存时）→ 解码 → 编码落盘
     * → 重启时再迁移一遍（`applyPendingRestore`）→ 解码。第二遍必须和第一遍落盘的内容一致（迁移对整份设置幂等）。
     */
    private fun migrateViaBackup(case: LegacyCase): Migrated {
        val assistantJson = JsonInstant.encodeToJsonElement(Assistant.serializer(), case.assistant).jsonObject
        val modes = JsonInstant.encodeToJsonElement(ListSerializer(PromptInjection.ModeInjection.serializer()), case.injections.modes).jsonArray
        val books = JsonInstant.encodeToJsonElement(ListSerializer(Lorebook.serializer()), case.injections.books).jsonArray
        val backup = JsonObject(mapOf(
            "assistants" to JsonArray(listOf(assistantJson)), "modeInjections" to modes, "lorebooks" to books,
        )).toString()
        val staged = JsonInstant.decodeFromString<Settings>(me.rerere.rikkihub.data.datastore.migration.SettingsJsonMigrator.migrate(backup))
        val persisted = JsonInstant.encodeToString(Settings.serializer(), staged)
        val restored = JsonInstant.decodeFromString<Settings>(me.rerere.rikkihub.data.datastore.migration.SettingsJsonMigrator.migrate(persisted))
        check(JsonInstant.encodeToString(Settings.serializer(), restored) == persisted) { "${case.name}: migrating a migrated backup changed it" }
        return Migrated(restored.assistants.single(), restored.promptLibrary, restored.lorebooks, restored.tavern)
    }

    // ---------------------------------------------------------------- 平台相关占位符

    /**
     * `PlaceholderTransformer` 对**所有**占位符先求值再替换，其中电量、系统版本、设备要用 Android 平台类，
     * 在 JVM 单测里没有。这里只把这三个解析器换成固定值（替换逻辑本身用真的），结束后还原。
     */
    private suspend fun withPlatformPlaceholdersPinned(block: suspend () -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val map = me.rerere.rikkihub.data.ai.transformers.DefaultPlaceholderProvider.placeholders
            as MutableMap<String, me.rerere.rikkihub.data.ai.transformers.PlaceholderInfo>
        val pinned = mapOf("battery_level" to "87", "system_version" to "Android SDK v35 (15)", "device_info" to "Acme Phone")
        val originals = pinned.keys.associateWith { map.getValue(it) }
        pinned.forEach { (key, value) -> map[key] = originals.getValue(key).copy(resolver = { value }) }
        try { block() } finally { originals.forEach { (key, info) -> map[key] = info } }
    }

    // ---------------------------------------------------------------- 序列化

    private val today: String
        get() = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault()).format(java.time.LocalDate.now())

    // JDK 20+ 的 CLDR 在 "9:00:00 AM" 前用 U+202F（窄不换行空格），旧版是普通空格：归一化，免得换 JDK 假失败
    private fun stable(text: String): String = text.replace(today, "<TODAY>").replace('\u202f', ' ')

    private fun serializeMessages(messages: List<UIMessage>): JsonArray = buildJsonArray {
        messages.forEach { m ->
            add(buildJsonObject {
                put("role", m.role.name.lowercase())
                put("synthetic", m.isSynthetic)
                put("parts", buildJsonArray { m.parts.forEach { add(serializePart(it)) } })
            })
        }
    }

    private fun serializePart(part: UIMessagePart): JsonElement = buildJsonObject {
        when (part) {
            is UIMessagePart.Text -> { put("type", "text"); put("text", stable(part.text)) }
            is UIMessagePart.Image -> { put("type", "image"); put("url", part.url) }
            is UIMessagePart.Document -> { put("type", "document"); put("fileName", part.fileName); put("mime", part.mime) }
            is UIMessagePart.Reasoning -> { put("type", "reasoning"); put("reasoning", stable(part.reasoning)) }
            is UIMessagePart.Tool -> {
                put("type", "tool"); put("id", part.toolCallId); put("name", part.toolName); put("input", part.input)
                put("output", buildJsonArray { part.output.forEach { add(serializePart(it)) } })
            }
            else -> put("type", part::class.simpleName ?: "unknown")
        }
    }

    private fun serializeParams(p: TextGenerationParams): JsonElement = buildJsonObject {
        put("model", p.model.modelId)
        put("temperature", p.temperature?.let { JsonPrimitive(it) } ?: JsonNull)
        put("topP", p.topP?.let { JsonPrimitive(it) } ?: JsonNull)
        put("maxTokens", p.maxTokens?.let { JsonPrimitive(it) } ?: JsonNull)
        put("reasoningLevel", p.reasoningLevel.name)
        put("tools", buildJsonArray { p.tools.forEach { add(JsonPrimitive(it.name)) } })
    }
}
