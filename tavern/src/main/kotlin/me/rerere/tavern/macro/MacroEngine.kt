package me.rerere.tavern.macro

import me.rerere.tavern.macro.macros.registerBuiltinMacros
import me.rerere.tavern.variable.Js
import me.rerere.tavern.variable.Undefined

/**
 * 宏引擎入口。ST:public/scripts/macros/engine/MacroEngine.js + macros.js evaluateMacros 的新引擎路径。
 *
 * 公开入口：[MacroEngine.evaluate]：`pre-processors → parseDocument → CST 遍历 → post-processors`。
 * [MacroEnv] 每次调用注入（上游是全局页面状态，这里显式传参，无副作用全局）。
 */
class MacroEngine(registry: MacroRegistry? = null) {
    val registry: MacroRegistry = (registry ?: MacroRegistry().also { registerBuiltinMacros(it) })
        .also { it.engineEvaluate = ::evaluate }
    private val walker = MacroWalker(this.registry)

    /**
     * ST:MacroEngine.js:117 evaluate。
     * [contextOffset] 只在内部递归用（`{{if}}`/`resolve` 的嵌套求值）；外部调 0。
     */
    fun evaluate(input: String, env: MacroEnv, contextOffset: Int = 0): String {
        if (input.isEmpty()) return ""

        // {{original}} 一次性：env 层包成 first-call-only（上游 MacroEnvBuilder 建 env 时做）
        val origFn = env.original
        val evalEnv = if (origFn == null) env else {
            var used = false
            env.copy(original = { if (used) "" else { used = true; origFn() } })
        }

        val preProcessed = runPreProcessors(input, evalEnv)

        val parsed = MacroParser.parseDocument(preProcessed)
        // 词法/语法告警只收集，不中断（上游 logMacroSyntaxWarning）
        val cst = parsed.cst
        if (cst == null || cst.children.isEmpty()) {
            env.warn("Macro parser produced an invalid CST. Returning original input.")
            return input // 上游返回原始 input，不是 preProcessed
        }

        val evaluated = try {
            walker.evaluateDocument(preProcessed, cst, contextOffset, evalEnv, { call -> resolveMacro(call) }, ::trimScopedContent)
        } catch (e: Exception) {
            env.warn("Macro evaluation failed. Returning original input.")
            return input
        }

        return runPostProcessors(evaluated, evalEnv)
    }

    // ST:MacroEngine.js:167 #resolveMacro
    private fun resolveMacro(call: MacroCall): String {
        val name = call.name
        val raw = "{{${call.rawInner}}}"
        if (name.isEmpty()) return raw

        // 动态宏（env.dynamicMacros + ownMacros）优先于注册表
        var defOverride: MacroDefinition? = null
        val nameLower = name.lowercase()
        val impl: DynamicMacro? = call.env.dynamicMacros[nameLower]
            ?: call.env.ownMacros[nameLower]?.let { fn -> DynamicMacro.Fn { _ -> fn() } }

        if (impl != null) {
            when (impl) {
                is DynamicMacro.Options -> {
                    try {
                        defOverride = registry.buildMacroDefFromOptions(name, impl.options)
                    } catch (e: Exception) {
                        call.env.warn("Dynamic macro \"$name\" has invalid options: ${e.message}")
                    }
                }
                is DynamicMacro.Value -> {
                    defOverride = registry.buildMacroDefFromOptions(name, MacroDefinitionOptions(
                        category = "dynamic", description = "Dynamic macro",
                        returnType = listOf(MacroValueType.STRING),
                        handler = { impl.text },
                    ))
                }
                is DynamicMacro.Fn -> {
                    defOverride = registry.buildMacroDefFromOptions(name, MacroDefinitionOptions(
                        category = "dynamic", description = "Dynamic macro",
                        returnType = listOf(MacroValueType.STRING),
                        handler = { ctx -> impl.handle(ctx) },
                    ))
                }
            }
        }

        if (defOverride == null && !registry.hasMacro(name)) {
            return raw // 未知宏：保留语法，但嵌套宏已在 rawInner 里解析过
        }

        return try {
            val result = registry.executeMacro(call, defOverride)
            try {
                call.env.postProcess(normalizeMacroResult(result))
            } catch (e: Exception) {
                call.env.warn("Macro \"$name\" postProcess function failed.")
                normalizeMacroResult(result)
            }
        } catch (e: MacroRuntimeError) {
            call.env.warn(e.message ?: "Macro \"$name\" execution failed.")
            raw
        } catch (e: Exception) {
            call.env.warn("Macro \"$name\" internal execution error.")
            raw
        }
    }

