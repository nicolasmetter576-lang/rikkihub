package me.rerere.tavern.macro

import me.rerere.tavern.macro.chevrotain.CstNode
import me.rerere.tavern.macro.chevrotain.Token
import me.rerere.tavern.variable.Js

/**
 * CST 遍历器。ST:public/scripts/macros/engine/MacroCstWalker.js。
 * 语义照搬：参数按序求值、`delayArgResolution` 时参数与作用域内容都保留原文、
 * 恢复 token 打平成 plaintext + 完整嵌套宏、globalOffset 传播（`{{pick}}` 确定性因子）、
 * 变量表达式 16 个操作符、未配对开闭标签 keepRaw。
 */
class MacroWalker(private val registry: MacroRegistry) {

    /** EvaluationContext：当前层 text + 全局偏移 + env + 回调。 */
    private class EvalContext(
        val text: String,
        val contextOffset: Int,
        val env: MacroEnv,
        val resolveMacro: (MacroCall) -> String,
        val trimContent: (String) -> String,
    )

    /** scopedContent：`{startOffset, endOffset, closingEndOffset}`。 */
    class ScopedContent(val startOffset: Int, val endOffset: Int, val closingEndOffset: Int)

    /** DocumentItem：plaintext / macro（可带 scopedContent 与 keepRaw）。 */
    class DocumentItem(
        val isPlaintext: Boolean,
        val startOffset: Int,
        val endOffset: Int,
        val node: CstNode? = null,
        var scopedContent: ScopedContent? = null,
        var keepRaw: Boolean = false,
    )

    // ST:MacroCstWalker.js:93 evaluateDocument
    fun evaluateDocument(
        text: String,
        cst: CstNode,
        contextOffset: Int,
        env: MacroEnv,
        resolveMacro: (MacroCall) -> String,
        trimContent: (String) -> String,
    ): String {
        val context = EvalContext(text, contextOffset, env, resolveMacro, trimContent)
        val items = processScopedMacros(collectDocumentItems(cst), text)
        return evaluateItems(items, context)
    }

    /** evaluateDocument 与 evaluateRawContent 共用的逐项求值循环（上游两份相同循环合并）。 */
    private fun evaluateItems(items: List<DocumentItem>, context: EvalContext): String {
        val text = context.text
        if (items.isEmpty()) return text
        var result = StringBuilder()
        var cursor = 0
        for (item in items) {
            if (item.startOffset > cursor) result.append(text.substring(cursor, item.startOffset))
            when {
                item.isPlaintext || item.keepRaw -> {
                    result.append(text.substring(item.startOffset, item.endOffset + 1))
                    cursor = item.endOffset + 1
                }
                else -> {
                    result.append(evaluateMacroNode(item.node!!, context, item.scopedContent))
                    val closingEnd = item.scopedContent?.closingEndOffset
                    cursor = if (closingEnd != null && closingEnd > item.endOffset) closingEnd + 1 else item.endOffset + 1
                }
            }
        }
        if (cursor < text.length) result.append(text.substring(cursor))
        return result.toString()
    }

    // ST:MacroCstWalker.js:162 extractMacroInfo（{{if}} 的 splitOnTopLevelElse 也用，伴生对象）
    companion object {
        fun extractMacroInfo(macroNode: CstNode): MacroInfo? {
            val children = macroNode.children
            if (children["variableExpr"]?.firstOrNull() != null) return null

            val startToken = macroNode.tokens("Macro.Start").firstOrNull()
            val endToken = macroNode.tokens("Macro.End").firstOrNull()
            if (startToken == null || endToken == null) return null

            val macroBodyNode = macroNode.nodes("macroBody").firstOrNull()
            val identifierTokens = macroBodyNode?.tokens("Macro.identifier") ?: emptyList()
            val name = identifierTokens.firstOrNull()?.image ?: return null

            val argumentsNode = macroBodyNode?.nodes("arguments")?.firstOrNull()
            val argumentNodes = argumentsNode?.nodes("argument") ?: emptyList()
            val argCount = argumentNodes.size

            var isClosing = macroNode.tokens("flags").any { it.image == MacroFlagType.CLOSING_BLOCK }

            // `{{///}}` 被词法切成 `{{// /}}`：首个参数 token 以 '/' 开头则视为闭合
            if (!isClosing && name == "//") {
                val firstArg = argumentNodes.firstOrNull()
                if (firstArg != null) {
                    val firstToken = getFirstTokenInNode(firstArg)
                    if (firstToken?.image?.startsWith("/") == true) isClosing = true
                }
            }

            return MacroInfo(name, isClosing, startToken.startOffset.toInt(), endToken.endOffset.toInt(), argCount)
        }

        // ST:MacroCstWalker.js:1123 #getFirstTokenInNode（全 children 扫描取 startOffset 最小的叶子 token）
        fun getFirstTokenInNode(node: CstNode): Token? {
            var first: Token? = null
            for (children in node.children.values) {
                for (element in children) {
                    if (element is CstNode) continue
                    val token = element as? Token ?: continue
                    if (token.startOffset.isNaN()) continue
                    if (first == null || token.startOffset < first!!.startOffset) first = token
                }
            }
            return first
        }
    }

