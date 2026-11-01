package me.rerere.ai.provider.stream

/**
 * 单条逻辑工具调用的流式名称解析器（公开窄 API，无 Android/UI 依赖）。
 *
 * 同一 tool_call 的 `name` 字段在不同 wire 实现里可能是首发、完整重发、
 * 累计快照或真增量分片，逐帧无法区分。本类维护有界候选集，
 * 并并行维护一条 legacy `resolveToolNameDelta` 同款逐字名轨道：
 *
 * - 状态：`candidates`（累计名假设集，初始 `{""}`）与已发射的确认前缀；
 *   每个候选 `c` 对一帧 wireName `w` 有三种解释——真增量（`c+w` 是某个
 *   注册名的前缀）、累计快照（`w` 以 `c` 为前缀且本身是注册名前缀）、
 *   完整重发（`w == c`）。皆不成立的候选淘汰。
 * - **候选全灭不再是协议错误**：注册表无法解释该 wire 名（模型幻觉、沿用
 *   ToolOverride 改名前的旧名等是常见情形）时进入 unresolved 透传模式，
 *   此后 [accept] 仍更新逐字名但一律返回 ""。未注册名永远无法被执行——
 *   原硬失败路径的效果也只是执行端 `tools.find` 落空生成
 *   "Tool X not found" 错误输出反馈模型自我纠正；逐字透传保留这条
 *   自我纠正链路，比协议硬失败的 UX 好，且是安全的（透传名必然未注册）。
 * - [accept] 返回所有候选的最长公共前缀（lcp）相对已发射前缀的**新增
 *   已确认后缀**——单调不退，歧义部分暂存不发射。
 * - [finish] 在终态收敛：候选∩注册表恰一个 → 返回剩余已确认增量（不是
 *   完整名）；**≥2 个注册名解释** → `name_ambiguous`（协议内不可判定，
 *   猜错会执行错工具，保持 fail-closed）；零候选/unresolved → 逐字名
 *   兜底：若逐字名补全后恰落在注册表上而候选集未能唯一确认它 →
 *   `name_unresolvable`（宁可报错也不执行可能错误的注册工具），否则
 *   返回逐字名尚未发射的尾部。
 *
 * 例（R={foo,foofoobar}）：`foo`→`foo` 后候选 {foo, foofoo}，第三片 `bar`
 * 消歧为 `foofoobar`；`foo`→`foo` 后结束则唯一完整候选 `foo`。
 * R={foo,foofoo} 下 `foo`→`foo` 终态双候选 → 歧义错误。
 * R={search_web} 下单发 `unknown_tool` → 逐字透传 `unknown_tool`。
 *
 * 有界（fail-closed，防异常响应扩大内存）：单帧 wireName >512、候选名 >256、
 * 候选数 >16、逐字名 >1024 均抛 [ToolStreamProtocolException]；finish 后
 * 再收到名称同样抛错。
 *
 * **只在出现工具调用时实例化**：纯文本响应不应触碰本类；注册表为空但有
 * 工具调用时逐字名原样透传（由执行端报 not found）。
 */