    // ST:MacroEngine.js:273 #registerCorePreProcessors
    private fun runPreProcessors(text: String, env: MacroEnv): String {
        var result = text
        // {{time_UTC-10}} => {{time::UTC-10}}（legacy 语法，新语法树不认，先正则改写）
        result = Regex("\\{\\{time_(UTC[+-]\\d+)\\}\\}", RegexOption.IGNORE_CASE)
            .replace(result) { "{{time::${it.groupValues[1]}}}" }
        // <USER> <BOT> <CHAR> <GROUP> <CHARIFNOTGROUP> => 对应宏
        result = Regex("<USER>", RegexOption.IGNORE_CASE).replace(result, "{{user}}")
        result = Regex("<BOT>", RegexOption.IGNORE_CASE).replace(result, "{{char}}")
        result = Regex("<CHAR>", RegexOption.IGNORE_CASE).replace(result, "{{char}}")
        result = Regex("<GROUP>", RegexOption.IGNORE_CASE).replace(result, "{{group}}")
        result = Regex("<CHARIFNOTGROUP>", RegexOption.IGNORE_CASE).replace(result, "{{charIfNotGroup}}")
        return result
    }

    // ST:MacroEngine.js:299 #registerCorePostProcessors
    private fun runPostProcessors(text: String, env: MacroEnv): String {
        var result = text
        // \{ => {、\} => }（\{\{ 走不进宏，后处理反转义）
        result = Regex("\\\\([{}])").replace(result, "$1")
        // {{trim}} 吞周围换行（legacy trim 语义，引擎不管）
        result = Regex("(?:\\r?\\n)*\\{\\{trim\\}\\}(?:\\r?\\n)*", RegexOption.IGNORE_CASE).replace(result, "")
        // 清理可能残留的 ELSE_MARKER
        result = result.replace(ELSE_MARKER, "")
        return result
    }

    companion object {
        /** `{{else}}` 在 `{{if}}` 里做分支切分的内部标记（永不进入输出，后处理器兜底清理）。 */
        val ELSE_MARKER = "\u0000\u001FELSE\u001F\u0000"

        // ST:MacroEngine.js:332 normalizeMacroResult
        fun normalizeMacroResult(value: Any?): String = when (value) {
            null, Undefined -> ""
            is Map<*, *>, is List<*> -> Js.stringify(value) ?: ""
            is Double -> Js.numberToString(value)
            is Boolean -> value.toString()
            else -> value.toString()
        }

        // ST:MacroEngine.js:372 trimScopedContent（trim + 按首个非空行的缩进去共同缩进）
        fun trimScopedContent(content: String, trimIndent: Boolean = true): String {
            if (content.isEmpty()) return ""
            if (!trimIndent) return Js.trim(content)

            val lines = content.split('\n')
            var baseIndent = 0
            for (line in lines) {
                if (line.trim() != "") {
                    baseIndent = Regex("^[ \t]*").find(line)?.value?.length ?: 0
                    break
                }
            }
            if (baseIndent == 0) return Js.trim(content)

            val dedented = lines.map { line ->
                val lineIndent = Regex("^[ \t]*").find(line)?.value?.length ?: 0
                if (lineIndent >= baseIndent) line.substring(baseIndent) else Js.trimStart(line)
            }
            return Js.trim(dedented.joinToString("\n"))
        }
    }
}
