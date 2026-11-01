package me.rerere.rikkihub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.ai.tools.enabledWorkspaceToolIds
import me.rerere.rikkihub.data.ai.tools.forbiddenWorkspaceToolNames
import me.rerere.rikkihub.data.ai.tools.resolvedName
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.workspace.WorkspaceShellStatus

/**
 * pending 清理纯逻辑：会话树里「未开始/未执行」的工作区工具调用
 * 按当前真实状态精确复核，不可用的写非空 unavailable 输出解除阻塞。
 *
 * 本文件不依赖 Android/协程/DB，可纯 JVM 单测。与 GenerationLoop 执行侧
 * 的分工：loop 负责在途取消（outcome_unknown）与准入拒绝（unavailable）的
 * 实时产出；这里负责 gate 事件之后、会话历史里残留的 `!isExecuted` part
 * （Pending 审批/被中断的 Auto），且**只动未完成 part**——已完成历史
 * 与整消息一律不改不删。
 *
 * 输出词表与守卫/loop 对齐：status="unavailable"（未开始，零执行），
 * 供 hasUnexecutedOutcome 统计剔除；不冒充成功，不伪造执行结果。
 */

enum class WorkspacePendingToolStatus {
    /** 仍是当前可用的工作区调用或非工作区调用可放行——保持未执行待恢复。 */
    READY,

    /** 已不可用：禁用/归属无效/撞名——写非空 unavailable 输出。 */
    UNAVAILABLE,

    /** 与工作区无关的普通工具 part：清理完全不干预。 */
    NOT_WORKSPACE,
}

data class WorkspacePendingToolResolution(
    val status: WorkspacePendingToolStatus,
    /** 机器可读原因（守卫 reason / 自定义词），进输出 JSON。 */
    val reason: String? = null,
)

/**
 * part 粒度的判定口。非 suspend、纯内存——会话态更新在 `state.update` 的
 * CAS 循环里可重放，实现必须无副作用且幂等。
 *
 * @param hostMessageId 宿主 `UIMessage.id`
 */
fun interface WorkspacePendingToolJudge {
    fun resolve(
        part: UIMessagePart.Tool,
        hostMessageId: String,
    ): WorkspacePendingToolResolution
}

data class WorkspacePendingToolPatchResult(
    val conversation: Conversation,
    /** 被写成 unavailable 的 part 数 */
    val patchedParts: Int,
    /** 被复核的未完成工具 part 总数 */
    val examinedParts: Int,
)

private const val TOOL_STATUS_UNAVAILABLE = "unavailable"

/** 启用工具表内两个 stableId 解析出同一 wire 名：归属显式歧义，拒绝恢复。 */
internal const val NAME_COLLISION_REASON = "NAME_COLLISION"

/** 工作区形状的调用，但会话当前没有可用（READY）工作区。 */
internal const val WORKSPACE_UNAVAILABLE_REASON = "WORKSPACE_UNAVAILABLE"

/** checkInvalidMessages 兜底标记词：未执行且不可恢复的调用。 */
internal const val UNRESUMABLE_TOOL_CALL_REASON = "UNRESUMABLE_TOOL_CALL"

internal fun unavailableOutput(
    part: UIMessagePart.Tool,
    reason: String,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("status", TOOL_STATUS_UNAVAILABLE)
            put("reason", reason)
            put("tool", part.toolName)
            put(
                "error",
                "Pending workspace tool call '${part.toolName}' is unavailable ($reason); " +
                    "it was never executed.",
            )
        }.toString(),
    ),
)

/**
 * 复核 [Conversation] 全树（所有节点、所有分支消息，不限 currentMessages）
 * 中 `!isExecuted` 的 Tool part；judge 判 UNAVAILABLE 的写非空 unavailable
 * 输出（copy 只换 output，metadata/approvalState/input 全保留）。
 * 任何已完成 part、非 Tool part、节点结构与 selectIndex 一律不动；
 * 无变更时返回原实例（幂等，供调用方跳过持久化）。
 */
