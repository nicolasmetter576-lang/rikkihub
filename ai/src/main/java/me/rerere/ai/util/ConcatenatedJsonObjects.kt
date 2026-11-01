package me.rerere.ai.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 把「多个完整 JSON 对象首尾相接」的工具参数切回独立对象。
 *
 * 线上常见形态：`{"path":"a"}{"path":"b"}`。来源有两类——模型/推理后端把
 * 并行调用并进一个调用，或上游在同一 index 上续发第二个调用（不带新 id）
 * 被流式账本按协议续接。两者在执行端表现一致：单对象解析报
 * "Expected EOF ... but had {"。
 *
 * 只认严格形态：整段输入恰由 2..[MAX_SEGMENTS] 个顶层对象组成，对象之间
 * 只允许空白和至多一个逗号；每段必须能独立解析为 [JsonObject]。任何其他
 * 残缺（尾部垃圾、截断、顶层非对象、超段数）一律返回 null，交给执行端按
 * 非法参数报错——绝不猜测修复。
 *
 * 完全相同的对象只保留首个：同一调用参数里出现重复对象几乎总是重发
 * 造成的，按两次执行对写文件/命令类工具有副作用风险。
 */
object ConcatenatedJsonObjects {
    const val MAX_SEGMENTS = 8

    data class Result(
        /** 去重后的各段原文（保留模型原始格式），按出现顺序。 */
        val segments: List<String>,
        /** 切出的总段数（含被去重丢弃的）。 */
        val totalSegments: Int,
    ) {
        val duplicatesDropped: Int get() = totalSegments - segments.size
    }

    /**
     * @return 输入是 ≥2 个拼接对象时返回切分结果（去重后可能只剩 1 段）；
     *   单个合法 JSON、空串或任何不符合严格形态的输入返回 null。
     */
    fun split(input: String): Result? {
        val ranges = scanTopLevelObjects(input) ?: return null
        if (ranges.size < 2) return null
        val seen = mutableSetOf<JsonElement>()
        val segments = mutableListOf<String>()
        for (range in ranges) {
            val raw = input.substring(range)
            val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            if (parsed !is JsonObject) return null
            if (seen.add(parsed)) segments += raw
        }
        return Result(segments = segments, totalSegments = ranges.size)
    }

    /**
     * 按字符串/转义感知的括号深度切出顶层 `{...}` 区间。只做定界，合法性由
     * [split] 逐段严格解析确认（括号种类错配等在那一步被拒）。
     */
    private fun scanTopLevelObjects(input: String): List<IntRange>? {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        var commaSinceLast = false
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == ',' -> {
                    // 逗号只能出现在两个对象之间，且至多一个
                    if (ranges.isEmpty() || commaSinceLast) return null
                    commaSinceLast = true
                    i++
                }
                c == '{' -> {
                    if (ranges.size == MAX_SEGMENTS) return null
                    val end = findObjectEnd(input, i) ?: return null
                    ranges += i..end
                    commaSinceLast = false
                    i = end + 1
                }
                else -> return null
            }
        }
        if (commaSinceLast) return null
        return ranges
    }

    /** 从 [start] 处的 `{` 起找到与之配平的结束下标；未闭合返回 null。 */
    private fun findObjectEnd(input: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until input.length) {
            val c = input[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i
                    if (depth < 0) return null
                }
            }
        }
        return null
    }
}
