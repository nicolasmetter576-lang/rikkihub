package me.rerere.rikkihub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.workspace.rootfsEnvironmentPromptLines
import me.rerere.rikkihub.data.workspace.workspaceReminderPrompt
import me.rerere.rikkihub.data.workspace.workspaceAgentsInstructions
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个工作区时, 在系统提示词中追加一段引导, 让模型了解 workspace
 * 环境与本次请求真实可用的工具（含 Override 后的 wire 名）。
 *
 * 能力来源：
 * - `ctx.workspaceCapabilities != null` 且 id 与 assistant 绑定的工作区一致 →
 *   工具提示直接使用本次快照（不重新查 DB）；未 READY/全关时快照为空表，提示明确
 *   「tools unavailable」，绝不回退旧固定清单、不推荐已禁用工具。
 * - `null`（固定 tools 的旧调用方）→ 旧行为：查 DB、仅 READY 注入、按模式固定清单。
 * AGENTS.md 独立追加，不替换冻结提醒；每份读取经 repository gate 复核 READY/挂载，
 * 自动读取不依赖模型工具开关。cwd 来自快照（包括显式 null）。
 */
class WorkspaceReminderTransformer internal constructor(
    private val loadWorkspace: suspend (String) -> WorkspaceEntity?,
    /** Rendered shell/distribution lines for the legacy (no snapshot) path. */
    private val rootfsEnvironment: (WorkspaceEntity) -> String = { "" },
    /** (workspaceId, guest path, files scope) → text; the scope confines the read. */
    private val readInstructions: suspend (String, String, String?) -> String,
) : InputMessageTransformer {
    constructor(workspaceRepository: WorkspaceRepository) : this(
        loadWorkspace = workspaceRepository::getById,
        readInstructions = workspaceRepository::readAgentsInstructions,
        rootfsEnvironment = { rootfsEnvironmentPromptLines(workspaceRepository.rootfsEnvironment(it)) },
    )
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages

        val reminder: String
        val instructionsEligible: Boolean
        val instructionsCwd: String?
        val caps = ctx.workspaceCapabilities
        val filesScope = caps?.filesScope
        if (caps != null) {
            // Never fall back to a different/old workspace when a request supplied capabilities.
            if (caps.workspaceId != workspaceId) return messages
            // The snapshot carries overrides from the same settings used to build the wire schema.
            instructionsEligible = caps.instructionsEligible
            instructionsCwd = caps.instructionsCwd
            reminder = caps.reminderPrompt ?: workspaceReminderPrompt(
                workspaceName = caps.workspaceName,
                harnessMinimal = caps.harnessMinimal,
                exposedTargets = caps.exposedTargets,
                cwd = if (caps.instructionsEligible) caps.instructionsCwd else ctx.workspaceCwd,
                overrides = ctx.settings.toolOverrides,
                capabilities = caps,
            )
        } else {
            // 旧路径（未提供快照的调用方）：查 DB + READY 门 + 按模式固定清单。
            val workspace = loadWorkspace(workspaceId) ?: return messages
            // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
            if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages
            instructionsEligible = true
            instructionsCwd = ctx.workspaceCwd
            reminder = workspaceReminderPrompt(
                workspaceName = workspace.name,
                harnessMinimal = workspace.harnessMinimal,
                exposedTargets = workspace.exposedMountTargets(),
                cwd = ctx.workspaceCwd,
                overrides = ctx.settings.toolOverrides,
                capabilities = null,
                rootfsEnvironment = rootfsEnvironment(workspace),
            )
        }

        val prompt = reminder + if (instructionsEligible) {
            workspaceAgentsInstructions(instructionsCwd, filesScope) { path -> readInstructions(workspaceId, path, filesScope) }
        } else ""

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
