package me.rerere.tavern.core

/**
 * 世界书激活的产出，对应 ST:public/scripts/world-info.js:892-915 的 WIPromptResult。
 * 由 `worldinfo/` 产生，`prompt/` 消费。
 */
data class WorldInfoPromptResult(
    val worldInfoString: String = "",
    val worldInfoBefore: String = "",
    val worldInfoAfter: String = "",
    /** 示例对话上/下（EMTop/EMBottom）。 */
    val worldInfoExamples: List<WorldInfoExampleEntry> = emptyList(),
    /** @Depth 条目，按 (depth, role) 合并。 */
    val worldInfoDepth: List<WorldInfoDepthEntry> = emptyList(),
    /** 作者注释上/下（ANTop/ANBottom）。 */
    val anBefore: List<String> = emptyList(),
    val anAfter: List<String> = emptyList(),
    /** outlet 名字 -> 内容，供宏 `{{outlet::名字}}` 取用。 */
    val outletEntries: Map<String, List<String>> = emptyMap(),
)

/** ST 的 `wi_anchor_position`：before=0, after=1。 */
data class WorldInfoExampleEntry(val before: Boolean, val content: String)

data class WorldInfoDepthEntry(val depth: Int, val role: PromptRole, val entries: List<String>)
