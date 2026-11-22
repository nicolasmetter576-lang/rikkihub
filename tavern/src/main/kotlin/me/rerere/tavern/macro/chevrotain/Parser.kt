package me.rerere.tavern.macro.chevrotain

import java.util.concurrent.ConcurrentHashMap

// 对应 chevrotain parse/parser/traits/{recognizer_engine,recoverable,tree_builder,lexer_adapter}.js 与 parse/cst/cst.js
// 以 recoveryEnabled=true、nodeLocationTracking="full"、maxLookahead=3 的配置运行（上游 MacroParser.js 的设置）。

class CstNode(val name: String) {
    /** 子元素是 [Token] 或 [CstNode]；LinkedHashMap 保持插入顺序（上游遍历 Object.keys 依赖它）。 */
    val children = LinkedHashMap<String, MutableList<Any>>()
    var recoveredNode = false
    var startOffset = Double.NaN
    var endOffset = Double.NaN

    fun tokens(key: String): List<Token> = (children[key] ?: emptyList<Any>()).filterIsInstance<Token>()
    fun nodes(key: String): List<CstNode> = (children[key] ?: emptyList<Any>()).filterIsInstance<CstNode>()
}

open class RecognitionException(message: String) : RuntimeException(message, null, false, false) {
    var partialCstResult: CstNode? = null
}

class MismatchedTokenException(message: String) : RecognitionException(message)
class NoViableAltException(message: String) : RecognitionException(message)
class EarlyExitException(message: String) : RecognitionException(message)
class NotAllInputParsedException(message: String) : RecognitionException(message)
private class InRuleRecoveryException : RuntimeException("sad sad panda", null, false, false)

class ParseResult(val cst: CstNode, val errors: List<RecognitionException>)

/** 语法 + 惰性缓存的静态分析结果（线程安全）。 */
class Grammar(val rules: Map<String, Rule>, val topRule: String, val maxLookahead: Int = 3) {
    internal val resyncFollows: Map<String, List<TokenType>> by lazy {
        val all = HashMap<String, List<TokenType>>()
        rules.values.forEach { all.putAll(ResyncFollowsWalker(it).startWalking()) }
        all
    }
    private val orPathsCache = ConcurrentHashMap<String, List<List<List<TokenType>>>>()
    private val optPathsCache = ConcurrentHashMap<String, List<List<TokenType>>>()
    private val afterRepCache = ConcurrentHashMap<String, AfterRepetitionInfo>()

    internal fun orPaths(rule: String, idx: Int) = orPathsCache.getOrPut("$rule/$idx") {
        getLookaheadPathsForOr(idx, rules.getValue(rule), maxLookahead)
    }

    /** 只取 "重复体内" 那一支的路径（上游 lookAheadPaths[0]）。 */
    internal fun optPaths(rule: String, type: ProdType, idx: Int) = optPathsCache.getOrPut("$rule/$type/$idx") {
        getLookaheadPathsForOptionalProd(idx, rules.getValue(rule), type, maxLookahead)[0]
    }

    internal fun afterRep(rule: String, kind: NextTerminalAfterWalker.Kind, idx: Int) = afterRepCache.getOrPut("$rule/$kind/$idx") {
        NextTerminalAfterWalker(rules.getValue(rule), kind, idx).startWalking()
    }

    fun parse(tokens: List<Token>): ParseResult = ParserRun(this, tokens).run()
}

private class ParserRun(private val g: Grammar, tokens: List<Token>) {
    private val tokVector = ArrayList<Token>(tokens.size + g.maxLookahead + 1)
    private val tokVectorLength = tokens.size
    private var currIdx = -1
    private val errors = ArrayList<RecognitionException>()
    private val ruleStack = ArrayList<String>()
    private val occurrenceStack = ArrayList<Int>()
    private val cstStack = ArrayList<CstNode>()

    init {
        tokVector.addAll(tokens)
    }

