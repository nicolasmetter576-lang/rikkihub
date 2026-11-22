package me.rerere.tavern.macro

import me.rerere.tavern.macro.chevrotain.CstNode
import me.rerere.tavern.macro.chevrotain.Token

/** 与 tavern/oracle/macro/cst_dump.mjs 输出格式一致的紧凑文本。 */
internal object CstDump {
    private fun n(x: Double): String = if (x.isNaN()) "NaN" else x.toLong().toString()

    fun token(t: Token): String = "T(${t.type.name}|${t.image}|${n(t.startOffset)}|${n(t.endOffset)}${if (t.isInsertedInRecovery) "|ins" else ""})"

    fun node(node: CstNode): String {
        val sb = StringBuilder("<${node.name} ${n(node.startOffset)}:${n(node.endOffset)}${if (node.recoveredNode) "!" else ""}")
        for ((key, items) in node.children) {
            sb.append(" ").append(key).append("[")
            sb.append(items.joinToString(",") { if (it is CstNode) node(it) else token(it as Token) })
            sb.append("]")
        }
        return sb.append(">").toString()
    }

    fun tokens(text: String): String = MacroLexer.tokenize(text).tokens.joinToString("") { token(it) }
    fun lexErrors(text: String): String = MacroLexer.tokenize(text).errors.joinToString(",") { "${it.offset}+${it.length}" }
}
