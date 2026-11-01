package me.rerere.rikkihub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.ChatToolSnapshot
import me.rerere.rikkihub.data.ai.tools.ToolExecutionConfig
import me.rerere.rikkihub.data.ai.tools.withBuiltinConcurrency
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolSnapshot
import me.rerere.rikkihub.data.ai.tools.buildToolBindings

/**
 * 生成循环的工具快照辅助（请求一致快照）。wireName→Tool 只信本次请求快照的
 * 精确表 [ChatToolSnapshot.bindings]，不用 ToolOverride/历史 alias 反查。
 *
 * 完成态输出 status 词表与执行守卫（WorkspaceToolSnapshot.kt）一致：
 * `unavailable`（未开始/被拒，零执行）与 `outcome_unknown`（在途取消，可能
 * 有部分副作用）。统计剔除见 [hasUnexecutedOutcome]。
 */
private const val TOOL_STATUS_UNAVAILABLE = "unavailable"
private const val TOOL_STATUS_OUTCOME_UNKNOWN = "outcome_unknown"

/**
 * 快照校验超时上限错误：构建的 attempt 提示/能力在请求发出前已被更新的
 * 策略视图取代，且重建达到上限仍持续漂移（工作区配置被反复修改）。
 * 属本地错误——不走 provider 网络重试路径，直接终止本 step 的生成，
 * 让外层把真实原因抛给消费侧，而不是用陈旧能力面继续发请求。
 */
internal class StaleToolSnapshotException(
    val rebuildCount: Int,
) : IllegalStateException(
    "tool snapshot went stale $rebuildCount times while building the request " +
        "(workspace tool policy is changing too fast); refusing to send a stale request",
)

/**
 * 固定 tools 调用者（未传 toolSnapshotProvider）的兼容快照。
 *
 * bindings 用首重名保留（first-wins），与旧执行路径 `tools.find { it.name == ... }`
 * 的命中语义一致；不抛 [me.rerere.rikkihub.data.ai.tools.DuplicateToolWireNameException]
 * ——查重是快照装配侧（createSnapshot）的职责，旧 caller 行为保持不变。
 */
internal fun fixedToolsSnapshot(tools: List<Tool>): ChatToolSnapshot {
    val bindings = LinkedHashMap<String, Tool>(tools.size)
    for (tool in tools) {
        bindings.putIfAbsent(tool.name, tool)
    }
    return ChatToolSnapshot(tools = tools, bindings = bindings)
}

/**
 * 工作区切片快照 → 生成循环消费的完整快照。
 * 切片不含非工作区工具；capabilities/wirePolicy/forbidden 同名映射。
 * 重名在切片层已由装配保证（buildToolBindings 再校验一次，失败即抛）。
 */
internal fun WorkspaceToolSnapshot.toChatToolSnapshot(): ChatToolSnapshot =
    ChatToolSnapshot(
        tools = tools,
        bindings = buildToolBindings(tools),
        forbiddenWorkspaceToolNames = forbidden.keys,
        wirePolicy = wirePolicy,
        workspaceCapabilities = capabilities,
        // 切片的时效判定透传：组装后策略再变即要求重建 child 整份 attempt。
        isCurrent = isCurrent,
    )

/**
 * 子代理 child 工具面：剥掉审批钩子（免审批契约），其余字段（含
 * workspaceIdentity / execute 守卫包装 / bindings / capabilities / wirePolicy）
 * 经 data class copy 原样穿透——`withoutToolApproval` 这类 copy 包装不能移除守卫。
 */
internal fun ChatToolSnapshot.withoutToolApprovals(): ChatToolSnapshot =
    mapTools { tool -> tool.copy(needsApproval = { false }) }

/** 给快照里的内置工具写入并行调度策略（tools 与 bindings 同步替换）。 */
internal fun ChatToolSnapshot.withBuiltinConcurrency(config: ToolExecutionConfig): ChatToolSnapshot =
    mapTools { tool -> tool.withBuiltinConcurrency(config) }

/** 逐个替换工具，bindings 按原实例映射到替换后的实例（wire 名不变）。 */
internal fun ChatToolSnapshot.mapTools(transform: (Tool) -> Tool): ChatToolSnapshot {
    val mapped = tools.map(transform)
    val byInstance = HashMap<Tool, Tool>(tools.size).apply {
        tools.forEachIndexed { index, tool -> put(tool, mapped[index]) }
    }
    val mappedBindings = LinkedHashMap<String, Tool>(bindings.size)
    bindings.forEach { (wireName, tool) ->
        mappedBindings[wireName] = byInstance[tool] ?: tool
    }
    return copy(tools = mapped, bindings = mappedBindings)
}

