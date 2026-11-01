package me.rerere.rikkihub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkihub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkihub.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val systemPromptMode: SystemPromptMode = SystemPromptMode.WHOLE, // 编辑界面打开整块还是分块视图，不影响发送内容
    val promptSections: List<SystemPromptSection> = emptyList(),  // 非空时 systemPrompt = 开启的块按序合并的结果
    val temperature: Float? = null,
    val topP: Float? = null,
    // 上下文消息条数上限, 超出后阶梯式截断; 0 表示不限制
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false, // 使用全局共享记忆而非助手隔离记忆
    val autoConsolidateMemory: Boolean = true, // 对话停下来后在后台自动整理记忆（做梦）; 关闭后只能手动整理
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = emptyList(), // 2.8.0 起默认不开启任何本地工具（含时间信息）
    val enableWebSearch: Boolean = false, // 网络搜索开关(每个助手独立)
    val workspaceId: Uuid? = null,
    val background: String? = null, // 聊天页背景图地址(本地文件 URI 或网络 URL), 为 null 时无背景
    val backgroundOpacity: Float = 1.0f, // 背景图不透明度(0~1)
    val useGradientBackground: Boolean = false, // 开启后聊天页使用动态渐变背景
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val timeReminderIntervalMinutes: Int = 60,          // 时间提醒间隔（分钟，至少 1 分钟）
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowConversationPromptInjection: Boolean = false, // 允许对话单独绑定提示词注入
)

@Serializable
enum class SystemPromptMode {
    @SerialName("whole")
    WHOLE,

    @SerialName("sections")
    SECTIONS,
}

/** 系统提示词分块；[title] 只用于编辑界面标识，不发给模型。 */
@Serializable
data class SystemPromptSection(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
    val enabled: Boolean = true,
)

const val SYSTEM_PROMPT_SECTION_SEPARATOR = "\n\n"

/*
 * 整块与分块是同一份提示词的两种视图（2.13.0 起不再相互独立）：
 * - 没有分块时，[Assistant.systemPrompt] 就是提示词；
 * - 有分块时，[Assistant.systemPrompt] 恒等于开启且非空白的块按序合并的结果。改分块时整块跟着重算
 *   （[withPromptSections]），改整块时把改动落到对应分块（[withWholeSystemPrompt]）。
 * [Assistant.systemPromptMode] 只记住界面打开哪个视图。所有写入经 [reconcileSystemPrompt]，读取经
 * [normalizedSystemPrompt] 兼容 2.10～2.12 时两种模式各存一份的旧数据。
 */

/** 开启且非空白的块按序合并，块之间空一行；标题不发送。 */
fun composeSystemPromptSections(sections: List<SystemPromptSection>): String = sections
    .filter { it.enabled && it.content.isNotBlank() }
    .joinToString(SYSTEM_PROMPT_SECTION_SEPARATOR) { it.content }

/** 助手提示词本身（不含会话覆盖）：没有分块时取 [Assistant.systemPrompt]，有分块时为分块的合并结果。 */
fun Assistant.composedSystemPrompt(): String =
    if (promptSections.isEmpty()) systemPrompt else composeSystemPromptSections(promptSections)

/**
 * 请求实际使用的助手侧系统提示词（不含记忆/工具段）：会话覆盖允许且非空时整个替换；
 * 否则为 [composedSystemPrompt]。生成与缓存保活都只经这里取值。
 */
fun Assistant.effectiveSystemPrompt(conversationSystemPrompt: String?): String =
    if (allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
        conversationSystemPrompt
    } else {
        composedSystemPrompt()
    }

/** 切换编辑视图。切到分块且尚无分块时，把整块原文带成第一块，发给模型的内容不变。 */
fun Assistant.switchSystemPromptMode(mode: SystemPromptMode, seedTitle: String): Assistant {
    if (mode == systemPromptMode) return this
    val sections = if (mode == SystemPromptMode.SECTIONS && promptSections.isEmpty() && systemPrompt.isNotBlank()) {
        listOf(SystemPromptSection(title = seedTitle, content = systemPrompt))
    } else {
        promptSections
    }
    return copy(systemPromptMode = mode, promptSections = sections)
}

/** 换一组分块，整块跟着变成它们的合并结果（删光分块即提示词为空）。 */
fun Assistant.withPromptSections(sections: List<SystemPromptSection>): Assistant =
    copy(promptSections = sections, systemPrompt = composeSystemPromptSections(sections))