class ToolNameResolver(
    /** 本轮请求实际下发（preflight 后最终 wire 表）的工具名集合。 */
    private val knownToolNames: Set<String>,
) {
    private var candidates: LinkedHashSet<String> = linkedSetOf("")
    private var emittedPrefix: String = ""

    /**
     * legacy `resolveToolNameDelta` 同款逐字累计名轨道，与候选集并行维护：
     * `resolved = if (!w.startsWith(literal)) w
     *            else if (known.any { it.startsWith(literal + w) } && w !in known) w
     *            else w.removePrefix(literal)`，然后 `literal += resolved`。
     * 候选集死光后它仍能按同一启发式收束回 wire 名的字面序列（首发/重发/
     * 快照/分片统一收敛），是未注册名透传的依据。
     */
    private var literal: String = ""

    /** true = 候选全灭后进入逐字透传模式（accept 不再发射、不再拒绝）。 */
    private var unresolved = false
    private var finished = false

    /** 注册名 + 其全部前缀的闭包缓存（R 固定，构建一次即可）。 */
    private var prefixClosure: Set<String>? = null

    /**
     * 接收一帧 wire 名，返回本帧新确认的名称增量（可拼到此前所有返回值之后）。
     * 空 `wireName` 为 no-op，返回 ""。unresolved 透传模式下只维护逐字名，
     * 返回 ""。
     *
     * @throws ToolStreamProtocolException 超界（帧 >512 / 逐字名 >1024 /
     *   候选数 >16）或 finish 后仍收到名称
     */
    fun accept(wireName: String): String {
        if (finished) {
            throw ToolStreamProtocolException(
                code = CODE_NAME_AFTER_FINISH,
                message = "tool name fragment received after resolver finished",
            )
        }
        if (wireName.isEmpty()) return ""
        if (wireName.length > MAX_WIRE_NAME_LEN) {
            throw ToolStreamProtocolException(
                code = CODE_NAME_TOO_LONG,
                message = "tool name fragment exceeds $MAX_WIRE_NAME_LEN chars " +
                    "(got ${wireName.length})",
            )
        }

        // legacy 逐字名轨道：无论候选死活都先收束本帧，未注册名透传靠它。
        val resolved = when {
            !wireName.startsWith(literal) -> wireName
            knownToolNames.any { it.startsWith(literal + wireName) } &&
                wireName !in knownToolNames -> wireName
            else -> wireName.removePrefix(literal)
        }
        literal += resolved
        if (literal.length > MAX_LITERAL_LEN) {
            throw ToolStreamProtocolException(
                code = CODE_NAME_TOO_LONG,
                message = "tool name exceeds $MAX_LITERAL_LEN chars",
            )
        }

        // unresolved 透传：候选全灭后只维护逐字名，不再发射增量。
        if (unresolved) return ""

        val prefixes = prefixClosure()
        val next = LinkedHashSet<String>()
        for (candidate in candidates) {
            // 真增量分片: c+w 是某注册名的前缀
            val delta = candidate + wireName
            if (delta.length <= MAX_CANDIDATE_LEN && delta in prefixes) {
                next += delta
            }
            // 累计名快照: w 以 c 为前缀且 w 本身是某注册名的前缀
            if (wireName.length <= MAX_CANDIDATE_LEN &&
                wireName.startsWith(candidate) && wireName in prefixes
            ) {
                next += wireName
            }
            // 完整名重发: w == c (c 非空由 w 非空推得)
            if (wireName == candidate) {
                next += candidate
            }
        }
        if (next.isEmpty()) {
            // 候选全灭 = 注册表无法解释该 wire 名（幻觉/旧名）。不抛协议错误：
            // 进入逐字透传模式，最终把字面名交给执行端报 not found 反馈模型。
            unresolved = true
            candidates = LinkedHashSet()
            return ""
        }
        if (next.size > MAX_CANDIDATES) {
            throw ToolStreamProtocolException(
                code = CODE_NAME_TOO_MANY_CANDIDATES,
                message = "tool name ambiguity exceeds $MAX_CANDIDATES candidates",
            )
        }
        candidates = next
        val lcp = longestCommonPrefix(next)
        // lcp 必然以 emittedPrefix 为前缀（每个幸存候选都以旧 lcp 为前缀），
        // 确认前缀单调增长，绝不回退。
        val increment = lcp.removePrefix(emittedPrefix)
        emittedPrefix = lcp
        return increment
    }

    /**
     * 流结束时收敛名称，返回**剩余的已确认增量**（不是完整名）。
     * 唯一完整候选时尚有未发射尾部则返回尾部；候选∩注册表恰一个且已全部
     * 发射时返回 ""；零候选/unresolved 时返回逐字名尚未发射的尾部
     * （未注册名透传，执行端报 not found）。
     *
     * @throws ToolStreamProtocolException 终态多候选歧义（可能执行错工具）
     *   或逐字名恰拼回一个候选集未能确认的注册名
     */
    fun finish(): String {
        if (finished) return ""
        finished = true
        val finals = candidates.filter { it in knownToolNames }
        val resolved = when {
            finals.size > 1 -> throw ToolStreamProtocolException(
                code = CODE_NAME_AMBIGUOUS,
                message = "tool name stream ended with ${finals.size} registered " +
                    "interpretations (prefix '${emittedPrefix.take(32)}')",
            )
            finals.size == 1 -> finals.single()
            else -> {
                // 零候选/unresolved：逐字名兜底透传。emittedPrefix 通常是
                // literal 的前缀（候选曾确认的公共前缀）；万一不是，保留已
                // 确认前缀在前、逐字名随后，保证流出名称序列完整不退化。
                val literalName =
                    if (literal.startsWith(emittedPrefix)) literal
                    else emittedPrefix + literal
                if (literalName in knownToolNames) {
                    // 逐字名恰好拼回一个注册名但候选集未能唯一确认它——
                    // 执行它可能选错工具，宁可协议错误也不猜。
                    throw ToolStreamProtocolException(
                        code = CODE_NAME_UNRESOLVABLE,
                        message = "tool name resolved literally to a registered " +
                            "name without a confirming hypothesis " +
                            "(emitted '${emittedPrefix.length}' chars)",
                    )
                }
                literalName
            }
        }
        // resolved 必然以 emittedPrefix 为前缀（lcp 性质，或上面显式拼接）。
        val tail = resolved.removePrefix(emittedPrefix)
        emittedPrefix = resolved
        return tail
    }

    /** 注册名 + 其全部前缀的闭包（候选判定的合法累计名域）。 */
    private fun prefixClosure(): Set<String> {
        return prefixClosure ?: run {
            val built = HashSet<String>()
            for (name in knownToolNames) {
                for (len in 1..name.length) built += name.substring(0, len)
            }
            prefixClosure = built
            built
        }
    }

    private fun longestCommonPrefix(names: Collection<String>): String {
        if (names.isEmpty()) return ""
        val first = names.first()
        var end = first.length
        for (name in names) {
            end = minOf(end, name.length)
            var i = 0
            while (i < end && name[i] == first[i]) i++
            end = i
        }
        return first.substring(0, end)
    }

    private companion object {
        const val MAX_CANDIDATES = 16
        const val MAX_CANDIDATE_LEN = 256
        const val MAX_WIRE_NAME_LEN = 512
        const val MAX_LITERAL_LEN = 1024

        const val CODE_NAME_AMBIGUOUS = "tool_name_ambiguous"
        const val CODE_NAME_UNRESOLVABLE = "tool_name_unresolvable"
        const val CODE_NAME_TOO_LONG = "tool_name_too_long"
        const val CODE_NAME_TOO_MANY_CANDIDATES = "tool_name_too_many_candidates"
        const val CODE_NAME_AFTER_FINISH = "tool_name_after_finish"
    }
}