    fun run(): ParseResult {
        // onBeforeParse：补 maxLookahead + 1 个 EOF 哨兵
        repeat(g.maxLookahead + 1) { tokVector.add(END_OF_FILE) }
        val cst = invokeRule(g.rules.getValue(g.topRule), 0)
        // onAfterParse
        if (!(LA(1).type === EOF_TYPE)) {
            errors.add(NotAllInputParsedException("Redundant input, expecting EOF but found: ${LA(1).image}"))
        }
        return ParseResult(cst, errors)
    }

    // ---- lexer_adapter.js ----
    private fun laFast(howMuch: Int): Token = tokVector[currIdx + howMuch]

    private fun LA(howMuch: Int): Token {
        val sought = currIdx + howMuch
        return if (sought < 0 || tokVectorLength <= sought) END_OF_FILE else tokVector[sought]
    }

    private fun skipToken(): Token = if (currIdx <= tokVectorLength - 2) {
        currIdx++
        laFast(1)
    } else END_OF_FILE

    // ---- 规则调用 ----
    private fun invokeRule(rule: Rule, idxInCallingRule: Int): CstNode {
        occurrenceStack.add(idxInCallingRule)
        ruleStack.add(rule.name)
        cstStack.add(CstNode(rule.name))
        try {
            execDefinition(rule.definition)
            return cstStack.last()
        } catch (e: RecognitionException) {
            return invokeRuleCatch(e)
        } finally {
            ruleStack.removeLast()
            occurrenceStack.removeLast()
            cstStack.removeLast()
        }
    }

    private fun invokeRuleCatch(e: RecognitionException): CstNode {
        val reSyncTokType = findReSyncTokenType()
        val partial = cstStack.last()
        if (isInCurrentRuleReSyncSet(reSyncTokType)) {
            reSyncTo(reSyncTokType)
            partial.recoveredNode = true
            return partial
        }
        partial.recoveredNode = true
        e.partialCstResult = partial
        throw e
    }

    private fun execDefinition(defn: List<Prod>) {
        for (p in defn) {
            when (p) {
                is Terminal -> consume(p)
                is NonTerminal -> subrule(p)
                is Option -> if (optionalLookahead(ProdType.OPTION, p.idx)) execDefinition(p.definition)
                is Repetition -> many(p)
                is RepetitionMandatory -> atLeastOne(p)
                is RepetitionMandatoryWithSeparator -> atLeastOneSep(p)
                is Alternation -> or(p)
                is Alternative -> execDefinition(p.definition)
                is Rule -> error("unexpected rule")
            }
        }
    }

    private val currRuleName get() = ruleStack.last()

    // ---- 前瞻 ----
    private fun optionalLookahead(type: ProdType, idx: Int): Boolean {
        val paths = g.optPaths(currRuleName, type, idx)
        return paths.any { path -> path.indices.all { laFast(it + 1).type === path[it] } }
    }

    private fun or(p: Alternation) {
        val alts = g.orPaths(currRuleName, p.idx)
        var chosen = -1
        outer@ for (t in alts.indices) {
            for (path in alts[t]) {
                if (path.indices.all { laFast(it + 1).type === path[it] }) {
                    chosen = t
                    break@outer
                }
            }
        }
        if (chosen < 0) throw NoViableAltException("Expecting one of the alternatives but found: ${laFast(1)}").also { errors.add(it) }
        execDefinition(p.definition[chosen].definition)
    }

    private fun doSingleRepetition(defn: List<Prod>): Boolean {
        val before = currIdx
        execDefinition(defn)
        return currIdx > before
    }

    private fun many(p: Repetition) {
        var notStuck = true
        while (optionalLookahead(ProdType.REPETITION, p.idx) && notStuck) {
            notStuck = doSingleRepetition(p.definition)
        }
        attemptInRepetitionRecovery(
            { many(p) }, { optionalLookahead(ProdType.REPETITION, p.idx) },
            NextTerminalAfterWalker.Kind.MANY, p.idx, notStuck,
        )
    }

