package me.rerere.ai.provider.stream

/**
 * 带 index 类协议（Chat Completions 等）的流式工具调用身份账本。
 *
 * 内部逻辑调用键（streamKey）与对外 wire call id 分离：
 * - streamKey 由本账本按响应内序号生成（`chat-tool-N`），整个响应稳定，
 *   不依赖 wire 上 `id`/`responseId` 等不稳定字段；
 * - wire id 为空/缺失不改变内部身份；迟到的真实 id 只是同一调用的身份
 *   补全（`assignedId`），不产生新调用；
 * - 不同逻辑调用不得因 wire id 相同而混合；信息冲突无法可靠恢复时显式
 *   抛 [ToolStreamProtocolException]，绝不猜测路由。
 *
 * 账本保留至整个 response 结束（含已结束调用），不会因清空活动集合而
 * 忘记已占用的 index / wire id。
 *
 * 路由规则：
 * 1. 显式 index 命中既有调用：既存 wire id 非空且不同 → 协议错误；既存
 *    为 null 而本帧带非空 id → 补绑并回报 `assignedId`（供发射
 *    `ToolCallIdAssigned`）；同 id 重发 → no-op。
 * 2. 无 index 有非空 id：别名表命中路由到唯一调用；未知 id 仅在整响应
 *    尚无调用时创建单槽，或整响应恰有一个未绑定真实 id 的未结束调用时
 *    作为迟到 id 就地绑定；其余一律拒绝猜测。
 *    （同一真实 id 被一个**已有不同 index**的调用占用时，无论该调用进行
 *    中还是已 End，一律 `tool_call_id_reused_across_index` 拒绝：不同
 *    index 指向的逻辑调用不得因 wire id 相同而合并——否则两个调用的
 *    名称/参数会拼进同一个 part，如 search_web+read_file。只有 index 为
 *    null 的无 index 调用才允许被首个显式 index 收编，那是同一调用的
 *    身份补全而非跨调用合并。）
 * 3. 无 index 且无可靠 id：整响应尚无调用且当前包只有这一个无身份块时
 *    创建单槽；账本只有一个未结束调用时可续接；否则拒绝（缺 index 的
 *    并行流不存在通用无损恢复法，绝不回退到 index0）。
 * 4. `id:null`/空白一律按缺失语义处理（不计入别名表、不覆盖既有绑定）。
 *    已结束的调用不复活：落到它的任何路由都是协议错误。
 */
internal class ToolCallLedger {
    /**
     * 一个逻辑调用的身份状态。[streamKey] 与 [syntheticId] 创建时固定；
     * [syntheticId] 只是绑定真实 wire id 前的临时显示身份（须稳定非空），
     * 真实 id 到达后由消费侧经 `ToolCallIdAssigned` 替换，绝不能算作
     * 「已有真实 id」参与冲突判定。
     */
    class Call internal constructor(
        /** 创建该调用时 wire 上的 index；无 index 创建的调用为 null。 */
        val index: Int?,
        /** 本响应内稳定的内部路由键，StreamChunk.id 承载它。 */
        val streamKey: String,
        /** wire id 缺失时的稳定非空临时显示 id（Start.toolCallId 的兜底值）。 */
        val syntheticId: String,
    ) {
        /** 已确认的真实 wire call id；null = 尚未见过非空 id。 */
        var wireCallId: String? = null
            internal set

        /** ToolCallStart 是否已发射（消费侧维护）。 */
        var started = false

        /** ToolCallEnd 是否已发射；已结束的调用不复活。 */
        var ended = false

        /** 展示/回传用的当前对外 id：真实 id 优先，否则 synthetic。 */
        val displayId: String get() = wireCallId ?: syntheticId
    }

    /** 路由结果：[call] 是本次块归属的逻辑调用。 */
    class RouteResult internal constructor(
        val call: Call,
        /** 本次路由新创建的调用（需要消费侧发射 ToolCallStart）。 */
        val created: Boolean,
        /** 本帧把真实 wire id 补绑到该调用（此前 wireCallId == null）。 */
        val assignedId: String?,
    )

