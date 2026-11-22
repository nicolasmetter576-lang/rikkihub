package me.rerere.rikkihub.data.ai.tavern

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.HistoryProbe
import me.rerere.tavern.prompt.PromptHost
import me.rerere.tavern.token.TokenCounter

/**
 * 历史消息交给组装器时加在文本前面的标记。带这个前缀的文本是「原样使用」的消息正文：
 * 宿主不求宏（用户输入在发送时已经求过一次并存进消息，AI 的回复从不求宏），也不把前缀算进 token。
 * 否则 `\{`、`<USER>` 之类会被改写，LaTeX 和代码内容受损。
 */
internal const val OPAQUE_PREFIX = "\u0001"

/**
 * 一个回合内冻结的求值与计数。
 *
 * 宏有副作用（变量写入、`{{random}}`、`{{time}}`），而 GenerationLoop 在每个工具步骤、每次网络重试都会重新组装请求。
 * 同一段文本在本回合里只求一次，之后一律取第一次的结果——请求之间的前缀因此逐字稳定，提示词缓存才命中。
 */
internal class FrozenPromptHost(
    private val evaluate: (String) -> String,
    /** 本回合开始时的历史，供提供商约束判断「带工具的助手消息」。组装用的历史长度可以更长（工具步骤追加）。 */
    @Volatile var history: List<UIMessage> = emptyList(),
    /** 组装预算有限时要真实计数；预算不设限（默认）时历史用估算，省掉每步给全部历史分词。 */
    private val exactHistoryTokens: Boolean,
) : PromptHost {
    private val memo = HashMap<String, String>()
    private val tokenCache = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > TOKEN_CACHE_SIZE
    }

    override fun substitute(text: String): String {
        if (text.isEmpty() || text.startsWith(OPAQUE_PREFIX)) return text
        synchronized(memo) { memo[text]?.let { return it } }
        // 求值放在锁外：宏引擎会递归求值，求值本身不应持有这把锁
        val result = evaluate(text)
        return synchronized(memo) { memo.getOrPut(text) { result } }
    }

    override fun countTokens(text: String): Int {
        if (text.startsWith(OPAQUE_PREFIX)) {
            val body = text.substring(OPAQUE_PREFIX.length)
            return if (exactHistoryTokens) cachedCount(body) else body.length / 3 + 1
        }
        return cachedCount(text)
    }

    private fun cachedCount(text: String): Int {
        synchronized(tokenCache) { tokenCache[text]?.let { return it } }
        val count = TokenCounter.count(text)
        synchronized(tokenCache) { tokenCache[text] = count }
        return count
    }

    /**
     * 提供商约束：注入块不能落在「用户消息 → 带工具调用的助手消息」之间
     * （deepseek 等要求二者相邻，插进去会报错或打断推理）。往前挪到用户消息之前。
     */
    override fun adjustHistoryInsertIndex(index: Int, items: List<HistoryProbe>): Int {
        var i = index.coerceIn(0, items.size)
        while (i > 0) {
            val previous = items.getOrNull(i - 1)
            val current = items.getOrNull(i)
            val afterUser = previous?.role == PromptRole.USER
            val beforeToolAssistant = current != null && current.originalIndex >= 0 &&
                history.getOrNull(current.originalIndex)?.let { it.role == MessageRole.ASSISTANT && it.getTools().isNotEmpty() } == true
            if (afterUser && beforeToolAssistant) i-- else break
        }
        return i
    }

    private companion object {
        const val TOKEN_CACHE_SIZE = 4096
    }
}