    data class MacroInfo(
        val name: String,
        val isClosing: Boolean,
        val startOffset: Int,
        val endOffset: Int,
        val argCount: Int,
    )

    // ST:MacroCstWalker.js:319 #collectDocumentItems
    private fun collectDocumentItems(cst: CstNode): MutableList<DocumentItem> {
        val items = ArrayList<DocumentItem>()

        for (token in cst.tokens("plaintext")) {
            if (token.startOffset.isNaN() || token.endOffset.isNaN()) continue
            items.add(DocumentItem(true, token.startOffset.toInt(), token.endOffset.toInt()))
        }

        for (macroNode in cst.nodes("macro")) {
            val endToken = macroNode.tokens("Macro.End").firstOrNull()
            // Macro.End 是恢复插入的：把整个不完整宏打平成 plaintext + 完整嵌套宏
            if (isRecoveryToken(endToken)) {
                flattenIncompleteMacro(macroNode, endToken, items)
                continue
            }
            val range = getMacroRange(macroNode)
            items.add(DocumentItem(false, range.first, range.last, node = macroNode))
        }

        items.sortWith(compareBy({ it.startOffset }, { it.endOffset }))
        return items
    }

    // ST:MacroCstWalker.js:377 #evaluateMacroNode
    private fun evaluateMacroNode(macroNode: CstNode, context: EvalContext, scopedContent: ScopedContent?): String {
        val text = context.text
        val contextOffset = context.contextOffset
        val env = context.env
        val resolveMacro = context.resolveMacro

        val variableExprNode = macroNode.nodes("variableExpr").firstOrNull()
        if (variableExprNode != null) {
            return evaluateVariableExpr(macroNode, variableExprNode, context)
        }

        val macroBodyNode = macroNode.nodes("macroBody").firstOrNull()
        val identifierTokens = macroBodyNode?.tokens("Macro.identifier") ?: emptyList()
        val name = identifierTokens.firstOrNull()?.image ?: ""

        val flagSymbols = macroNode.tokens("flags").map { it.image }
        val flags = if (flagSymbols.isNotEmpty()) parseFlags(flagSymbols) else createEmptyFlags()

        val range = getMacroRange(macroNode)
        val startToken = macroNode.tokens("Macro.Start").firstOrNull()
        val endToken = macroNode.tokens("Macro.End").firstOrNull()
        val innerStart = startToken?.let { it.endOffset.toInt() + 1 } ?: range.first
        val innerEnd = endToken?.let { it.startOffset.toInt() - 1 } ?: range.last

        val argumentsNode = macroBodyNode?.nodes("arguments")?.firstOrNull()
        val argumentNodes = argumentsNode?.nodes("argument") ?: emptyList()

        // 上游用 getMacro（含别名），不用 getPrimaryMacro
        val macroDef = registry.getMacro(name)
        val delayArgResolution = macroDef?.delayArgResolution == true

        val args = ArrayList<String>()
        val evaluatedArguments = ArrayList<Triple<String, Int, Int>>() // (value, startOffset, endOffset)
        val rawArgs = ArrayList<String>()

        for (argNode in argumentNodes) {
            val location = getArgumentLocation(argNode)
            val rawArgText = if (location != null) text.substring(location.first, location.last + 1) else ""
            rawArgs.add(rawArgText)

            val argValue = if (delayArgResolution) rawArgText else evaluateArgumentNode(argNode, context)
            args.add(argValue)

            if (location != null) evaluatedArguments.add(Triple(argValue, location.first, location.last))
        }

        // 作用域内容作为最后一个无名参数；delayArgResolution 时保留原文（{{if}} 自己找 {{else}}）
        if (scopedContent != null) {
            if (scopedContent.startOffset > scopedContent.endOffset) {
                args.add("")
                rawArgs.add("")
            } else {
                val rawScopedText = text.substring(scopedContent.startOffset, scopedContent.endOffset + 1)
                rawArgs.add(rawScopedText)

                val scopedValue = if (delayArgResolution) {
                    rawScopedText
                } else {
                    var v = evaluateScopedContent(scopedContent, context)
                    if (!flags.preserveWhitespace) v = context.trimContent(v)
                    v
                }
                args.add(scopedValue)
                evaluatedArguments.add(Triple(scopedValue, scopedContent.startOffset, scopedContent.endOffset))
            }
        }

        evaluatedArguments.sortBy { it.second }

        // 重建 `{{...}}` 内部文本：已解析片段 + 间隙原文（未知宏/错误时原样输出用）
        var rawInner = ""
        if (innerStart <= innerEnd) {
            var cursor = innerStart
            for ((value, start, end) in evaluatedArguments) {
                if (start > cursor) rawInner += text.substring(cursor, start)
                rawInner += value
                cursor = end + 1
            }
            if (cursor <= innerEnd) rawInner += text.substring(cursor, innerEnd + 1)
        }

        val call = MacroCall(
            name = name,
            args = args,
            flags = flags,
            isScoped = scopedContent != null,
            rawInner = rawInner,
            rawWithBraces = text.substring(range.first, range.last + 1),
            rawArgs = rawArgs,
            env = env,
            cstNode = macroNode,
            range = range,
            globalOffset = contextOffset + range.first,
        )

        val value = resolveMacro(call)
        return value
    }