    private fun atLeastOne(p: RepetitionMandatory) {
        val look = { optionalLookahead(ProdType.REPETITION_MANDATORY, p.idx) }
        if (look()) {
            var notStuck = doSingleRepetition(p.definition)
            while (look() && notStuck) notStuck = doSingleRepetition(p.definition)
        } else {
            throw EarlyExitException("Expecting at least one iteration").also { errors.add(it) }
        }
        attemptInRepetitionRecovery({ atLeastOne(p) }, look, NextTerminalAfterWalker.Kind.AT_LEAST_ONE, p.idx, null)
    }

    private fun atLeastOneSep(p: RepetitionMandatoryWithSeparator) {
        if (optionalLookahead(ProdType.REPETITION_MANDATORY_WITH_SEPARATOR, p.idx)) {
            execDefinition(p.definition)
            val sepLook = { laFast(1).type === p.separator }
            while (sepLook()) {
                consumeType(p.separator, 0, null)
                execDefinition(p.definition)
            }
            attemptSepRecovery(p, sepLook)
        } else {
            throw EarlyExitException("Expecting at least one iteration").also { errors.add(it) }
        }
    }

    /** repetitionSepSecondInternal */
    private fun repetitionSepSecond(p: RepetitionMandatoryWithSeparator, sepLook: () -> Boolean) {
        while (sepLook()) {
            consumeType(p.separator, 0, null)
            execDefinition(p.definition)
        }
        attemptSepRecovery(p, sepLook)
    }

    private fun attemptSepRecovery(p: RepetitionMandatoryWithSeparator, sepLook: () -> Boolean) {
        attemptInRepetitionRecovery(
            { repetitionSepSecond(p, sepLook) }, sepLook,
            NextTerminalAfterWalker.Kind.AT_LEAST_ONE_SEP, p.idx, null,
        )
    }

    // ---- 终结符与子规则 ----
    private fun consume(t: Terminal) = consumeType(t.type, t.idx, t.label)

    private fun consumeType(type: TokenType, idx: Int, label: String?) {
        var consumed: Token
        try {
            val next = laFast(1)
            if (next.type === type) {
                currIdx++
                consumed = next
            } else {
                throw MismatchedTokenException("Expecting token of type --> $type <-- but found --> '${next.image}' <--")
                    .also { errors.add(it) }
            }
        } catch (e: MismatchedTokenException) {
            consumed = consumeInternalRecovery(type, idx, e)
        }
        cstPostTerminal(label ?: type.name, consumed)
    }

    private fun subrule(p: NonTerminal) {
        val key = p.label ?: p.ruleName
        try {
            val result = invokeRule(p.referencedRule, p.idx)
            cstPostNonTerminal(result, key)
        } catch (e: RecognitionException) {
            e.partialCstResult?.let {
                cstPostNonTerminal(it, key)
                e.partialCstResult = null
            }
            throw e
        }
    }

    // ---- 恢复（recoverable.js） ----
    private fun consumeInternalRecovery(tokType: TokenType, idx: Int, original: MismatchedTokenException): Token {
        val follows = getFollowsForInRuleRecovery(tokType, idx)
        try {
            return tryInRuleRecovery(tokType, follows)
        } catch (e: InRuleRecoveryException) {
            throw original
        }
    }

    private fun tryInRuleRecovery(expected: TokenType, follows: List<TokenType>): Token {
        if (canRecoverWithSingleTokenInsertion(follows)) {
            return Token(expected, "", Double.NaN, Double.NaN, isInsertedInRecovery = true)
        }
        if (canRecoverWithSingleTokenDeletion(expected)) {
            val nextTok = skipToken()
            currIdx++
            return nextTok
        }
        throw InRuleRecoveryException()
    }

    private fun canRecoverWithSingleTokenInsertion(follows: List<TokenType>): Boolean {
        if (follows.isEmpty()) return false
        val mismatched = laFast(1)
        return follows.any { it === mismatched.type }
    }

    private fun canRecoverWithSingleTokenDeletion(expected: TokenType): Boolean = LA(2).type === expected

