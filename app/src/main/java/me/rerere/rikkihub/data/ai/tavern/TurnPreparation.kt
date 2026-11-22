package me.rerere.rikkihub.data.ai.tavern

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.libraryEnabled
import me.rerere.rikkihub.data.model.plainLayout
import me.rerere.rikkihub.data.model.regexScripts
import me.rerere.rikkihub.data.model.withTextEntries
import me.rerere.tavern.core.ChatLine
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.core.JsRegexFactory
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.core.SystemTavernClock
import me.rerere.tavern.core.SystemTavernRandom
import me.rerere.tavern.core.TavernClock
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.core.WorldInfoPromptResult
import me.rerere.tavern.card.DepthPrompt
import me.rerere.tavern.macro.MacroEnvCharacter
import me.rerere.tavern.macro.MacroEnvNames
import me.rerere.tavern.prompt.AssembledItem
import me.rerere.tavern.prompt.AssemblyInput
import me.rerere.tavern.prompt.AssemblyOptions
import me.rerere.tavern.prompt.HistoryItem
import me.rerere.tavern.prompt.PromptAssembler
import me.rerere.tavern.prompt.PromptLayout
import me.rerere.tavern.regex.RegexParams
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.token.TokenCounter
import me.rerere.tavern.variable.VariableScope
import me.rerere.tavern.variable.Variables
import me.rerere.tavern.worldinfo.ActivatedWorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoBook
import me.rerere.tavern.worldinfo.WorldInfoBookRef
import me.rerere.tavern.worldinfo.WorldInfoBooks
import me.rerere.tavern.worldinfo.WorldInfoCharacterContext
import me.rerere.tavern.worldinfo.WorldInfoGlobalScanData
import me.rerere.tavern.worldinfo.WorldInfoEngine
import me.rerere.tavern.worldinfo.WorldInfoHost
import kotlin.uuid.Uuid

/** 一次生成需要的全部酒馆输入。 */
class TurnRequest(
    val assistant: Assistant,
    val settings: Settings,
    val model: Model,
    val conversationId: Uuid?,
    /** `conversations.tavern_metadata`。 */
    val metadata: JsonObject,
    val conversationLibraryEntryIds: Set<Uuid>,
    val conversationLorebookIds: Set<Uuid>,
    /** 会话单独的系统提示词（助手允许覆盖时整个替换提示词文本条目）。 */
    val customSystemPrompt: String?,
    /** 本次生成之前的聊天（当前分支，最旧在前）。世界书扫描和预演都用它。 */
    val messages: List<UIMessage>,
    val generationType: GenerationType = GenerationType.NORMAL,
    /** 预演（压缩摘要、token 估算等）：不推进计时器、不掷概率骰，结果里的状态不该被保存。 */
    val dryRun: Boolean = false,
    val userName: String,
    val charName: String,
    /** 会话绑定的角色（没有角色的对话为 null）：卡字段、角色书、卡内正则都来自它。 */
    val character: ActiveCharacter? = null,
    /** rikkihub 自有宏（cur_date、model_id 等 9 个）。 */
    val ownMacros: Map<String, () -> String> = emptyMap(),
    val clock: TavernClock = SystemTavernClock,
    val random: TavernRandom = SystemTavernRandom,
)

/** 本回合里世界书激活的一条，供消息详情里的「激活记录」使用。 */
data class WorldInfoActivation(val bookId: String, val bookName: String, val entry: ActivatedWorldInfoEntry)

/**
 * 回合准备：每次生成开始时做一次，结果冻结。
 *
 * 做的事（顺序照上游 `Generate()`）：世界书扫描与激活（概率、计时器）→ 用同一套宏环境预演一次组装，
 * 让预设文本、作者注释、激活条目里的宏副作用只发生这一次 → 取出新的变量与计时状态交给调用方保存。
 * 之后 GenerationLoop 每个工具步骤、每次重试都只调用 [PreparedTurn.build]，同一段文本取第一次的求值结果。
 */
class TurnPreparation(private val runtime: TavernRuntime) {