/** 整块视图里的一段文字，对应一个分块；整块文本 = 各段按 [SYSTEM_PROMPT_SECTION_SEPARATOR] 连接。 */
data class SystemPromptSegment(val sectionId: Uuid, val text: String)

/** 整块文本改动后的分段：[segments] 连接起来恰好是新文本，[cleared] 是被并入别段、内容应清空的分块。 */
data class SystemPromptRemap(val segments: List<SystemPromptSegment>, val cleared: Set<Uuid>)

/**
 * 整块视图的初始分段：开启且非空白的块各一段。一个都没有时，用第一个开启的块（没有就用 [fallback]
 * 作为新块的 id）承接输入。
 */
fun Assistant.systemPromptSegments(fallback: Uuid): List<SystemPromptSegment> {
    val visible = promptSections.filter { it.enabled && it.content.isNotBlank() }
    if (visible.isNotEmpty()) return visible.map { SystemPromptSegment(it.id, it.content) }
    return listOf(SystemPromptSegment(promptSections.firstOrNull { it.enabled }?.id ?: fallback, ""))
}

/**
 * 把整块文本从 `segments` 的连接结果改成 [newText]，再切回各段。只看改动的最小范围（公共前后缀之外）：
 * 没被改动碰到的分隔符原样保留，改动所在的段拿到改后的文字；改动吃掉了分隔符时，相邻几段并成一段——
 * 留给前面那段，前面那段被整段删掉时留给后面那段，其余的段记入 cleared。
 */
fun remapSystemPromptSegments(segments: List<SystemPromptSegment>, newText: String): SystemPromptRemap {
    require(segments.isNotEmpty()) { "at least one segment" }
    val separator = SYSTEM_PROMPT_SECTION_SEPARATOR
    val oldText = segments.joinToString(separator) { it.text }
    if (oldText == newText) return SystemPromptRemap(segments, emptySet())
    val common = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < common && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < common - prefix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
    val oldEnd = oldText.length - suffix
    val delta = newText.length - oldText.length

    val starts = IntArray(segments.size)
    for (i in 1 until segments.size) starts[i] = starts[i - 1] + segments[i - 1].text.length + separator.length
    val ends = IntArray(segments.size) { starts[it] + segments[it].text.length }
    // 第 k 个分隔符位于 [ends[k], ends[k] + 长度)；与改动范围有交集（含插在两个换行之间）就算被吃掉
    val survives = BooleanArray(segments.size - 1) { k ->
        val at = ends[k]
        !(prefix < at + separator.length && oldEnd > at)
    }

    val result = mutableListOf<SystemPromptSegment>()
    val cleared = mutableSetOf<Uuid>()
    var groupStart = 0
    var textStart = 0
    for (k in segments.indices) {
        if (k < segments.size - 1 && !survives[k]) continue
        // 存活的分隔符在改动前面位置不变，在后面则整体平移
        val textEnd = if (k == segments.size - 1) newText.length
        else if (ends[k] + separator.length <= prefix) ends[k] else ends[k] + delta
        val keeper = if (groupStart == k) groupStart else {
            val head = minOf(prefix, ends[groupStart]) - starts[groupStart]
            val tail = ends[k] - maxOf(oldEnd, starts[k])
            if (head <= 0 && tail > 0) k else groupStart
        }
        result += segments[keeper].copy(text = newText.substring(textStart, textEnd))
        for (m in groupStart..k) if (m != keeper) cleared += segments[m].sectionId
        textStart = textEnd + separator.length
        groupStart = k + 1
    }
    return SystemPromptRemap(result, cleared)
}

/** 把分段写回分块：[segments] 的文字覆盖对应块，[cleared] 的块清空，不存在的段 id 追加为新块；整块随之重算。 */
fun Assistant.withSystemPromptSegments(segments: List<SystemPromptSegment>, cleared: Set<Uuid>): Assistant {
    val texts = segments.associate { it.sectionId to it.text }
    val updated = promptSections.map { section ->
        when (section.id) {
            in texts -> section.copy(content = texts.getValue(section.id))
            in cleared -> section.copy(content = "")
            else -> section
        }
    }
    val added = segments.filter { segment -> segment.text.isNotEmpty() && promptSections.none { it.id == segment.sectionId } }
        .map { SystemPromptSection(id = it.sectionId, content = it.text) }
    return withPromptSections(updated + added)
}

