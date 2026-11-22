package me.rerere.rikkihub.data.ai.tavern

import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.tavern.prompt.InjectionPosition
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptMarkers

/**
 * 把本回合选用的提示词库条目并进助手的布局。
 *
 * 相对条目按锚点落位（锚点是 rikkihub 自有信息，见 [LibraryEntryMeta]）：
 * - `before_system`：世界书「前」之后、提示词条目之前；
 * - `after_system`：整个系统块（提示词 + `rikkihubContext`）之后；
 * - `before_history`：聊天历史之前，保留条目自己的角色。
 *
 * 同一锚点内按 `injection_order` 降序（旧「优先级」），相同的保持库里的顺序。
 * 聊天内（绝对）条目放在布局末尾，组装器按深度和顺序自己归位。
 */
internal object LibraryLayout {
    fun merge(layout: List<PromptEntry>, library: List<PromptEntry>): List<PromptEntry> {
        if (library.isEmpty()) return layout
        val ordered = library.sortedByDescending { it.injectionOrder ?: 0 }
        val absolute = ordered.filter { it.injectionPosition == InjectionPosition.ABSOLUTE }
        val relative = ordered - absolute.toSet()

        val result = layout.toMutableList()
        fun insertAfter(identifier: String, fallback: () -> Int, entries: List<PromptEntry>) {
            if (entries.isEmpty()) return
            val at = result.indexOfFirst { it.identifier == identifier }.let { if (it >= 0) it + 1 else fallback() }
            result.addAll(at.coerceIn(0, result.size), entries)
        }

        fun anchored(anchor: String) = relative.filter { (it.libraryAnchor ?: LibraryEntryMeta.AFTER_SYSTEM) == anchor }

        // after_system 先插，这样它的位置不受 before_system 的影响；三组锚点互不相邻，顺序无关紧要
        insertAfter(
            PromptMarkers.RIKKIHUB_CONTEXT,
            fallback = { result.indexOfFirst { it.identifier == "worldInfoAfter" }.let { i -> if (i >= 0) i else historyIndex(result) } },
            entries = anchored(LibraryEntryMeta.AFTER_SYSTEM),
        )
        insertAfter(
            "worldInfoBefore",
            fallback = { 0 },
            entries = anchored(LibraryEntryMeta.BEFORE_SYSTEM),
        )
        anchored(LibraryEntryMeta.BEFORE_HISTORY).takeIf { it.isNotEmpty() }?.let { entries ->
            result.addAll(historyIndex(result), entries)
        }
        result.addAll(absolute)
        return result
    }

    /**
     * 只有空白的文本条目不发（旧版分块提示词就是这样：开着但内容空白的块被跳过）。
     * 酒馆里空白内容会作为一条空白消息发出，对任何提供商都没有意义，有的还会拒收。
     */
    fun dropBlankText(layout: List<PromptEntry>): List<PromptEntry> = layout.map {
        if (it.marker != true && it.content != null && it.content!!.isBlank() && it.content!!.isNotEmpty()) it.copy(content = "") else it
    }

    private fun historyIndex(entries: List<PromptEntry>): Int =
        entries.indexOfFirst { it.identifier == PromptMarkers.CHAT_HISTORY }.let { if (it >= 0) it else entries.size }
}
