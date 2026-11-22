package me.rerere.tavern.macro.chevrotain

// 对应 chevrotain parse/grammar/{first,follow,rest,interpreter,lookahead}.js

// ---- first.js ----

/** @chevrotain/gast helpers.js isOptionalProd */
internal fun isOptionalProd(prod: Prod, alreadyVisited: MutableList<Prod> = mutableListOf()): Boolean {
    if (prod is Option || prod is Repetition) return true
    if (prod is Alternation) return prod.definition.any { isOptionalProd(it, alreadyVisited) }
    if (prod is NonTerminal && alreadyVisited.contains(prod)) return false
    if (prod is Terminal) return false
    if (prod is NonTerminal) alreadyVisited.add(prod)
    return prod.definition.all { isOptionalProd(it, alreadyVisited) }
}

/** ST 无 MANY_SEP 用法，故不移植 RepetitionWithSeparator。 */
internal fun first(prod: Prod): List<TokenType> = when (prod) {
    is NonTerminal -> first(prod.referencedRule)
    is Terminal -> listOf(prod.type)
    is Alternation -> prod.definition.flatMap { first(it) }.distinct()
    else -> firstForSequence(prod)
}

private fun firstForSequence(prod: Prod): List<TokenType> {
    val firstSet = mutableListOf<TokenType>()
    val seq = prod.definition
    var next = 0
    var lastOptional = true
    while (seq.size > next && lastOptional) {
        val curr = seq[next]
        lastOptional = isOptionalProd(curr)
        firstSet += first(curr)
        next++
    }
    return firstSet.distinct()
}

// ---- rest.js ----

internal abstract class RestWalker {
    open fun walk(prod: Prod, prevRest: List<Prod> = emptyList()) {
        prod.definition.forEachIndexed { index, sub ->
            val currRest = prod.definition.drop(index + 1)
            when (sub) {
                is NonTerminal -> walkProdRef(sub, currRest, prevRest)
                is Terminal -> walkTerminal(sub, currRest, prevRest)
                is Alternative -> walkFlat(sub, currRest, prevRest)
                is Option -> walkOption(sub, currRest, prevRest)
                is RepetitionMandatory -> walkAtLeastOne(sub, currRest, prevRest)
                is RepetitionMandatoryWithSeparator -> walkAtLeastOneSep(sub, currRest, prevRest)
                is Repetition -> walkMany(sub, currRest, prevRest)
                is Alternation -> walkOr(sub, currRest, prevRest)
                is Rule -> error("non exhaustive match")
            }
        }
    }

    open fun walkTerminal(terminal: Terminal, currRest: List<Prod>, prevRest: List<Prod>) {}
    open fun walkProdRef(refProd: NonTerminal, currRest: List<Prod>, prevRest: List<Prod>) {}

    open fun walkFlat(flatProd: Alternative, currRest: List<Prod>, prevRest: List<Prod>) {
        walk(flatProd, currRest + prevRest)
    }

    open fun walkOption(optionProd: Option, currRest: List<Prod>, prevRest: List<Prod>) {
        walk(optionProd, currRest + prevRest)
    }

    open fun walkAtLeastOne(prod: RepetitionMandatory, currRest: List<Prod>, prevRest: List<Prod>) {
        // ABC(DE)+F => after the (DE)+ the rest is (DE)?F
        walk(prod, listOf<Prod>(Option(0, prod.definition)) + currRest + prevRest)
    }

    open fun walkAtLeastOneSep(prod: RepetitionMandatoryWithSeparator, currRest: List<Prod>, prevRest: List<Prod>) {
        // after the (,DE)+ the rest is (,DE)?F
        val repSepRest = listOf<Prod>(Option(0, listOf<Prod>(Terminal(prod.separator)) + prod.definition))
        walk(prod, repSepRest + currRest + prevRest)
    }

    open fun walkMany(prod: Repetition, currRest: List<Prod>, prevRest: List<Prod>) {
        walk(prod, listOf<Prod>(Option(0, prod.definition)) + currRest + prevRest)
    }

    open fun walkOr(orProd: Alternation, currRest: List<Prod>, prevRest: List<Prod>) {
        val fullOrRest = currRest + prevRest
        orProd.definition.forEach { alt ->
            walk(Alternative(listOf(alt)), fullOrRest)
        }
    }
}

// ---- follow.js ----

internal const val IN = "_~IN~_"

internal class ResyncFollowsWalker(private val topProd: Rule) : RestWalker() {
    val follows = HashMap<String, List<TokenType>>()