    private val calls = LinkedHashMap<String, Call>()
    private val byIndex = HashMap<Int, Call>()
    private val byWireId = HashMap<String, Call>()
    private var sequence = 0
    private var closed = false

    /** 响应内全部逻辑调用（含已结束），按出现顺序。 */
    fun all(): List<Call> = calls.values.toList()

    /**
     * 标记流进入收尾：之后任何路由尝试都是协议错误。
     * [Call.ended] 由消费侧在发射 ToolCallEnd 时标记。
     */
    fun markClosed() {
        closed = true
    }

    /**
     * 把一个 wire tool_call 块路由到逻辑调用，必要时创建新调用。
     *
     * @param index 块内显式 `index`（缺省/非整数 → null）
     * @param wireCallId 块内显式 `id`（absent/null/空白 → null）
     * @param anonymousInPacket 本包内同时无 index 且无可靠 id 的块数
     * @param syntheticId 需要创建调用时使用的临时显示 id
     * @throws ToolStreamProtocolException 身份冲突或信息不足
     */
    fun route(
        index: Int?,
        wireCallId: String?,
        anonymousInPacket: Int,
        syntheticId: String,
    ): RouteResult {
        if (closed) {
            throw ToolStreamProtocolException(
                code = CODE_CALL_AFTER_FINISH,
                message = "tool call element received after the stream finished",
            )
        }

        // 规则 1：显式 index 优先命中既有调用
        index?.let { idx ->
            byIndex[idx]?.let { call ->
                if (call.ended) {
                    throw ToolStreamProtocolException(
                        code = CODE_CALL_AFTER_END,
                        message = "tool call element targets ended call at index=$idx",
                    )
                }
                return bind(call, wireCallId, created = false, via = "index=$idx")
            }
        }

        // 规则 2：无 index（或 index 未见）但有非空 id
        if (wireCallId != null) {
            byWireId[wireCallId]?.let { call ->
                if (index != null && call.index != null && call.index != index) {
                    // 真实 id 已被另一个 index 的调用占用（进行中或已 End 都算）：
                    // 收编会把两个逻辑调用的名称/参数拼进同一 part，必须拒绝。
                    throw ToolStreamProtocolException(
                        code = CODE_ID_REUSED_ACROSS_INDEX,
                        message = "tool call id '$wireCallId' already bound to " +
                            "index=${call.index}, cannot be reused by index=$index" +
                            (if (call.ended) " (owning call already ended)" else ""),
                    )
                }
                if (call.ended) {
                    throw ToolStreamProtocolException(
                        code = CODE_CALL_AFTER_END,
                        message = "tool call element targets ended call with id",
                    )
                }
                if (index != null) {
                    // 该调用本身无 index：首个显式 index 是身份补全，收编后
                    // index/id 双通（不是跨调用合并）。
                    byIndex[index] = call
                }
                return RouteResult(call, created = false, assignedId = null)
            }
            if (index != null) {
                // 新 index + 新 id：常规新调用
                return create(index, wireCallId, syntheticId)
            }
            return routeUnknownId(wireCallId, syntheticId)
        }

        // 规则 3：无可靠 id —— 数组位置不构成身份
        return routeAnonymous(anonymousInPacket, index, syntheticId)
    }