    private fun canPerformInRuleRecovery(expected: TokenType, follows: List<TokenType>) =
        canRecoverWithSingleTokenInsertion(follows) || canRecoverWithSingleTokenDeletion(expected)

    private fun getFollowsForInRuleRecovery(tokType: TokenType, tokIdxInRule: Int): List<TokenType> {
        val path = GrammarPath(ruleStack.toList(), occurrenceStack.toList(), tokType, tokIdxInRule)
        return NextAfterTokenWalker(g.rules.getValue(path.ruleStack[0]), path).startWalking()
    }

    private fun attemptInRepetitionRecovery(
        reapply: () -> Unit,
        lookahead: () -> Boolean,
        kind: NextTerminalAfterWalker.Kind,
        occurrence: Int,
        notStuck: Boolean?,
    ) {
        val info = g.afterRep(currRuleName, kind, occurrence)
        var expectTok = info.token
        var nextTokIdx = info.occurrence
        if (ruleStack.size - 1 == 0 && info.isEndOfRule == true && expectTok == null) {
            expectTok = EOF_TYPE
            nextTokIdx = 1
        }
        if (expectTok == null || nextTokIdx == null) return
        if (shouldInRepetitionRecoveryBeTried(expectTok, nextTokIdx, notStuck)) {
            tryInRepetitionRecovery(reapply, lookahead, expectTok)
        }
    }

    private fun shouldInRepetitionRecoveryBeTried(expect: TokenType, nextTokIdx: Int, notStuck: Boolean?): Boolean {
        if (notStuck == false) return false
        if (laFast(1).type === expect) return false
        if (canPerformInRuleRecovery(expect, getFollowsForInRuleRecovery(expect, nextTokIdx))) return false
        return true
    }

    private fun tryInRepetitionRecovery(reapply: () -> Unit, lookahead: () -> Boolean, expected: TokenType) {
        val reSyncTokType = findReSyncTokenType()
        val saved = currIdx
        var passed = false
        var currToken = laFast(1)
        while (!passed) {
            if (currToken.type === expected) {
                return
            } else if (lookahead()) {
                reapply()
                return
            } else if (currToken.type === reSyncTokType) {
                passed = true
            } else {
                currToken = skipToken()
            }
        }
        currIdx = saved
    }

    private fun followSetOf(idxInStack: Int): List<TokenType> {
        if (idxInStack == 0) return listOf(EOF_TYPE)
        val name = ruleStack[idxInStack] + occurrenceStack[idxInStack] + IN + ruleStack[idxInStack - 1]
        return g.resyncFollows[name] ?: emptyList()
    }

    private fun isInCurrentRuleReSyncSet(tokType: TokenType): Boolean = followSetOf(ruleStack.size - 1).any { it === tokType }

    private fun findReSyncTokenType(): TokenType {
        val all = ruleStack.indices.flatMap { followSetOf(it) }
        var nextToken = laFast(1)
        var k = 2
        while (true) {
            val m = all.firstOrNull { it === nextToken.type }
            if (m != null) return m
            nextToken = LA(k)
            k++
        }
    }

    private fun reSyncTo(tokType: TokenType) {
        var nextTok = laFast(1)
        while (nextTok.type !== tokType) {
            nextTok = skipToken()
        }
    }

    // ---- CST（tree_builder.js / cst.js） ----
    private fun setNodeLocationFull(node: CstNode, start: Double, end: Double) {
        if (node.startOffset.isNaN()) {
            node.startOffset = start
            node.endOffset = end
        } else if (node.endOffset < end) {
            node.endOffset = end
        }
    }

    private fun cstPostTerminal(key: String, token: Token) {
        val root = cstStack.last()
        root.children.getOrPut(key) { mutableListOf() }.add(token)
        setNodeLocationFull(root, token.startOffset, token.endOffset)
    }

    private fun cstPostNonTerminal(result: CstNode, ruleName: String) {
        val pre = cstStack.last()
        pre.children.getOrPut(ruleName) { mutableListOf() }.add(result)
        setNodeLocationFull(pre, result.startOffset, result.endOffset)
    }
}