    fun startWalking(): Map<String, List<TokenType>> {
        walk(topProd)
        return follows
    }

    override fun walkProdRef(refProd: NonTerminal, currRest: List<Prod>, prevRest: List<Prod>) {
        val followName = refProd.referencedRule.name + refProd.idx + IN + topProd.name
        follows[followName] = first(Alternative(currRest + prevRest))
    }
}

// ---- interpreter.js ----

internal class GrammarPath(
    val ruleStack: List<String>,
    val occurrenceStack: List<Int>,
    val lastTok: TokenType,
    val lastTokOccurrence: Int,
)

/** NextAfterTokenWalker：在语法路径 [path] 末尾的那个终结符之后，下一个可能出现的 token 类型。 */
internal class NextAfterTokenWalker(private val topProd: Rule, private val path: GrammarPath) : RestWalker() {
    var possibleTokTypes: List<TokenType> = emptyList()
    private var nextProductionName = ""
    private var nextProductionOccurrence = 0
    private var found = false
    private var isAtEndOfPath = false
    private val ruleStack = ArrayDeque<String>()
    private val occurrenceStack = ArrayDeque<Int>()

    fun startWalking(): List<TokenType> {
        found = false
        check(path.ruleStack[0] == topProd.name) { "The path does not start with the walker's top Rule!" }
        // 上游先 reverse 再 pop：等于依次取出第 1、2、3... 个元素，并丢掉第 0 个
        ruleStack.clear(); occurrenceStack.clear()
        ruleStack.addAll(path.ruleStack.drop(1))
        occurrenceStack.addAll(path.occurrenceStack.drop(1))
        updateExpectedNext()
        walk(topProd)
        return possibleTokTypes
    }

    override fun walk(prod: Prod, prevRest: List<Prod>) {
        if (!found) super.walk(prod, prevRest)
    }

    override fun walkProdRef(refProd: NonTerminal, currRest: List<Prod>, prevRest: List<Prod>) {
        if (refProd.referencedRule.name == nextProductionName && refProd.idx == nextProductionOccurrence) {
            val fullRest = currRest + prevRest
            updateExpectedNext()
            walk(refProd.referencedRule, fullRest)
        }
    }

    override fun walkTerminal(terminal: Terminal, currRest: List<Prod>, prevRest: List<Prod>) {
        if (isAtEndOfPath && terminal.type.name == path.lastTok.name && terminal.idx == path.lastTokOccurrence && !found) {
            possibleTokTypes = first(Alternative(currRest + prevRest))
            found = true
        }
    }

    private fun updateExpectedNext() {
        if (ruleStack.isEmpty()) {
            nextProductionName = ""
            nextProductionOccurrence = 0
            isAtEndOfPath = true
        } else {
            nextProductionName = ruleStack.removeFirst()
            nextProductionOccurrence = occurrenceStack.removeFirst()
        }
    }
}

/** 重复结构之后紧跟的首个元素（若为终结符）。 */
internal class AfterRepetitionInfo(val token: TokenType?, val occurrence: Int?, val isEndOfRule: Boolean?)

/** NextTerminalAfter{Many,AtLeastOne,AtLeastOneSep}Walker */
internal class NextTerminalAfterWalker(
    private val topRule: Rule,
    private val kind: Kind,
    private val occurrence: Int,
) : RestWalker() {
    enum class Kind { MANY, AT_LEAST_ONE, AT_LEAST_ONE_SEP }

    private var token: TokenType? = null
    private var tokOccurrence: Int? = null
    private var isEndOfRule: Boolean? = null

    fun startWalking(): AfterRepetitionInfo {
        walk(topRule)
        return AfterRepetitionInfo(token, tokOccurrence, isEndOfRule)
    }

    private fun record(currRest: List<Prod>, prevRest: List<Prod>) {
        val firstAfter = (currRest + prevRest).firstOrNull()
        isEndOfRule = firstAfter == null
        if (firstAfter is Terminal) {
            token = firstAfter.type
            tokOccurrence = firstAfter.idx
        }
    }

    override fun walkMany(prod: Repetition, currRest: List<Prod>, prevRest: List<Prod>) {
        if (kind == Kind.MANY && prod.idx == occurrence) record(currRest, prevRest) else super.walkMany(prod, currRest, prevRest)
    }

    override fun walkAtLeastOne(prod: RepetitionMandatory, currRest: List<Prod>, prevRest: List<Prod>) {
        if (kind == Kind.AT_LEAST_ONE && prod.idx == occurrence) record(currRest, prevRest) else super.walkAtLeastOne(prod, currRest, prevRest)
    }

    override fun walkAtLeastOneSep(prod: RepetitionMandatoryWithSeparator, currRest: List<Prod>, prevRest: List<Prod>) {
        if (kind == Kind.AT_LEAST_ONE_SEP && prod.idx == occurrence) record(currRest, prevRest) else super.walkAtLeastOneSep(prod, currRest, prevRest)
    }
}