    private fun routeUnknownId(wireCallId: String, syntheticId: String): RouteResult {
        if (calls.isEmpty()) {
            // 整响应尚无调用：允许创建单槽（索引缺失的兼容输入）
            return create(index = null, wireCallId = wireCallId, syntheticId = syntheticId)
        }
        // 迟到真实 id 绑定：仅当整响应恰有一个「未绑定真实 id 且未结束」的
        // 调用时才可能唯一归属；否则（多候选/该 id 是新调用无从判断）拒绝。
        val unboundOpen = calls.values.filter { !it.ended && it.wireCallId == null }
        if (unboundOpen.size == 1) {
            return bind(unboundOpen.single(), wireCallId, created = false, via = "late-id")
        }
        throw ToolStreamProtocolException(
            code = CODE_AMBIGUOUS_IDENTITY,
            message = "unknown tool call id cannot be routed: " +
                "${unboundOpen.size} unbound open calls in flight",
        )
    }

    private fun routeAnonymous(
        anonymousInPacket: Int,
        index: Int?,
        syntheticId: String,
    ): RouteResult {
        if (index != null) {
            // 有显式 index 但未命中既有调用且无 id：创建新调用（index 已知）
            return create(index, wireCallId = null, syntheticId = syntheticId)
        }
        if (anonymousInPacket > 1) {
            throw ToolStreamProtocolException(
                code = CODE_AMBIGUOUS_IDENTITY,
                message = "$anonymousInPacket anonymous tool call elements " +
                    "in one packet cannot be uniquely located",
            )
        }
        if (calls.isEmpty()) {
            return create(index = null, wireCallId = null, syntheticId = syntheticId)
        }
        val open = calls.values.filter { !it.ended }
        if (open.size == 1) {
            return RouteResult(open.single(), created = false, assignedId = null)
        }
        // 账本已有多个调用（或只剩已结束的）：即使本包只剩一个元素也不得
        // 回退到 index0 —— 那是已验证的映射污染源。
        throw ToolStreamProtocolException(
            code = CODE_AMBIGUOUS_IDENTITY,
            message = "anonymous tool call element cannot be routed: " +
                "${open.size} open calls in flight",
        )
    }

    /**
     * 把 [wireCallId] 补绑/校验到 [call]。返回 `assignedId`（仅当本次
     * 从无到有绑定了真实 id；此时消费侧若已发射 Start 需补发
     * `ToolCallIdAssigned`）。
     */
    private fun bind(
        call: Call,
        wireCallId: String?,
        created: Boolean,
        via: String,
    ): RouteResult {
        if (wireCallId == null) return RouteResult(call, created, assignedId = null)
        val bound = call.wireCallId
        if (bound != null) {
            if (bound != wireCallId) {
                throw ToolStreamProtocolException(
                    code = CODE_ID_CONFLICT,
                    message = "tool call at $via changed id from '$bound' to '$wireCallId'",
                )
            }
            return RouteResult(call, created, assignedId = null) // 同 id 重发 no-op
        }
        val owner = byWireId[wireCallId]
        if (owner != null && owner !== call) {
            throw ToolStreamProtocolException(
                code = CODE_ID_CONFLICT,
                message = "tool call id is used by two calls " +
                    "('${owner.streamKey}' and '${call.streamKey}')",
            )
        }
        call.wireCallId = wireCallId
        byWireId[wireCallId] = call
        return RouteResult(call, created, assignedId = wireCallId)
    }

    private fun create(index: Int?, wireCallId: String?, syntheticId: String): RouteResult {
        val call = Call(
            index = index,
            streamKey = "chat-tool-${++sequence}",
            syntheticId = syntheticId,
        )
        calls[call.streamKey] = call
        index?.let { byIndex[it] = call }
        wireCallId?.let { call.wireCallId = it; byWireId[it] = call }
        return RouteResult(call, created = true, assignedId = null)
    }

    private companion object {
        const val CODE_ID_CONFLICT = "tool_call_id_conflict"
        const val CODE_ID_REUSED_ACROSS_INDEX = "tool_call_id_reused_across_index"
        const val CODE_AMBIGUOUS_IDENTITY = "tool_call_ambiguous_identity"
        const val CODE_CALL_AFTER_END = "tool_call_after_end"
        const val CODE_CALL_AFTER_FINISH = "tool_call_after_finish"
    }
}