    // ST:MacroCstWalker.js:526 #evaluateVariableExpr
    private fun evaluateVariableExpr(macroNode: CstNode, variableExprNode: CstNode, context: EvalContext): String {
        val varChildren = variableExprNode.children

        val localPrefixToken = varChildren["Var.scope"]?.filterIsInstance<Token>()?.firstOrNull { it.type === MacroTokens.VarLocalPrefix }
        val isGlobal = localPrefixToken == null

        val varName = varChildren["Var.identifier"]?.filterIsInstance<Token>()?.firstOrNull()?.image ?: ""

        val operatorNode = varChildren["variableOperator"]?.filterIsInstance<CstNode>()?.firstOrNull()
        val operatorChildren = operatorNode?.children ?: emptyMap()

        var operation = "get"
        var hasValueExpr = false
        if (operatorNode != null) {
            val operatorToken = operatorChildren["Var.operator"]?.filterIsInstance<Token>()?.firstOrNull()
            if (operatorToken != null) {
                when (operatorToken.image) {
                    "++" -> operation = "inc"
                    "--" -> operation = "dec"
                    "=" -> { operation = "set"; hasValueExpr = true }
                    "+=" -> { operation = "add"; hasValueExpr = true }
                    "-=" -> { operation = "sub"; hasValueExpr = true }
                    "||" -> { operation = "logicalOr"; hasValueExpr = true }
                    "??" -> { operation = "nullishCoalescing"; hasValueExpr = true }
                    "||=" -> { operation = "logicalOrAssign"; hasValueExpr = true }
                    "??=" -> { operation = "nullishCoalescingAssign"; hasValueExpr = true }
                    "==" -> { operation = "equals"; hasValueExpr = true }
                    "!=" -> { operation = "notEquals"; hasValueExpr = true }
                    ">" -> { operation = "greaterThan"; hasValueExpr = true }
                    ">=" -> { operation = "greaterThanOrEqual"; hasValueExpr = true }
                    "<" -> { operation = "lessThan"; hasValueExpr = true }
                    "<=" -> { operation = "lessThanOrEqual"; hasValueExpr = true }
                    else -> context.env.warn("Lexer found macro operator that is not implemented for variable shorthand expressions in macro node '${macroNode.name}'.")
                }
            }
        }

        val lazyValue = if (hasValueExpr) createLazyValue(operatorChildren, context) else ({ "" })
        return executeVariableOperation(varName, isGlobal, operation, lazyValue, context.env)
    }