internal class PathAndSuffix(val partialPath: List<TokenType>, val suffixDef: List<Prod>)

/** interpreter.js possiblePathsFrom */
internal fun possiblePathsFrom(targetDef: List<Prod>, maxLength: Int, currPathIn: List<TokenType> = emptyList()): List<PathAndSuffix> {
    val currPath = currPathIn.toMutableList()
    var result: List<PathAndSuffix> = emptyList()
    var i = 0

    fun remainingPathWith(nextDef: List<Prod>) = nextDef + targetDef.drop(i + 1)
    fun getAlternativesForProd(definition: List<Prod>): List<PathAndSuffix> =
        result + possiblePathsFrom(remainingPathWith(definition), maxLength, currPath)

    while (currPath.size < maxLength && i < targetDef.size) {
        when (val prod = targetDef[i]) {
            is Alternative -> return getAlternativesForProd(prod.definition)
            is NonTerminal -> return getAlternativesForProd(prod.definition)
            is Option -> result = getAlternativesForProd(prod.definition)
            is RepetitionMandatory -> {
                val newDef = prod.definition + Repetition(0, prod.definition)
                return getAlternativesForProd(newDef)
            }
            is RepetitionMandatoryWithSeparator -> {
                val newDef = listOf<Prod>(
                    Alternative(prod.definition),
                    Repetition(0, listOf<Prod>(Terminal(prod.separator)) + prod.definition),
                )
                return getAlternativesForProd(newDef)
            }
            is Repetition -> {
                val newDef = prod.definition + Repetition(0, prod.definition)
                result = getAlternativesForProd(newDef)
            }
            is Alternation -> {
                prod.definition.forEach { currAlt ->
                    if (currAlt.definition.isNotEmpty()) result = getAlternativesForProd(currAlt.definition)
                }
                return result
            }
            is Terminal -> currPath.add(prod.type)
            is Rule -> error("non exhaustive match")
        }
        i++
    }
    return result + PathAndSuffix(currPath.toList(), targetDef.drop(i))
}

// ---- lookahead.js ----

internal enum class ProdType { OPTION, REPETITION, REPETITION_MANDATORY, REPETITION_MANDATORY_WITH_SEPARATOR, ALTERNATION }

private fun pathKey(path: List<TokenType>): String = path.joinToString("") { "_" + it.id }

private fun isUniquePrefixHash(altKnownPathsKeys: List<Set<String>>, searchKey: String, idx: Int): Boolean {
    for (currAltIdx in altKnownPathsKeys.indices) {
        if (currAltIdx == idx) continue
        if (searchKey in altKnownPathsKeys[currAltIdx]) return false
    }
    return true
}

/** lookahead.js lookAheadSequenceFromAlternatives */
internal fun lookAheadSequenceFromAlternatives(altsDefs: List<Alternative>, k: Int): List<List<List<TokenType>>> {
    val partialAlts = altsDefs.map { possiblePathsFrom(listOf(it), 1) }
    val finalResult = List(partialAlts.size) { mutableListOf<List<TokenType>>() }
    val altsHashes = partialAlts.map { paths -> paths.map { pathKey(it.partialPath) }.toMutableSet() }
    var newData: List<List<PathAndSuffix>> = partialAlts

    for (pathLength in 1..k) {
        val currDataset = newData
        val next = List(currDataset.size) { mutableListOf<PathAndSuffix>() }
        newData = next
        for (altIdx in currDataset.indices) {
            for (item in currDataset[altIdx]) {
                val currPathPrefix = item.partialPath
                val suffixDef = item.suffixDef
                val prefixKey = pathKey(currPathPrefix)
                val isUnique = isUniquePrefixHash(altsHashes, prefixKey, altIdx)
                if (isUnique || suffixDef.isEmpty() || currPathPrefix.size == k) {
                    val currAltResult = finalResult[altIdx]
                    if (currAltResult.none { it == currPathPrefix }) {
                        currAltResult.add(currPathPrefix)
                        altsHashes[altIdx].add(prefixKey)
                    }
                } else {
                    val newPartial = possiblePathsFrom(suffixDef, pathLength + 1, currPathPrefix)
                    next[altIdx].addAll(newPartial)
                    newPartial.forEach { altsHashes[altIdx].add(pathKey(it.partialPath)) }
                }
            }
        }
    }
    return finalResult
}