    suspend fun prepare(request: TurnRequest): PreparedTurn {
        val assistant = request.assistant
        val settings = request.settings
        val state = TavernConversationState.from(request.metadata, settings.tavern.authorsNote)

        // 酒馆模式关：用精简布局，提示词库和手动选用的世界书照常生效，角色卡和作者注释不用
        val tavern = assistant.tavernMode
        val character = if (tavern) request.character else null
        val preset = effectivePreset(assistant, request.customSystemPrompt).let { if (tavern) it else it.plainLayout() }
        val options = preset.options
        val maxContext = minOf(options.contextSize, request.model.contextLength).coerceAtLeast(1)

        val local = VariableScope(state.variables)
        val global = VariableScope(settings.tavern.globalVariables)
        val chat = chatLines(request.messages, request.userName, request.charName)
        fun session(card: MacroEnvCharacter) = MacroSession(
            engine = runtime.macroEngine,
            names = MacroEnvNames.solo(user = request.userName, char = request.charName),
            variables = Variables(local, global),
            model = request.model.modelId,
            ownMacros = request.ownMacros,
            chat = chat,
            character = card,
            clock = request.clock,
            random = request.random,
            chatId = request.conversationId?.toString(),
            maxPromptTokens = maxContext - options.responseTokens,
            maxContextTokens = maxContext,
            maxResponseTokens = options.responseTokens,
        )

        val plain = session(MacroEnvCharacter())
        val fields = CardFields.compute(plain, character, settings)
        val card = character?.record?.card
        val macros = session(fields.macroEnv())

        // 正则不属于组装布局，酒馆模式关时照旧（和显示端的正则一致）
        val regexScripts = RegexScripts.of(settings, preset, request.character)

        // ---- 世界书 ----
        val lorebooks = selectedLorebooks(assistant, settings, request.conversationLorebookIds)
        val characterBooks = character?.books ?: emptyList()
        val books = WorldInfoBooks(
            global = lorebooks.map { WorldInfoBookRef(it.id.toString(), WorldInfoBook(entries = it.entries, name = it.name)) },
            character = characterBooks.map { WorldInfoBookRef(it.id.toString(), WorldInfoBook(entries = it.entries, name = it.name)) },
        )
        val worldInfoHost = object : WorldInfoHost {
            override fun substitute(text: String) = macros.evaluate(text)
            override suspend fun applyWorldInfoRegex(text: String, depth: Int?): String =
                runtime.regexEngine.getRegexedString(
                    regexScripts, text, RegexPlacement.WORLD_INFO, macros.regexHost,
                    RegexParams(isPrompt = true, depth = depth),
                )
            override val regexFactory: JsRegexFactory get() = runtime.regexFactory
            override fun countTokens(text: String) = TokenCounter.count(text)
            override val random: TavernRandom get() = request.random
        }
        val worldInfo = WorldInfoEngine(worldInfoHost).check(
            chat = chat,
            books = books,
            settings = settings.tavern.worldInfo.normalized(),
            maxContext = maxContext,
            timedEffects = state.timedEffects,
            generationType = request.generationType,
            dryRun = request.dryRun,
            globalScanData = WorldInfoGlobalScanData(
                personaDescription = fields.persona,
                characterDescription = fields.description,
                characterPersonality = fields.personality,
                characterDepthPrompt = fields.depthPrompt,
                scenario = fields.scenario,
                creatorNotes = fields.creatorNotes,
            ),
            character = WorldInfoCharacterContext(fileName = card?.let { request.charName }, tagIds = card?.tags),
        )
        macros.outlets = worldInfo.prompt.outletEntries
        val bookNames = (lorebooks + characterBooks).associate { it.id.toString() to it.name }
        val activations = worldInfo.activated.map { WorldInfoActivation(it.book, bookNames[it.book].orEmpty(), it) }

        // ---- 布局：助手预设 + 本回合选用的库条目 ----
        val library = selectedLibrary(assistant, settings, request.conversationLibraryEntryIds)
        val layout = PromptLayout(LibraryLayout.dropBlankText(LibraryLayout.merge(preset.layout().entries, library)))

        val host = FrozenPromptHost(
            evaluate = macros::evaluate,
            history = request.messages,
            exactHistoryTokens = options.contextSize < UNLIMITED_CONTEXT,
        )
        val base = AssemblyInput(
            layout = layout,
            options = options,
            generationType = request.generationType,
            charName = request.charName,
            userName = request.userName,
            description = fields.description,
            personality = fields.personality,
            scenario = fields.scenario,
            // 用户设定除了标记处，还会按它自己的位置（作者注释前后、聊天内）注入，酒馆模式关时一律不发
            persona = if (tavern) fields.persona else "",
            mesExamples = fields.mesExamples,
            charDepthPrompt = fields.depthPrompt,
            depthPromptDepth = card?.depthPrompt?.depth ?: DepthPrompt.DEFAULT_DEPTH,
            depthPromptRole = card?.depthPrompt?.role ?: PromptRole.SYSTEM,
            systemPromptOverride = fields.system.ifEmpty { null },
            jailbreakPromptOverride = fields.jailbreak.ifEmpty { null },
            worldInfo = worldInfo.prompt,
            authorsNote = if (tavern) state.authorsNote else null,
        )
        val turn = PreparedTurn(
            base = base,
            host = host,
            regexScripts = regexScripts,
            regexEngine = runtime.regexEngine,
            macros = macros,
            activations = activations,
            budgetOverflowed = worldInfo.budgetOverflowed,
            libraryIdentifiers = library.mapTo(HashSet()) { it.identifier },
            local = local,
            global = global,
            timedEffects = worldInfo.timedEffects,
            state = state,
        )
        // 预演一次组装：预设文本、作者注释、条目内容里的宏在这里求值一次（副作用落到变量上），之后全部取缓存
        turn.build(request.messages, rikkihubContext = "")
        return turn
    }

