package me.rerere.tavern.macro

import me.rerere.tavern.macro.chevrotain.CstNode
import me.rerere.tavern.macro.chevrotain.Grammar
import me.rerere.tavern.macro.chevrotain.GrammarBuilder
import me.rerere.tavern.macro.chevrotain.RecognitionException

/**
 * 宏语法。ST:public/scripts/macros/engine/MacroParser.js:21-153。
 * 规则名、出现序号（OR/OR1/OR2... 的数字）、CST 标签都与上游一致，恢复行为才会一致。
 */
class ParsedDocument(
    /** 输入为空时为 null（上游返回 `cst: null`）。 */
    val cst: CstNode?,
    val lexingErrors: List<LexingError>,
    val parserErrors: List<RecognitionException>,
)

object MacroParser {
    private val grammar: Grammar = with(MacroTokens) {
        val b = GrammarBuilder()

        // ST:MacroParser.js:30 document
        b.rule("document") {
            many(0) {
                or(
                    0,
                    { consume(Plaintext, 0, "plaintext") },
                    { consume(PlaintextOpenBrace, 0, "plaintext") },
                    { subrule("macro") },
                    { consume(MacroStart, 0, "plaintext") },
                )
            }
        }

        // ST:MacroParser.js:42 macro
        b.rule("macro") {
            consume(MacroStart)
            many(0) {
                or(
                    1,
                    { consume(MacroFlags, 0, "flags") },
                    { consume(MacroFilterFlag, 0, "flags") },
                )
            }
            or(
                0,
                { subrule("variableExpr") },
                { subrule("macroBody") },
            )
            consume(MacroEnd)
        }

        // ST:MacroParser.js:68 macroBody
        b.rule("macroBody") {
            or(
                2,
                { consume(MacroDoubleSlash, 0, "Macro.identifier") },
                { consume(MacroIdentifier, 0, "Macro.identifier") },
            )
            option(0) { subrule("arguments") }
        }

        // ST:MacroParser.js:78 variableExpr
        b.rule("variableExpr") {
            or(
                3,
                { consume(VarLocalPrefix, 0, "Var.scope") },
                { consume(VarGlobalPrefix, 0, "Var.scope") },
            )
            consume(VarIdentifier, 0, "Var.identifier")
            option(2) { subrule("variableOperator") }
        }

        // ST:MacroParser.js:92 variableOperator
        b.rule("variableOperator") {
            or(
                4,
                { consume(VarIncrement, 0, "Var.operator") },
                { consume(VarDecrement, 0, "Var.operator") },
                {
                    or(
                        5,
                        { consume(VarNullishCoalescingEquals, 0, "Var.operator") },
                        { consume(VarNullishCoalescing, 0, "Var.operator") },
                        { consume(VarLogicalOrEquals, 0, "Var.operator") },
                        { consume(VarLogicalOr, 0, "Var.operator") },
                        { consume(VarMinusEquals, 0, "Var.operator") },
                        { consume(VarDoubleEquals, 0, "Var.operator") },
                        { consume(VarNotEquals, 0, "Var.operator") },
                        { consume(VarGreaterThanOrEqual, 0, "Var.operator") },
                        { consume(VarGreaterThan, 0, "Var.operator") },
                        { consume(VarLessThanOrEqual, 0, "Var.operator") },
                        { consume(VarLessThan, 0, "Var.operator") },
                        { consume(VarPlusEquals, 0, "Var.operator") },
                        { consume(VarEquals, 0, "Var.operator") },
                    )
                    subrule("variableValue", 0, "Var.value")
                },
            )
        }

        // ST:MacroParser.js:120 variableValue
        b.rule("variableValue") {
            many(2) {
                or(
                    5,
                    { subrule("macro") },
                    { consume(Identifier) },
                    { consume(Unknown) },
                )
            }
        }

        // ST:MacroParser.js:131 arguments
        b.rule("arguments") {
            or(
                0,
                {
                    consume(ArgsDoubleColon, 0, "separator")
                    atLeastOneSep(ArgsDoubleColon, 0) { subrule("argument", 0, "argument") }
                },
                {
                    option(0) { consume(ArgsColon, 0, "separator") }
                    subrule("argumentAllowingColons", 0, "argument")
                },
            )
        }

        // ST:MacroParser.js:162 argument / argumentAllowingColons（共用 validArgumentTokens）
        b.rule("argument") {
            many(0) {
                or(
                    0,
                    { subrule("macro") },
                    { consume(Identifier) },
                    { consume(Unknown) },
                    { consume(ArgsColon) },
                    { consume(ArgsEquals) },
                    { consume(ArgsQuote) },
                )
            }
        }
        b.rule("argumentAllowingColons") {
            atLeastOne(0) {
                or(
                    0,
                    { subrule("macro") },
                    { consume(Identifier) },
                    { consume(Unknown) },
                    { consume(ArgsColon) },
                    { consume(ArgsEquals) },
                    { consume(ArgsQuote) },
                    { consume(ArgsDoubleColon) },
                )
            }
        }

        Grammar(b.build(), "document", maxLookahead = 3)
    }

    /** ST:MacroParser.js:187 parseDocument */
    fun parseDocument(input: String): ParsedDocument {
        if (input.isEmpty()) return ParsedDocument(null, emptyList(), emptyList())
        val lexed = MacroLexer.tokenize(input)
        val result = grammar.parse(lexed.tokens)
        return ParsedDocument(result.cst, lexed.errors, result.errors)
    }
}