    // ST:MacroCstWalker.js:634 #createLazyValue（首次调用缓存）
    private fun createLazyValue(operatorChildren: Map<String, MutableList<Any>>, context: EvalContext): () -> String {
        var cached: String? = null
        var resolved = false
        return {
            if (!resolved) {
                cached = evaluateVariableValue(operatorChildren, context)
                resolved = true
            }
            cached!!
        }
    }

    // ST:MacroCstWalker.js:656 #executeVariableOperation（上游打 ctx.variables；这里打注入的 env.variables）
    private fun executeVariableOperation(
        varName: String,
        isGlobal: Boolean,
        operation: String,
        lazyValue: () -> String,
        env: MacroEnv,
    ): String {
        val vars = if (isGlobal) env.variables.global else env.variables.local
        val normalize = MacroEngine::normalizeMacroResult

        fun isFalsy(v: Any?): Boolean = !Js.truthy(v) || Js.isFalseBoolean(normalize(v))

        return when (operation) {
            "get" -> normalize(vars.get(varName))

            "set" -> {
                vars.set(varName, lazyValue())
                ""
            }

            "inc" -> normalize(vars.inc(varName))

            "dec" -> normalize(vars.dec(varName))

            "add" -> {
                vars.add(varName, lazyValue())
                ""
            }

            "sub" -> {
                val numValue = Js.stringToNumber(lazyValue())
                if (!numValue.isNaN()) vars.add(varName, -numValue)
                else env.warn("Variable shorthand \"-=\" operator requires a numeric value, got: \"${lazyValue()}\"")
                ""
            }

            "logicalOr" -> {
                val currentValue = vars.get(varName)
                if (isFalsy(currentValue)) normalize(lazyValue()) else normalize(currentValue)
            }

            "nullishCoalescing" -> {
                if (vars.has(varName)) normalize(vars.get(varName)) else normalize(lazyValue())
            }

            "logicalOrAssign" -> {
                val currentValue = vars.get(varName)
                if (isFalsy(currentValue)) {
                    vars.set(varName, lazyValue())
                    normalize(lazyValue())
                } else normalize(currentValue)
            }

            "nullishCoalescingAssign" -> {
                if (!vars.has(varName)) {
                    vars.set(varName, lazyValue())
                    normalize(lazyValue())
                } else normalize(vars.get(varName))
            }

            "equals" -> {
                val currentValue = normalize(vars.get(varName))
                if (currentValue == normalize(lazyValue())) "true" else "false"
            }

            "notEquals" -> {
                val currentValue = normalize(vars.get(varName))
                if (currentValue != normalize(lazyValue())) "true" else "false"
            }

            "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual" -> {
                val currentNum = Js.toNumber(vars.get(varName))
                val compareNum = Js.stringToNumber(lazyValue())
                if (currentNum.isNaN() || compareNum.isNaN()) {
                    env.warn("Variable shorthand \"${operation}\" operator requires numeric values. Got: \"${vars.get(varName)}\" and \"${lazyValue()}\"")
                    "false"
                } else {
                    val result = when (operation) {
                        "greaterThan" -> currentNum > compareNum
                        "greaterThanOrEqual" -> currentNum >= compareNum
                        "lessThan" -> currentNum < compareNum
                        else -> currentNum <= compareNum
                    }
                    if (result) "true" else "false"
                }
            }

            else -> {
                env.warn("Unknown variable shorthand operation: \"$operation\"")
                ""
            }
        }
    }

    // ST:MacroCstWalker.js:808 #evaluateVariableValue（嵌套宏内联求值 + 间隙原文 + trim）
    private fun evaluateVariableValue(operatorChildren: Map<String, MutableList<Any>>, context: EvalContext): String {
        val text = context.text
        val valueNode = operatorChildren["Var.value"]?.filterIsInstance<CstNode>()?.firstOrNull() ?: return ""
        val valueChildren = valueNode.children

        val allTokens = (valueChildren["Identifier"] ?: emptyList<Any>()) + (valueChildren["Unknown"] ?: emptyList<Any>())
        val nestedMacros = valueChildren["macro"]?.filterIsInstance<CstNode>() ?: emptyList()

        val allRanges = allTokens.filterIsInstance<Token>().map { it.startOffset.toInt()..it.endOffset.toInt() } +
            nestedMacros.map { getMacroRange(it) }
        if (allRanges.isEmpty()) return ""

        val startOffset = allRanges.minOf { it.first }
        val endOffset = allRanges.maxOf { it.last }

        if (nestedMacros.isEmpty()) {
            return text.substring(startOffset, endOffset + 1).trim()
        }

        val nestedWithRange = nestedMacros.map { it to getMacroRange(it) }.sortedBy { it.second.first }
        var result = ""
        var cursor = startOffset
        for ((node, range) in nestedWithRange) {
            if (range.first > cursor) result += text.substring(cursor, range.first)
            result += evaluateMacroNode(node, context, null)
            cursor = range.last + 1
        }
        if (cursor <= endOffset) result += text.substring(cursor, endOffset + 1)

        return result.trim()
    }