    private fun effectivePreset(assistant: Assistant, customSystemPrompt: String?) =
        if (assistant.allowConversationSystemPrompt && !customSystemPrompt.isNullOrBlank()) {
            // 会话提示词是升级前写的，里面可能还有单花括号的占位符：和迁移同一条规则改成双花括号
            assistant.promptPreset.withTextEntries(
                listOf(AssistantPresets.mainEntry(me.rerere.tavern.macro.LegacyPlaceholderMigration.migrate(customSystemPrompt)))
            )
        } else {
            assistant.promptPreset
        }

    private fun selectedLorebooks(assistant: Assistant, settings: Settings, conversationIds: Set<Uuid>): List<Lorebook> {
        val ids = if (assistant.allowConversationPromptInjection) conversationIds else assistant.lorebookIds
        return settings.lorebooks.filter { it.enabled && it.id in ids }
    }

    private fun selectedLibrary(assistant: Assistant, settings: Settings, conversationIds: Set<Uuid>) = run {
        val ids = (if (assistant.allowConversationPromptInjection) conversationIds else assistant.libraryEntryIds)
            .mapTo(HashSet()) { it.toString() }
        settings.promptLibrary.filter { it.libraryEnabled && it.identifier in ids }
    }

    companion object {
        /** 预设的上下文长度不小于它就视为不设限（迁移出来的助手是 100 万）：历史不逐条分词。 */
        const val UNLIMITED_CONTEXT = AssistantPresets.UNLIMITED_CONTEXT

        /** 世界书扫描与宏看到的聊天：只含有文字的真实消息（工具调用步骤和请求期合成的消息不算）。 */
        internal fun chatLines(messages: List<UIMessage>, userName: String, charName: String): List<ChatLine> =
            messages.mapNotNull { message ->
                if (message.isSynthetic || message.role == MessageRole.SYSTEM) return@mapNotNull null
                val text = message.toText()
                if (text.isBlank()) return@mapNotNull null
                val isUser = message.role == MessageRole.USER
                ChatLine(
                    name = if (isUser) userName else charName,
                    isUser = isUser,
                    isSystem = false,
                    text = text,
                    sendDateMillis = null,
                )
            }
    }
}

/**
 * 冻结后的回合。[build] 可以反复调用（每个工具步骤、每次重试一次），输出只取决于传入的历史和 rikkihub 自有段。
 */