/** 直接改整块：没有分块时就是改 [Assistant.systemPrompt]；有分块时把改动落到对应分块。 */
fun Assistant.withWholeSystemPrompt(text: String): Assistant {
    if (promptSections.isEmpty()) return copy(systemPrompt = text)
    val remap = remapSystemPromptSegments(systemPromptSegments(fallback = Uuid.random()), text)
    return withSystemPromptSegments(remap.segments, remap.cleared)
}

/**
 * 写入时（App、Web、创造助手、导入）让整块与分块保持一致，[previous] 是写入前的同一助手（新建为 null）：
 * 分块变了 → 整块重算（同时把分块清空又给了新整块的，按新整块）；只有整块变了且有分块 → 落到分块。
 */
fun Assistant.reconcileSystemPrompt(previous: Assistant?): Assistant {
    val beforeSections = previous?.promptSections.orEmpty()
    val beforePrompt = previous?.systemPrompt ?: ""
    if (promptSections != beforeSections) {
        if (promptSections.isEmpty() && systemPrompt != beforePrompt) return this
        return withPromptSections(promptSections)
    }
    if (promptSections.isEmpty() || systemPrompt == beforePrompt) return this
    return withWholeSystemPrompt(systemPrompt)
}

/**
 * 读取兼容：2.10～2.12 两种模式各存一份。整块模式下发送的是整块，分块是另存的草稿 → 草稿全部关闭，
 * 整块成为第一块；分块模式下发送的是分块 → 整块改为合并结果，原整块文字作为关闭的块留在末尾。
 * 两种情况发给模型的内容都不变。新 id 由助手 id 派生，重复读取结果一致。
 */
fun Assistant.normalizedSystemPrompt(): Assistant {
    if (promptSections.isEmpty()) return this
    if (composeSystemPromptSections(promptSections) == systemPrompt) return this
    return when (systemPromptMode) {
        SystemPromptMode.WHOLE -> {
            val seed = if (systemPrompt.isNotBlank()) {
                listOf(SystemPromptSection(id = derivedSectionId(1), content = systemPrompt))
            } else emptyList()
            withPromptSections(seed + promptSections.map { it.copy(enabled = false) })
        }

        SystemPromptMode.SECTIONS -> {
            val backup = if (systemPrompt.isNotBlank() && promptSections.none { it.content == systemPrompt }) {
                listOf(SystemPromptSection(id = derivedSectionId(2), content = systemPrompt, enabled = false))
            } else emptyList()
            withPromptSections(promptSections + backup)
        }
    }
}

private fun Assistant.derivedSectionId(salt: Long): Uuid =
    id.toLongs { high, low -> Uuid.fromLongs(high xor 0x5ec7_10a5_0000_0000L xor salt, low.inv()) }

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
)

// 流式输出时每个chunk都会调用replaceRegexes，正则必须缓存编译结果，
// 否则长回复期间会重复编译上万次；编译失败也缓存，避免反复构造异常
private val regexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

private fun compileRegexCached(pattern: String): Regex? {
    regexCache.getIfPresent(pattern)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern) }.onFailure { it.printStackTrace() }
    regexCache.put(pattern, result)
    return result.getOrNull()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            val compiled = compileRegexCached(regex.findRegex) ?: return@fold acc
            try {
                acc.replace(
                    regex = compiled,
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                // 替换字符串可能引用不存在的分组，失败时返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）
}

/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    /**
     * 正则注入 - 基于内容匹配触发（世界书）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),  // 触发关键词
        val useRegex: Boolean = false,             // 是否使用正则匹配
        val caseSensitive: Boolean = false,        // 大小写敏感
        val scanDepth: Int = 4,                    // 扫描最近N条消息
        val constantActive: Boolean = false,       // 常驻激活（无需匹配）
    ) : PromptInjection()
}

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 * 检查 RegexInjection 是否被触发
 *
 * @param context 要扫描的上下文文本
 * @return 是否触发
 */
fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false

    return keywords.any { keyword ->
        if (useRegex) {
            try {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(keyword, options).containsMatchIn(context)
            } catch (e: Exception) {
                false
            }
        } else {
            if (caseSensitive) {
                context.contains(keyword)
            } else {
                context.contains(keyword, ignoreCase = true)
            }
        }
    }
}

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    return messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
}

/**
 * 获取所有被触发的注入，按优先级排序
 *
 * @param injections 所有注入规则
 * @param context 上下文文本
 * @return 被触发的注入列表，按优先级降序排列
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedByDescending { it.priority }
}