    // ST:MacroCstWalker.js:882 #evaluateArgumentNode
    private fun evaluateArgumentNode(argNode: CstNode, context: EvalContext): String {
        val location = getArgumentLocation(argNode) ?: return ""
        val rawContent = context.text.substring(location.first, location.last + 1)
        return evaluateRawContent(rawContent, context.contextOffset + location.first, context)
    }

    // ST:MacroCstWalker.js:910 #evaluateRawContent
    private fun evaluateRawContent(rawContent: String, newContextOffset: Int, context: EvalContext): String {
        if (rawContent.isEmpty()) return ""
        val parsed = MacroParser.parseDocument(rawContent)
        val cst = parsed.cst
        if (cst == null || cst.children.isEmpty()) return rawContent

        val contentContext = EvalContext(rawContent, newContextOffset, context.env, context.resolveMacro, context.trimContent)
        val items = processScopedMacros(collectDocumentItems(cst), rawContent)
        return evaluateItems(items, contentContext)
    }

    // ST:MacroCstWalker.js:1152 #evaluateScopedContent
    private fun evaluateScopedContent(scopedContent: ScopedContent, context: EvalContext): String {
        val rawContent = context.text.substring(scopedContent.startOffset, scopedContent.endOffset + 1)
        return evaluateRawContent(rawContent, context.contextOffset + scopedContent.startOffset, context)
    }

    // ST:MacroCstWalker.js:981 #getMacroRange
    private fun getMacroRange(macroNode: CstNode): IntRange {
        val startToken = macroNode.tokens("Macro.Start").firstOrNull()
        val endToken = macroNode.tokens("Macro.End").firstOrNull()
        if (startToken != null && endToken != null) {
            return startToken.startOffset.toInt()..endToken.endOffset.toInt()
        }
        if (!macroNode.startOffset.isNaN() && !macroNode.endOffset.isNaN()) {
            return macroNode.startOffset.toInt()..macroNode.endOffset.toInt()
        }
        return 0..0
    }

    // ST:MacroCstWalker.js:1002 #flattenIncompleteMacro
    private fun flattenIncompleteMacro(macroNode: CstNode, excludeToken: Token?, items: MutableList<DocumentItem>) {
        for (children in macroNode.children.values) {
            for (element in children) {
                if (element === excludeToken) continue
                if (element is Token) {
                    if (element.startOffset.isNaN()) continue
                    val end = if (element.endOffset.isNaN()) element.startOffset.toInt() else element.endOffset.toInt()
                    items.add(DocumentItem(true, element.startOffset.toInt(), end))
                } else if (element is CstNode) {
                    val nestedEnd = element.tokens("Macro.End").firstOrNull()
                    val nestedStart = element.tokens("Macro.Start").firstOrNull()
                    if (nestedStart != null && nestedEnd != null) {
                        if (!isRecoveryToken(nestedEnd)) {
                            val range = getMacroRange(element)
                            items.add(DocumentItem(false, range.first, range.last, node = element))
                        } else {
                            flattenIncompleteMacro(element, nestedEnd, items)
                        }
                    } else {
                        flattenIncompleteMacro(element, excludeToken, items)
                    }
                }
            }
        }
    }

    // ST:MacroCstWalker.js:1055 #isRecoveryToken（null 也算）
    private fun isRecoveryToken(token: Token?): Boolean =
        token == null || token.isInsertedInRecovery || token.startOffset.isNaN()

    // ST:MacroCstWalker.js:1068 #getArgumentLocation（全 children 的 min/max；CST 节点用 location）
    private fun getArgumentLocation(argNode: CstNode): IntRange? {
        var startOffset = Int.MAX_VALUE
        var endOffset = Int.MIN_VALUE
        for (children in argNode.children.values) {
            for (element in children) {
                if (element is CstNode) {
                    val s = element.startOffset.toInt()
                    val e = element.endOffset.toInt()
                    if (s < startOffset) startOffset = s
                    if (e > endOffset) endOffset = e
                } else if (element is Token) {
                    val s = element.startOffset.toInt()
                    val e = element.endOffset.toInt()
                    if (s < startOffset) startOffset = s
                    if (e > endOffset) endOffset = e
                }
            }
        }
        if (startOffset == Int.MAX_VALUE || endOffset == Int.MIN_VALUE) return null
        return startOffset..endOffset
    }

