package me.rerere.rikkihub.data.devin

import me.rerere.ai.provider.providers.devin.DevinToolCallDelta
import me.rerere.ai.provider.stream.ToolNameResolver
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunk

/**
 * 把 Devin wire 的工具调用增量 ([DevinToolCallDelta]) 翻译为通用 [StreamChunk] 事件.
 *
 * [DevinProvider] 只驱动输入输出: 每个响应帧的 `toolCallDeltas` 交给 [onDeltas],
 * EOS 时调用一次 [finish]. 名称消歧、调用身份与冲突拒绝全部收敛在本类,
 * 以便离线测试直接驱动 assembler + 真实 StreamChunkHandler.
 * (接入流事件契约与共享 resolver)
 *
 * wire 契约 (ChatToolCall 没有 index 字段): 一个调用的增量连续到达,
 * 首帧带 id/name, 后续帧只带参数片段. 因此调用身份**按 id** 区分:
 * - 带已知 id → 归属该 id 的调用 (不要求是最近一个);
 * - 带新 id → 新调用; 唯一例外是最近的调用还没有真实 id (首帧缺 id 的兼容输入),
 *   这时是迟到 id, 就地补绑并发 [StreamChunk.ToolCallIdAssigned];
 * - 不带 id → 接到最近一个调用; 还没有调用时新开一个 (synthetic 显示 id).
 * 旧实现把 field 4 (`invalid_json_str`) 误当 index, 所有调用都落到同一槽位,
 * 并行调用的名字/参数被拼成一个 (`subagentworkspace_read_file` / `{A}{B}`).
 *
 * 其余语义:
 * - **streamKey**: 按出现顺序分配 (`devin-tool-<n>`), 整个响应内稳定;
 *   StreamChunk 的 `id` 字段一律承载 streamKey, 对外真实调用 id 走
 *   `toolCallId` 字段/Assigned 事件.
 * - **名称**: 每个逻辑调用一个 [ToolNameResolver], 注册表为本轮请求实际下发的
 *   工具名集. `accept` 返回的已确认增量作为 `ToolCallStart.toolName` /
 *   `ToolCallDelta.toolNameDelta` 发射; `finish` 返回的收尾增量在 `ToolCallEnd`
 *   前 flush. 空 wire name 不触碰 resolver 状态.
 * - **参数**: `arguments` 是真实增量, 逐字追加, 不去重/不做前缀差分;
 *   `arguments` 为空时用 `invalid_json_str` (模型参数不是合法 JSON 时上游放原文
 *   的位置), 让执行端回灌 invalid_arguments, 而不是按空参数 `{}` 执行.
 * - **finish**: 幂等; 对每个已开始未结束的调用先 flush 名称尾部增量再发 End.
 *   finish 之后再到来的工具增量视为协议错误. 单响应调用数超过
 *   [MAX_CALLS] 也按协议错误处理.
 */