/** InsideDefinitionFinderVisitor：在规则内（不进入引用）找到最后一个匹配的产生式定义。 */
private fun findInside(rule: Rule, type: ProdType, occurrence: Int): List<Prod> {
    var result: List<Prod> = emptyList()
    fun visit(defs: List<Prod>) {
        for (p in defs) {
            when (p) {
                is Option -> { if (type == ProdType.OPTION && p.idx == occurrence) result = p.definition; visit(p.definition) }
                is Repetition -> { if (type == ProdType.REPETITION && p.idx == occurrence) result = p.definition; visit(p.definition) }
                is RepetitionMandatory -> { if (type == ProdType.REPETITION_MANDATORY && p.idx == occurrence) result = p.definition; visit(p.definition) }
                is RepetitionMandatoryWithSeparator -> { if (type == ProdType.REPETITION_MANDATORY_WITH_SEPARATOR && p.idx == occurrence) result = p.definition; visit(p.definition) }
                is Alternation -> { if (type == ProdType.ALTERNATION && p.idx == occurrence) result = p.definition; visit(p.definition) }
                is Alternative -> visit(p.definition)
                is NonTerminal, is Terminal, is Rule -> {}
            }
        }
    }
    visit(rule.definition)
    return result
}

/** RestDefinitionFinderWalker：目标产生式之后、规则内剩余的定义。 */
private class RestDefinitionFinderWalker(
    private val topProd: Rule,
    private val targetOccurrence: Int,
    private val targetProdType: ProdType,
) : RestWalker() {
    var restDef: List<Prod> = emptyList()

    fun startWalking(): List<Prod> {
        walk(topProd)
        return restDef
    }

    private fun checkIsTarget(idx: Int, expected: ProdType, currRest: List<Prod>, prevRest: List<Prod>): Boolean {
        if (idx == targetOccurrence && targetProdType == expected) {
            restDef = currRest + prevRest
            return true
        }
        return false
    }

    override fun walkOption(optionProd: Option, currRest: List<Prod>, prevRest: List<Prod>) {
        if (!checkIsTarget(optionProd.idx, ProdType.OPTION, currRest, prevRest)) super.walkOption(optionProd, currRest, prevRest)
    }

    // 上游这几个方法在未命中时调用的是 super.walkOption（而不是各自的 walkXxx），这里照搬。
    override fun walkAtLeastOne(prod: RepetitionMandatory, currRest: List<Prod>, prevRest: List<Prod>) {
        if (!checkIsTarget(prod.idx, ProdType.REPETITION_MANDATORY, currRest, prevRest)) {
            walk(prod, currRest + prevRest)
        }
    }

    override fun walkAtLeastOneSep(prod: RepetitionMandatoryWithSeparator, currRest: List<Prod>, prevRest: List<Prod>) {
        if (!checkIsTarget(prod.idx, ProdType.REPETITION_MANDATORY_WITH_SEPARATOR, currRest, prevRest)) {
            walk(prod, currRest + prevRest)
        }
    }

    override fun walkMany(prod: Repetition, currRest: List<Prod>, prevRest: List<Prod>) {
        if (!checkIsTarget(prod.idx, ProdType.REPETITION, currRest, prevRest)) {
            walk(prod, currRest + prevRest)
        }
    }
}

internal fun getLookaheadPathsForOr(occurrence: Int, rule: Rule, k: Int): List<List<List<TokenType>>> {
    @Suppress("UNCHECKED_CAST")
    val alts = findInside(rule, ProdType.ALTERNATION, occurrence) as List<Alternative>
    return lookAheadSequenceFromAlternatives(alts, k)
}

internal fun getLookaheadPathsForOptionalProd(occurrence: Int, rule: Rule, prodType: ProdType, k: Int): List<List<List<TokenType>>> {
    val insideDef = findInside(rule, prodType, occurrence)
    val afterDef = RestDefinitionFinderWalker(rule, occurrence, prodType).startWalking()
    return lookAheadSequenceFromAlternatives(listOf(Alternative(insideDef), Alternative(afterDef)), k)
}
