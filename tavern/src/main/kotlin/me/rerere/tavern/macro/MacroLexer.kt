package me.rerere.tavern.macro

import me.rerere.tavern.macro.chevrotain.Token
import me.rerere.tavern.macro.chevrotain.TokenType

/**
 * 宏词法分析器。ST:public/scripts/macros/engine/MacroLexer.js（chevrotain 多模式 Lexer）。
 *
 * 照搬的语义：每个模式按声明顺序取第一个匹配的 token（不是最长匹配）；允许零宽匹配（用来切换模式）；
 * 没有任何 token 匹配时记一条词法错误，并丢弃字符直到当前模式里有某个模式能匹配
 * （chevrotain lexer_public.js 的 resync 逻辑）。偏离：无（宏过滤器 `|` 模式在上游已被注释掉，未移植）。
 */
object MacroTokens {
    val Plaintext = TokenType("Plaintext")
    val PlaintextOpenBrace = TokenType("Plaintext.OpenBrace")

    val MacroStart = TokenType("Macro.Start")
    val MacroFlags = TokenType("Macro.Flag")
    val MacroFilterFlag = TokenType("Macro.FilterFlag")
    val MacroDoubleSlash = TokenType("Macro.DoubleSlash")
    val MacroIdentifier = TokenType("Macro.Identifier")
    val MacroEndOfIdentifier = TokenType("Macro.EndOfIdentifier")
    val MacroBeforeEnd = TokenType("Macro.BeforeEnd")
    val MacroEnd = TokenType("Macro.End")

    val ArgsDoubleColon = TokenType("Args.DoubleColon")
    val ArgsColon = TokenType("Args.Colon")
    val ArgsEquals = TokenType("Args.Equals")
    val ArgsQuote = TokenType("Args.Quote")

    val Identifier = TokenType("Identifier")
    val WhiteSpace = TokenType("WhiteSpace")

    val VarLocalPrefix = TokenType("Var.LocalPrefix")
    val VarGlobalPrefix = TokenType("Var.GlobalPrefix")
    val VarIdentifier = TokenType("Var.Identifier")
    val VarIncrement = TokenType("Var.Increment")
    val VarDecrement = TokenType("Var.Decrement")
    val VarNullishCoalescingEquals = TokenType("Var.NullishCoalescingEquals")
    val VarNullishCoalescing = TokenType("Var.NullishCoalescing")
    val VarLogicalOrEquals = TokenType("Var.LogicalOrEquals")
    val VarLogicalOr = TokenType("Var.LogicalOr")
    val VarMinusEquals = TokenType("Var.MinusEquals")
    val VarDoubleEquals = TokenType("Var.DoubleEquals")
    val VarNotEquals = TokenType("Var.NotEquals")
    val VarGreaterThanOrEqual = TokenType("Var.GreaterThanOrEqual")
    val VarGreaterThan = TokenType("Var.GreaterThan")
    val VarLessThanOrEqual = TokenType("Var.LessThanOrEqual")
    val VarLessThan = TokenType("Var.LessThan")
    val VarPlusEquals = TokenType("Var.PlusEquals")
    val VarEquals = TokenType("Var.Equals")

    val Unknown = TokenType("Unknown")
    val ModePopper = TokenType("ModePopper")
}

class LexingError(val offset: Int, val length: Int)

class LexResult(val tokens: List<Token>, val errors: List<LexingError>)

/** ST:MacroLexer.js:17 MACRO_IDENTIFIER_PATTERN。注册表校验宏名、`{{if}}` 的条件解析用。 */
val MACRO_IDENTIFIER_PATTERN = Regex("^[a-zA-Z][\\w\\-_]*$")

/** ST:MacroLexer.js:26 MACRO_VARIABLE_SHORTHAND_PATTERN。 */
val MACRO_VARIABLE_SHORTHAND_PATTERN = Regex("[a-zA-Z](?:[\\w\\-_]*[\\w])?")

object MacroLexer {
    private enum class Mode {
        PLAINTEXT, MACRO_DEF, MACRO_IDENTIFIER_END, MACRO_ARGS, VAR_IDENTIFIER, VAR_AFTER_IDENTIFIER, VAR_VALUE
    }

    /** 一条词法规则：[match] 返回匹配长度，-1 表示不匹配；pop/push 对应 chevrotain 的 POP_MODE / PUSH_MODE。 */
    private class LexRule(
        val type: TokenType,
        val skipped: Boolean = false,
        val pop: Boolean = false,
        val push: Mode? = null,
        val match: (String, Int) -> Int,
    )