internal fun Conversation.patchWorkspacePendingToolCalls(
    judge: WorkspacePendingToolJudge,
): WorkspacePendingToolPatchResult {
    var patched = 0
    var examined = 0
    var changed = false
    val nodes = messageNodes.map { node ->
        var nodeChanged = false
        val messages = node.messages.map { message ->
            var msgChanged = false
            val parts = message.parts.map { part ->
                if (part !is UIMessagePart.Tool || part.isExecuted) {
                    return@map part
                }
                examined++
                val resolution = judge.resolve(part, message.id.toString())
                if (resolution.status == WorkspacePendingToolStatus.UNAVAILABLE) {
                    patched++
                    msgChanged = true
                    part.copy(
                        output = unavailableOutput(
                            part,
                            resolution.reason ?: TOOL_STATUS_UNAVAILABLE,
                        ),
                    )
                } else {
                    part
                }
            }
            if (msgChanged) {
                nodeChanged = true
                message.copy(parts = parts)
            } else {
                message
            }
        }
        if (nodeChanged) {
            changed = true
            node.copy(messages = messages)
        } else {
            node
        }
    }
    return WorkspacePendingToolPatchResult(
        conversation = if (changed) copy(messageNodes = nodes) else this,
        patchedParts = patched,
        examinedParts = examined,
    )
}

/**
 * 构建一次清理的判定器（同一轮复用快照表，不逐 part 查 DB）。
 *
 * 判定只看当前状态：会话工作区 READY 且该名字是当前启用、唯一解析的工作区工具 →
 * READY（保持待恢复，执行时由守卫按最新 DB 复核）；名字落在禁用面 → TOOL_DISABLED；
 * 撞名 → NAME_COLLISION；工作区形状的名字但会话没有可用工作区 → WORKSPACE_UNAVAILABLE。
 *
 * @param sessionWorkspace 会话**当前**归属工作区（conversation.assistantId →
 *   assistant.workspaceId 重读的最新实体）；null = 归属无效/无工作区
 * @param toolOverrides    当前全局别名表（只用于识别「当前名」）
 */
internal fun buildWorkspacePendingToolJudge(
    sessionWorkspace: WorkspaceEntity?,
    toolOverrides: Map<String, ToolOverride>,
): WorkspacePendingToolJudge {
    val resolvedNames: Map<String, String> =
        if (sessionWorkspace != null &&
            sessionWorkspace.shellStatus == WorkspaceShellStatus.READY.name
        ) {
            enabledWorkspaceToolIds(sessionWorkspace).associate { stableId ->
                stableId to BuiltinToolCatalog.require(stableId).resolvedName(toolOverrides)
            }
        } else {
            emptyMap()
        }
    val wireNameCounts = resolvedNames.values.groupingBy { it }.eachCount()
    val collidedWireNames: Set<String> = wireNameCounts.filterValues { it > 1 }.keys
    val sessionWireNames: Set<String> = wireNameCounts.filterValues { it == 1 }.keys

    // 会话工作区当前禁用/模式外 stableId 的解析名（非 READY 时含全部可配置 ID）。
    val sessionForbidden: Map<String, String> = sessionWorkspace
        ?.let { forbiddenWorkspaceToolNames(it, toolOverrides) }
        ?: emptyMap()

    // 兜底别名表：会话无工作区时仍能把「工作区形状的 pending part」挑出来关闭，
    // 而不是留在 pending 卡队列。
    val globalAliasStableIds: Map<String, String> =
        WorkspaceToolEnablement.configurableIds.associate { stableId ->
            BuiltinToolCatalog.require(stableId).resolvedName(toolOverrides) to stableId
        }

    return WorkspacePendingToolJudge { part, _ ->
        when {
            part.toolName in collidedWireNames ->
                WorkspacePendingToolResolution(WorkspacePendingToolStatus.UNAVAILABLE, NAME_COLLISION_REASON)
            part.toolName in sessionWireNames ->
                WorkspacePendingToolResolution(WorkspacePendingToolStatus.READY)
            sessionForbidden.containsKey(part.toolName) ->
                WorkspacePendingToolResolution(WorkspacePendingToolStatus.UNAVAILABLE, "TOOL_DISABLED")
            globalAliasStableIds.containsKey(part.toolName) ->
                WorkspacePendingToolResolution(WorkspacePendingToolStatus.UNAVAILABLE, WORKSPACE_UNAVAILABLE_REASON)
            else -> WorkspacePendingToolResolution(WorkspacePendingToolStatus.READY)
        }
    }
}
