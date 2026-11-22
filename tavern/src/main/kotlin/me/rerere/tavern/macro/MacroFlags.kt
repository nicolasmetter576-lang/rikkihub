package me.rerere.tavern.macro

/**
 * 宏执行标志位。ST:public/scripts/macros/engine/MacroFlags.js。
 * `!`/`?`/`~`/`>` 上游标了未实现，只解析；`/` 闭合块、`#` 保留空白已实现。
 */
data class MacroFlags(
    val immediate: Boolean = false,
    val delayed: Boolean = false,
    val reevaluate: Boolean = false,
    val filter: Boolean = false,
    val closingBlock: Boolean = false,
    val preserveWhitespace: Boolean = false,
    val raw: List<String> = emptyList(),
)

object MacroFlagType {
    const val IMMEDIATE = "!"
    const val DELAYED = "?"
    const val REEVALUATE = "~"
    const val FILTER = ">"
    const val CLOSING_BLOCK = "/"
    const val PRESERVE_WHITESPACE = "#"
}

/** ST:MacroFlags.js:150 createEmptyFlags */
fun createEmptyFlags(): MacroFlags = MacroFlags()

/** ST:MacroFlags.js:168 parseFlags */
fun parseFlags(flagSymbols: List<String>): MacroFlags {
    var flags = createEmptyFlags()
    for (symbol in flagSymbols) {
        flags = when (symbol) {
            MacroFlagType.IMMEDIATE -> flags.copy(immediate = true)
            MacroFlagType.DELAYED -> flags.copy(delayed = true)
            MacroFlagType.REEVALUATE -> flags.copy(reevaluate = true)
            MacroFlagType.FILTER -> flags.copy(filter = true)
            MacroFlagType.CLOSING_BLOCK -> flags.copy(closingBlock = true)
            MacroFlagType.PRESERVE_WHITESPACE -> flags.copy(preserveWhitespace = true)
            else -> flags
        }
    }
    return flags.copy(raw = flagSymbols.toList())
}