internal class DevinToolCallAssembler(
    private val knownToolNames: Set<String>,
) {
    private class CallState(
        val streamKey: String,
        val syntheticId: String,
        val nameResolver: ToolNameResolver,
    ) {
        /** 已确认的真实 wire call id; null = 尚未见过非空 id (身份仍待补全) */
        var wireCallId: String? = null

        /** ToolCallStart 是否已发射 */
        var started = false

        /** ToolCallEnd 是否已发射; End 后的调用不复活 */
        var ended = false
    }

    /** 按出现顺序的全部调用; finish 时按此顺序发射 End */
    private val calls = ArrayList<CallState>()

    /** 真实 wire id → 调用, 保留至响应结束 */
    private val callsByWireId = HashMap<String, CallState>()

    private var finished = false

    /**
     * 处理一帧内的全部工具调用增量, 返回按序待发射的 [StreamChunk].
     *
     * @throws ToolStreamProtocolException finish 之后仍有工具增量, 或调用数超界
     * @throws ToolStreamProtocolException resolver 仅对终态多候选歧义/候选或
     *   长度超界拒绝；未注册的 wire 名不拒绝——候选全灭后按逐字面量透传，
     *   由执行端报 not found 反馈模型自纠。
     */
    fun onDeltas(deltas: List<DevinToolCallDelta>): List<StreamChunk> {
        if (finished) {
            throw ToolStreamProtocolException(
                code = CODE_STREAM_ALREADY_FINISHED,
                message = "devin tool call delta received after finish",
            )
        }
        val chunks = mutableListOf<StreamChunk>()
        for (delta in deltas) {
            val state = route(delta.id.takeUnless { it.isBlank() }, chunks)
            val arguments = delta.arguments.ifEmpty { delta.invalidJsonStr }

            if (!state.started) {
                state.started = true
                chunks += StreamChunk.ToolCallStart(
                    id = state.streamKey,
                    toolName = resolveNameIncrement(state, delta.name),
                    toolCallId = state.wireCallId ?: state.syntheticId,
                )
                // 首个增量可能同时携带参数, 名称增量进 Start, 参数走 Delta
                if (arguments.isNotEmpty()) {
                    chunks += StreamChunk.ToolCallDelta(
                        id = state.streamKey,
                        inputDelta = arguments,
                    )
                }
            } else {
                val nameDelta = resolveNameIncrement(state, delta.name)
                if (nameDelta.isNotEmpty() || arguments.isNotEmpty()) {
                    chunks += StreamChunk.ToolCallDelta(
                        id = state.streamKey,
                        toolNameDelta = nameDelta,
                        inputDelta = arguments,
                    )
                }
            }
        }
        return chunks
    }

    /**
     * EOS: 对每个已开始未结束的调用, 先 flush resolver 的名称尾部增量再发 End.
     * 幂等 —— 重复调用返回空列表, 不重复发射 End.
     */
    fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        val chunks = mutableListOf<StreamChunk>()
        for (state in calls) {
            if (!state.started || state.ended) continue
            state.ended = true
            // finish() 返回结束时剩余的已确认名称增量 (不是完整名)
            val tail = state.nameResolver.finish()
            if (tail.isNotEmpty()) {
                chunks += StreamChunk.ToolCallDelta(
                    id = state.streamKey,
                    toolNameDelta = tail,
                )
            }
            chunks += StreamChunk.ToolCallEnd(id = state.streamKey)
        }
        return chunks
    }

    /**
     * 按 wire id 找到增量归属的调用, 必要时新开. 已开始的调用迟到真实 id 时
     * 把 [StreamChunk.ToolCallIdAssigned] 追加进 [chunks] —— 未开始时绑定会
     * 体现在随后 Start 的 toolCallId 上.
     */
    private fun route(wireId: String?, chunks: MutableList<StreamChunk>): CallState {
        val latest = calls.lastOrNull()
        if (wireId == null) {
            return latest ?: newCall(wireId = null)
        }
        callsByWireId[wireId]?.let { return it }
        if (latest != null && latest.wireCallId == null) {
            latest.wireCallId = wireId
            callsByWireId[wireId] = latest
            if (latest.started) {
                chunks += StreamChunk.ToolCallIdAssigned(
                    id = latest.streamKey,
                    toolCallId = wireId,
                )
            }
            return latest
        }
        return newCall(wireId)
    }

    private fun newCall(wireId: String?): CallState {
        if (calls.size >= MAX_CALLS) {
            throw ToolStreamProtocolException(
                code = CODE_TOO_MANY_CALLS,
                message = "devin response opened more than $MAX_CALLS tool calls",
            )
        }
        val ordinal = calls.size
        val state = CallState(
            streamKey = streamKeyOf(ordinal),
            syntheticId = syntheticIdOf(ordinal),
            nameResolver = ToolNameResolver(knownToolNames),
        )
        wireId?.let {
            state.wireCallId = it
            callsByWireId[it] = state
        }
        calls += state
        return state
    }

    private fun resolveNameIncrement(state: CallState, wireName: String): String {
        if (wireName.isEmpty()) return "" // 空名不清状态、不发射
        return state.nameResolver.accept(wireName)
    }

    private companion object {
        /** 单响应调用数上限 (同 CLIProxyAPI 的 maxDevinToolCalls) */
        const val MAX_CALLS = 128

        const val CODE_STREAM_ALREADY_FINISHED = "devin_tool_stream_finished"
        const val CODE_TOO_MANY_CALLS = "devin_tool_calls_too_many"

        /** 内部路由键; 与 wire id/上游 outputId 完全无关, 响应内稳定 */
        fun streamKeyOf(ordinal: Int) = "devin-tool-$ordinal"

        /** wire id 缺失时的临时显示身份; 真实 id 到达后经 Assigned 替换 */
        fun syntheticIdOf(ordinal: Int) = "devin-call-$ordinal"
    }
}