    private fun lit(s: String): (String, Int) -> Int = { text, i -> if (text.startsWith(s, i)) s.length else -1 }

    private fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    private fun wsRun(text: String, i: Int): Int {
        var j = i
        while (j < text.length && isJsSpace(text[j])) j++
        return j - i
    }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isWordChar(c: Char) = isAsciiLetter(c) || c in '0'..'9' || c == '_'

    /** /[a-zA-Z][\w-_]*​/ */
    private val identifier: (String, Int) -> Int = { text, i ->
        if (i < text.length && isAsciiLetter(text[i])) {
            var j = i + 1
            while (j < text.length && (isWordChar(text[j]) || text[j] == '-')) j++
            j - i
        } else -1
    }

    /** /[a-zA-Z](?:[\w\-_]*[\w])?/：贪婪取到最后一个单词字符，结尾的连字符回退。 */
    private val varIdentifier: (String, Int) -> Int = { text, i ->
        if (i < text.length && isAsciiLetter(text[i])) {
            var j = i + 1
            while (j < text.length && (isWordChar(text[j]) || text[j] == '-')) j++
            while (j > i + 1 && text[j - 1] == '-') j--
            j - i
        } else -1
    }

    private val plaintext: (String, Int) -> Int = { text, i ->
        // (?:[^{]|\{(?!\{))+
        var j = i
        while (j < text.length) {
            val c = text[j]
            if (c != '{') j++
            else if (j + 1 >= text.length || text[j + 1] != '{') j++
            else break
        }
        if (j > i) j - i else -1
    }

    private val openBrace: (String, Int) -> Int = { text, i ->
        // \{(?=\{\{)
        if (text.startsWith("{{{", i)) 1 else -1
    }

    private val beforeEnd: (String, Int) -> Int = { text, i -> if (text.startsWith("}}", i)) 0 else -1 }

    private val endOfIdentifier: (String, Int) -> Int = { text, i ->
        // (?:\s+|(?=:{1,2})|(?=[|}]))
        val ws = wsRun(text, i)
        if (ws > 0) ws
        else if (i < text.length && (text[i] == ':' || text[i] == '|' || text[i] == '}')) 0
        else -1
    }

    private val unknown: (String, Int) -> Int = { text, i ->
        // ([^}]|\}(?!\}))
        if (i >= text.length) -1
        else if (text[i] != '}') 1
        else if (i + 1 < text.length && text[i + 1] == '}') -1
        else 1
    }

    private val whitespace: (String, Int) -> Int = { text, i -> val n = wsRun(text, i); if (n > 0) n else -1 }
    private val always: (String, Int) -> Int = { _, _ -> 0 }

    private fun oneOf(chars: String): (String, Int) -> Int = { text, i -> if (i < text.length && chars.indexOf(text[i]) >= 0) 1 else -1 }

    private fun varOp(type: TokenType, s: String) = LexRule(type, pop = true, push = Mode.VAR_VALUE, match = lit(s))