/**
 * 「未开始即不可用」的非空 JSON 输出：解除 pending，不算执行。
 * status 词表与守卫的 workspaceToolUnavailableParts 一致（"unavailable"）。
 * [reason] 为诊断标识（如快照失败原因、工具已禁用）。
 */
internal fun unavailableToolParts(
    json: Json,
    reason: String,
    toolName: String,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        json.encodeToString(
            buildJsonObject {
                put("status", TOOL_STATUS_UNAVAILABLE)
                put("reason", reason)
                put(
                    "error",
                    "Tool '$toolName' is unavailable ($reason); it was not executed.",
                )
            }
        )
    )
)

/**
 * 本轮表里没有这个名字（模型幻觉/工具已关/流式解码拼坏的名字）时回灌给模型的
 * 输出：不带异常类名与堆栈，列出当前可调用的工具名（最多 [MAX_LISTED_TOOL_NAMES]
 * 个），让模型按准确名称重发。只是反馈，不做近似匹配或自动改名。
 */
internal fun toolNotFoundParts(
    json: Json,
    toolName: String,
    availableToolNames: Collection<String>,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        json.encodeToString(
            buildJsonObject {
                put(
                    "error",
                    "Tool '$toolName' not found; it was not executed. Call one of the " +
                        "available tools by its exact name, one call per operation.",
                )
                put("available_tools", buildJsonArray {
                    availableToolNames.take(MAX_LISTED_TOOL_NAMES).forEach { add(it) }
                })
                if (availableToolNames.size > MAX_LISTED_TOOL_NAMES) {
                    put("more_tools", availableToolNames.size - MAX_LISTED_TOOL_NAMES)
                }
            }
        )
    )
)

private const val MAX_LISTED_TOOL_NAMES = 64

/**
 * 「已开始、被工作区策略/范围取消」的完成态输出（守卫之外的兜底路径——
 * 带守卫的工作区工具在 execute 包装内已映射为同样的词表）。
 * `started=true` 声明可能已有部分副作用，不冒充零执行。
 */
internal fun scopeCancelledToolParts(
    json: Json,
    toolName: String,
    error: Throwable,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        json.encodeToString(
            buildJsonObject {
                put("status", TOOL_STATUS_OUTCOME_UNKNOWN)
                put("started", JsonPrimitive(true))
                put(
                    "error",
                    "Tool '$toolName' was cancelled while in flight; " +
                        "the call may have partially executed " +
                        "(${error.javaClass.simpleName}: ${error.message})",
                )
            }
        )
    )
)

/**
 * 该 Tool part 是否是「未开始、零执行」的占位结果（status=unavailable）。
 *
 * 统计口径（toolsUsed / edited_paths）只剔除这类 part——unavailable 不冒充执行。
 * status=outcome_unknown 的 part **保留**：守卫/loop 写出它时已开始执行
 * （started=true，可能有部分副作用），真实统计里它是「已启动但结果未知」的
 * 调用——计入 tools_used / edited_paths（后者本来就只取参数路径、不代表
 * 实际写盘），原始 transcript 保留 status 字段供追溯。
 */
internal fun UIMessagePart.Tool.hasUnavailableOutcome(json: Json): Boolean {
    if (!isExecuted) return false
    val text = output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
        ?: return false
    val obj = runCatching {
        json.parseToJsonElement(text).jsonObject
    }.getOrNull() ?: return false
    return obj["status"]?.jsonPrimitive?.content == TOOL_STATUS_UNAVAILABLE
}

/**
 * 供 collectSubagentStats 的过滤视图：剔除「零执行」的 unavailable 工具 part，
 * 不改动真实 transcript（历史 part 原样保留在消息里）；outcome_unknown
 * （已启动、可能部分副作用）按实际统计保留。
 */
internal fun transcriptForStats(json: Json, messages: List<UIMessage>): List<UIMessage> =
    messages.map { message ->
        val filtered = message.parts.filterNot { part ->
            part is UIMessagePart.Tool && part.hasUnavailableOutcome(json)
        }
        if (filtered.size == message.parts.size) message else message.copy(parts = filtered)
    }
