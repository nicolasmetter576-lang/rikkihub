package me.rerere.rikkihub.data.model

import kotlinx.serialization.json.JsonObject
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.worldinfo.WorldInfoEntry
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkihub.data.ai.tools.local.LocalToolOption
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    // 停用的助手不在选助手的地方（抽屉、移动到助手、分享）列出；当前正在用的照常可用
    val enabled: Boolean = true,
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    // 酒馆预设：提示词条目 + 顺序 + 组装选项 + 助手作用域正则（extensions.regex_scripts）。取代旧的整块/分块系统提示词
    @Serializable(with = PromptPresetSerializer::class)
    val promptPreset: PromptPreset = AssistantPresets.withSystemPrompt(""),
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
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    // 酒馆预设带来的采样参数；null = 不发。top_k 之外的几个由各协议决定发不发（见 TextGenerationParams）
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val repetitionPenalty: Float? = null,
    val minP: Float? = null,
    val topA: Float? = null,
    val seed: Long? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = emptyList(), // 2.8.0 起默认不开启任何本地工具（含时间信息）
    val enableWebSearch: Boolean = false, // 网络搜索开关(每个助手独立)
    val workspaceId: Uuid? = null,
    val background: String? = null, // 聊天页背景图地址(本地文件 URI 或网络 URL), 为 null 时无背景
    val backgroundOpacity: Float = 1.0f, // 背景图不透明度(0~1)
    val useGradientBackground: Boolean = false, // 开启后聊天页使用动态渐变背景
    val libraryEntryIds: Set<Uuid> = emptySet(),        // 启用的提示词库条目 ID（原模式注入）
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val timeReminderIntervalMinutes: Int = 60,          // 时间提醒间隔（分钟，至少 1 分钟）
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowConversationPromptInjection: Boolean = false, // 允许对话单独绑定提示词注入
    // 酒馆模式：开时按完整布局组装（角色卡、用户设定、作者注释、组装选项）；
    // 关时用精简布局，提示词库和手动选用的世界书照常生效。新建助手默认关，导入酒馆预设的助手开
    val tavernMode: Boolean = false,
    // 每轮标题：在系统提示词末尾附上约定，让模型在回复末行写 <rk-title>（普通模式与酒馆模式共用）
    val enableMessageTitle: Boolean = false,
    // 新对话默认开启的工作区 CLI（agentId）；null = 普通对话。解绑工作区时清空
    val defaultCliAgentId: String? = null,
    val defaultCliPermissionMode: me.rerere.rikkihub.data.cli.CliPermissionMode = me.rerere.rikkihub.data.cli.CliPermissionMode.ASK,
    // 双栏助手卡片展示偏好：手写备注（留空不显示）与手动固定整行显示
    val cardNote: String = "",
    val cardWide: Boolean = false,
) {
    /** 更新助手绑定的工作区；解绑或更换工作区时同步清空依赖原工作区的默认 CLI 选择。 */
    fun withWorkspace(newWorkspaceId: Uuid?): Assistant = copy(
        workspaceId = newWorkspaceId,
        defaultCliAgentId = if (newWorkspaceId == null || newWorkspaceId != workspaceId) null else defaultCliAgentId,
    )
}

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

/**
 * 世界书：一组酒馆世界书条目（`WorldInfoEntry`，酒馆原格式，导入导出无损）。
 * [enabled] 是 rikkihub 自己的总开关；其余字段对应酒馆世界书文件。
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<@Serializable(with = WorldInfoEntrySerializer::class) WorldInfoEntry> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
)