    private val modes: Map<Mode, List<LexRule>> = with(MacroTokens) {
        mapOf(
            Mode.PLAINTEXT to listOf(
                LexRule(Plaintext, match = plaintext),
                LexRule(PlaintextOpenBrace, match = openBrace),
                LexRule(MacroStart, push = Mode.MACRO_DEF, match = lit("{{")),
            ),
            Mode.MACRO_DEF to listOf(
                LexRule(MacroEnd, pop = true, match = lit("}}")),
                LexRule(MacroDoubleSlash, push = Mode.MACRO_ARGS, match = lit("//")),
                LexRule(VarLocalPrefix, push = Mode.VAR_IDENTIFIER, match = lit(".")),
                LexRule(VarGlobalPrefix, push = Mode.VAR_IDENTIFIER, match = lit("$")),
                LexRule(MacroFlags, match = oneOf("!?~#/")),
                LexRule(MacroFilterFlag, match = lit(">")),
                LexRule(WhiteSpace, skipped = true, match = whitespace),
                LexRule(MacroIdentifier, push = Mode.MACRO_IDENTIFIER_END, match = identifier),
                LexRule(ModePopper, skipped = true, pop = true, match = always),
            ),
            Mode.MACRO_IDENTIFIER_END to listOf(
                LexRule(MacroBeforeEnd, skipped = true, pop = true, match = beforeEnd),
                LexRule(MacroEndOfIdentifier, skipped = true, pop = true, push = Mode.MACRO_ARGS, match = endOfIdentifier),
            ),
            Mode.MACRO_ARGS to listOf(
                LexRule(MacroStart, push = Mode.MACRO_DEF, match = lit("{{")),
                LexRule(ArgsDoubleColon, match = lit("::")),
                LexRule(ArgsColon, match = lit(":")),
                LexRule(ArgsEquals, match = lit("=")),
                LexRule(ArgsQuote, match = lit("\"")),
                LexRule(Identifier, match = identifier),
                LexRule(WhiteSpace, skipped = true, match = whitespace),
                LexRule(Unknown, match = unknown),
                LexRule(ModePopper, skipped = true, pop = true, match = always),
            ),
            Mode.VAR_IDENTIFIER to listOf(
                LexRule(WhiteSpace, skipped = true, match = whitespace),
                LexRule(VarIdentifier, pop = true, push = Mode.VAR_AFTER_IDENTIFIER, match = varIdentifier),
                LexRule(ModePopper, skipped = true, pop = true, match = always),
            ),
            Mode.VAR_AFTER_IDENTIFIER to listOf(
                LexRule(WhiteSpace, skipped = true, match = whitespace),
                LexRule(VarIncrement, match = lit("++")),
                LexRule(VarDecrement, match = lit("--")),
                varOp(VarNullishCoalescingEquals, "??="),
                varOp(VarNullishCoalescing, "??"),
                varOp(VarLogicalOrEquals, "||="),
                varOp(VarLogicalOr, "||"),
                varOp(VarMinusEquals, "-="),
                varOp(VarDoubleEquals, "=="),
                varOp(VarNotEquals, "!="),
                varOp(VarGreaterThanOrEqual, ">="),
                varOp(VarGreaterThan, ">"),
                varOp(VarLessThanOrEqual, "<="),
                varOp(VarLessThan, "<"),
                varOp(VarPlusEquals, "+="),
                varOp(VarEquals, "="),
                LexRule(MacroBeforeEnd, skipped = true, pop = true, match = beforeEnd),
                LexRule(ModePopper, skipped = true, pop = true, match = always),
            ),
            Mode.VAR_VALUE to listOf(
                LexRule(MacroStart, push = Mode.MACRO_DEF, match = lit("{{")),
                LexRule(Identifier, match = identifier),
                LexRule(WhiteSpace, skipped = true, match = whitespace),
                LexRule(Unknown, match = unknown),
                LexRule(ModePopper, skipped = true, pop = true, match = always),
            ),
        )
    }

    /** chevrotain lexer_public.js tokenizeInternal，recoveryEnabled 默认为 true。 */
    fun tokenize(text: String): LexResult {
        val tokens = ArrayList<Token>()
        val errors = ArrayList<LexingError>()
        val modeStack = ArrayList<Mode>()
        modeStack.add(Mode.PLAINTEXT)
        var offset = 0
        val length = text.length

        fun currRules() = modes.getValue(modeStack.last())

        while (offset < length) {
            val rules = currRules()
            var chosen: LexRule? = null
            var imageLength = -1
            for (r in rules) {
                val n = r.match(text, offset)
                if (n != -1) {
                    chosen = r
                    imageLength = n
                    break
                }
            }
            if (chosen != null) {
                if (!chosen.skipped) {
                    tokens.add(
                        Token(
                            chosen.type, text.substring(offset, offset + imageLength),
                            offset.toDouble(), (offset + imageLength - 1).toDouble(),
                        ),
                    )
                }
                offset += imageLength
                // handleModes
                if (chosen.pop) {
                    val pushMode = chosen.push
                    if (modeStack.size == 1 && pushMode == null) {
                        errors.add(LexingError(offset - imageLength, imageLength))
                    } else {
                        modeStack.removeLast()
                    }
                    if (pushMode != null) modeStack.add(pushMode)
                } else if (chosen.push != null) {
                    modeStack.add(chosen.push)
                }
            } else {
                // 恢复：丢弃字符直到当前模式里有某个模式能匹配
                val errorStart = offset
                var found = false
                while (!found && offset < length) {
                    offset++
                    for (r in rules) {
                        if (r.match(text, offset) != -1) {
                            found = true
                            break
                        }
                    }
                }
                errors.add(LexingError(errorStart, offset - errorStart))
            }
        }
        return LexResult(tokens, errors)
    }
}