    // ========================================================================
    // 作用域配对
    // ========================================================================

    private class MacroItemInfo(
        val index: Int,
        val item: DocumentItem,
        val name: String,
        val isClosing: Boolean,
        var matched: Boolean = false,
    )

    // ST:MacroCstWalker.js:1182 #processScopedMacros
    private fun processScopedMacros(items: MutableList<DocumentItem>, text: String): List<DocumentItem> {
        val macroInfos = ArrayList<MacroItemInfo>()
        for (i in items.indices) {
            val item = items[i]
            if (item.isPlaintext || item.node == null) continue
            val info = extractMacroInfo(item.node) ?: continue
            macroInfos.add(MacroItemInfo(i, item, info.name, info.isClosing))
        }

        // 只配对最外层；嵌套作用域在内容被重新解析时再发现
        val pairs = ArrayList<Pair<Int, Int>>() // (openingIndex, closingIndex) in items
        val insideScope = HashSet<Int>()

        for (i in macroInfos.indices) {
            val openInfo = macroInfos[i]
            if (openInfo.isClosing || openInfo.matched || insideScope.contains(openInfo.index)) continue

            val closingIdx = findMatchingClosingMacro(macroInfos, i)
            if (closingIdx == -1) continue

            if (!canAcceptScopedContent(openInfo.item.node!!, openInfo.name)) {
                openInfo.item.keepRaw = true
                macroInfos[closingIdx].item.keepRaw = true
                openInfo.matched = true
                macroInfos[closingIdx].matched = true
                continue
            }

            openInfo.matched = true
            macroInfos[closingIdx].matched = true
            val closingIndex = macroInfos[closingIdx].index
            pairs.add(openInfo.index to closingIndex)
            for (j in openInfo.index + 1 until closingIndex) insideScope.add(j)
        }

        for (info in macroInfos) {
            if (info.isClosing && !info.matched) info.item.keepRaw = true
        }

        if (pairs.isEmpty()) return items

        val itemsToRemove = HashSet<Int>()
        for ((openIdx, closeIdx) in pairs) {
            val openingItem = items[openIdx]
            val closingItem = items[closeIdx]
            val contentStart = openingItem.endOffset + 1
            val contentEnd = closingItem.startOffset - 1
            openingItem.scopedContent = ScopedContent(contentStart, contentEnd, closingItem.endOffset)

            itemsToRemove.add(closeIdx)
            for (j in openIdx + 1 until closeIdx) itemsToRemove.add(j)
        }

        return items.filterIndexed { index, _ -> index !in itemsToRemove }
    }

    // ST:MacroCstWalker.js:1308 #canAcceptScopedContent
    private fun canAcceptScopedContent(macroNode: CstNode, macroName: String): Boolean {
        val def = registry.getPrimaryMacro(macroName) ?: return true // 未知宏按可接处理
        if (def.list != null) return false

        val macroBodyNode = macroNode.nodes("macroBody").firstOrNull()
        val argumentNodes = macroBodyNode?.nodes("arguments")?.firstOrNull()?.nodes("argument") ?: emptyList()
        val newArgCount = argumentNodes.size + 1
        return newArgCount >= def.minArgs && newArgCount <= def.maxArgs
    }

    // ST:MacroCstWalker.js:1344 #findMatchingClosingMacro（同名不区分大小写；只有可接作用域的开标签算深度）
    private fun findMatchingClosingMacro(macroInfos: List<MacroItemInfo>, openingIdx: Int): Int {
        val targetName = macroInfos[openingIdx].name
        var depth = 1
        for (i in openingIdx + 1 until macroInfos.size) {
            val info = macroInfos[i]
            if (!info.name.equals(targetName, ignoreCase = true)) continue
            if (info.matched) continue
            if (info.isClosing) {
                depth--
                if (depth == 0) return i
            } else {
                if (canAcceptScopedContent(info.item.node!!, info.name)) depth++
            }
        }
        return -1
    }
}