class PreparedTurn internal constructor(
    private val base: AssemblyInput,
    private val host: FrozenPromptHost,
    internal val regexScripts: List<RegexScript>,
    private val regexEngine: me.rerere.tavern.regex.RegexEngine,
    internal val macros: MacroSession,
    /** 本回合世界书激活的条目（消息详情的「激活记录」）。 */
    val activations: List<WorldInfoActivation>,
    val budgetOverflowed: Boolean,
    internal val libraryIdentifiers: Set<String> = emptySet(),
    private val local: VariableScope,
    private val global: VariableScope,
    private val timedEffects: me.rerere.tavern.worldinfo.TimedEffectsState,
    private val state: TavernConversationState,
) {
    /** 世界书与变量在本回合之后的会话状态；预演（dryRun）的结果不要保存。 */
    fun newConversationState(): TavernConversationState = state.copy(variables = local.toJson(), timedEffects = timedEffects)

    /** 全局变量是否被本回合改过，以及改后的值（存回 `Settings.tavern.globalVariables`）。 */
    val globalVariablesChanged: Boolean get() = global.dirty
    fun newGlobalVariables(): JsonObject = global.toJson()

    /**
     * 预演一次组装但不合并系统消息（`squashSystemMessages = false`），保留每个 [AssembledItem.Generated.identifier]，
     * 供上下文用量分项准确区分系统提示词、世界书与酒馆注入。
     */
    internal suspend fun inspect(messages: List<UIMessage>, rikkihubContext: String): List<AssembledItem> {
        val prompted = applyPromptRegexes(messages)
        host.history = prompted
        val input = base.copy(
            options = base.options.copy(squashSystemMessages = false),
            history = prompted.map { message ->
                HistoryItem(
                    role = message.role.toPromptRole(),
                    name = null,
                    text = OPAQUE_PREFIX + message.toRawText(),
                )
            },
            rikkihubContext = rikkihubContext,
        )
        return PromptAssembler.assemble(input, host).items
    }

    /**
     * 把 [messages]（本次请求要发的聊天，工具步骤也在里面）组装成发给提供商的消息：
     * 系统块、世界书、库条目、深度注入、作者注释按布局落位，聊天消息原样保留。
     *
     * @param rikkihubContext rikkihub 自有段（记忆、工具说明），放在 `rikkihubContext` 标记处，不求宏
     */
    suspend fun build(messages: List<UIMessage>, rikkihubContext: String): List<UIMessage> {
        val prompted = applyPromptRegexes(messages)
        host.history = prompted
        val input = base.copy(
            history = prompted.map { message ->
                HistoryItem(
                    role = message.role.toPromptRole(),
                    name = null,
                    // 带前缀 = 原样使用；空文本（只有工具调用或图片）也要有内容，否则组装器会当空消息丢掉
                    text = OPAQUE_PREFIX + message.toRawText(),
                )
            },
            rikkihubContext = rikkihubContext,
        )
        val result = PromptAssembler.assemble(input, host)
        return result.items.map { item ->
            when (item) {
                is AssembledItem.History -> prompted[item.originalIndex]
                is AssembledItem.Generated -> when (item.role) {
                    PromptRole.SYSTEM -> UIMessage.system(item.content)
                    PromptRole.USER -> UIMessage.user(item.content)
                    PromptRole.ASSISTANT -> UIMessage.assistant(item.content)
                }.copy(isSynthetic = true)
            }
        }
    }

    /** 仅提示词的正则：只改发给模型的文本，按消息离末尾的距离（深度）过滤。 */
    private suspend fun applyPromptRegexes(messages: List<UIMessage>): List<UIMessage> {
        if (regexScripts.none { it.promptOnly && !it.disabled }) return messages
        val real = messages.indices.filter { !messages[it].isSynthetic }
        val depthOf = real.withIndex().associate { (position, index) -> index to real.size - 1 - position }
        return messages.mapIndexed { index, message ->
            val depth = depthOf[index] ?: return@mapIndexed message
            val placement = when (message.role) {
                MessageRole.USER -> RegexPlacement.USER_INPUT
                MessageRole.ASSISTANT -> RegexPlacement.AI_OUTPUT
                else -> return@mapIndexed message
            }
            var changed = false
            val parts = message.parts.map { part ->
                if (part is UIMessagePart.Text) {
                    val replaced = regexEngine.getRegexedString(
                        regexScripts, part.text, placement, macros.regexHost,
                        RegexParams(isPrompt = true, depth = depth),
                    )
                    if (replaced != part.text) { changed = true; part.copy(text = replaced) } else part
                } else part
            }
            if (changed) message.copy(parts = parts) else message
        }
    }

    private fun MessageRole.toPromptRole() = when (this) {
        MessageRole.SYSTEM -> PromptRole.SYSTEM
        MessageRole.USER -> PromptRole.USER
        MessageRole.ASSISTANT, MessageRole.TOOL -> PromptRole.ASSISTANT
    }
}

/** 脚本顺序：全局 → 预设（助手）。上游的角色作用域随 1B。 */
internal object RegexScripts {
    /** 顺序照酒馆：全局、角色自带（放行后）、预设（助手）。 */
    fun of(settings: Settings, preset: me.rerere.tavern.prompt.PromptPreset, character: ActiveCharacter? = null): List<RegexScript> =
        settings.tavern.regexScripts + (character?.regexScripts ?: emptyList()) + preset.regexScripts

    fun of(settings: Settings, assistant: Assistant?, character: ActiveCharacter? = null): List<RegexScript> =
        if (assistant == null) settings.tavern.regexScripts + (character?.regexScripts ?: emptyList())
        else of(settings, assistant.promptPreset, character)
}
