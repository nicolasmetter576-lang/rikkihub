package me.rerere.rikkihub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.ConcatenatedJsonObjects

/** OpenAI Chat Completions 对 tool_call id 的长度上限；派生 id 不得超过。 */
private const val MAX_TOOL_CALL_ID_LENGTH = 40
private const val MAX_PARSE_DETAIL_CHARS = 300

/** 一次拆分的诊断记录：只含身份与计数，不含参数正文。 */
internal data class ToolCallSplit(
    val toolName: String,
    val toolCallId: String,
    val segments: Int,
    val duplicatesDropped: Int,
)

/**
 * 把本轮新生成、参数为「多个 JSON 对象首尾相接」的 Tool part 拆回独立调用。
 *
 * 必须在审批判定之前调用：拆出的每个调用按自己的参数走审批/执行/回写；
 * 未拆时 `inputAsJson()` 对拼接参数退化为 `{}`，按参数判定的审批策略会失效。
 *
 * - 只处理未执行、审批态为 Auto 的 part（即刚从流里收尾的调用）；可正常
 *   解析的参数、或不符合严格拼接形态的参数原样保留（后者在执行端按非法
 *   参数回灌错误）。
 * - 首段沿用原 toolCallId 与 metadata；其余段派生新 id（`<id>_2`…，超长时
 *   改用 `call_<hash>_N`），与本消息内既有 id 不重复。各 provider 都是无状态
 *   全量回传历史，派生 id 不需要与服务端记录对应。metadata 只留首段：
 *   其中可能有签名类数据，不应复制到客户端派生的调用上。
 * - 重复对象只执行一次（见 [ConcatenatedJsonObjects]）。
 * - 给了 [knownToolNames] 时，名字不在表里的调用不拆：反正执行不了，拆开只会
 *   把一次 not found 变成 N 次。
 */
internal fun UIMessage.splitConcatenatedToolCalls(
    json: Json,
    knownToolNames: Set<String>? = null,
): Pair<UIMessage, List<ToolCallSplit>> {
    val splits = mutableListOf<ToolCallSplit>()
    val usedIds = parts.filterIsInstance<UIMessagePart.Tool>().mapTo(mutableSetOf()) { it.toolCallId }
    val newParts = parts.flatMap { part ->
        if (part !is UIMessagePart.Tool || part.isExecuted ||
            part.approvalState !is ToolApprovalState.Auto
        ) {
            return@flatMap listOf(part)
        }
        if (knownToolNames != null && part.toolName !in knownToolNames) {
            return@flatMap listOf(part)
        }
        if (runCatching { json.parseToJsonElement(part.input.ifBlank { "{}" }) }.isSuccess) {
            return@flatMap listOf(part)
        }
        val result = ConcatenatedJsonObjects.split(part.input) ?: return@flatMap listOf(part)
        splits += ToolCallSplit(
            toolName = part.toolName,
            toolCallId = part.toolCallId,
            segments = result.totalSegments,
            duplicatesDropped = result.duplicatesDropped,
        )
        result.segments.mapIndexed { index, segment ->
            if (index == 0) {
                part.copy(input = segment)
            } else {
                val id = deriveSplitToolCallId(part.toolCallId, index + 1, usedIds)
                usedIds += id
                part.copy(toolCallId = id, input = segment, metadata = null)
            }
        }
    }
    if (splits.isEmpty()) return this to emptyList()
    return copy(parts = newParts) to splits
}

private fun deriveSplitToolCallId(base: String, ordinal: Int, usedIds: Set<String>): String {
    var n = ordinal
    while (true) {
        val suffix = "_$n"
        val candidate = if (base.isNotBlank() && base.length + suffix.length <= MAX_TOOL_CALL_ID_LENGTH) {
            base + suffix
        } else {
            "call_" + base.hashCode().toUInt().toString(16) + suffix
        }
        if (candidate !in usedIds) return candidate
        n++
    }
}

/**
 * 参数无法解析为单个 JSON 时回灌给模型的错误：说明错在哪、该怎么改，
 * 不附异常堆栈（对模型无用且占 token）。不回显参数正文。
 */
internal fun invalidToolArgumentsParts(
    json: Json,
    toolName: String,
    failure: Throwable,
): List<UIMessagePart> {
    val detail = failure.message.orEmpty()
        .substringBefore("\nJSON input")
        .take(MAX_PARSE_DETAIL_CHARS)
    return listOf(
        UIMessagePart.Text(
            json.encodeToString(
                buildJsonObject {
                    put("error", "invalid_arguments")
                    put(
                        "message",
                        "Arguments for tool '$toolName' are not a single valid JSON object " +
                            "($detail). The tool was not executed. Arguments must be exactly one " +
                            "JSON object matching the tool's parameter schema; to perform several " +
                            "operations, make a separate tool call for each.",
                    )
                }
            )
        )
    )
}
