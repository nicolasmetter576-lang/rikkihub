package me.rerere.tavern.macro

import me.rerere.tavern.macro.chevrotain.CstNode
import me.rerere.tavern.variable.Js

/**
 * 宏注册表与执行。ST:public/scripts/macros/engine/MacroRegistry.js。
 * 语义照搬：名字大小写不敏感、别名、参数个数/类型校验（strictArgs 时抛 [MacroRuntimeError]，由引擎原样输出）、
 * list 尾参、动态宏 override、postProcess。
 */

enum class MacroValueType { STRING, INTEGER, NUMBER, BOOLEAN }

object MacroCategory {
    const val UTILITY = "utility"
    const val NAMES = "names"
    const val CHARACTER = "character"
    const val VARIABLE = "variable"
    const val TIME = "time"
    const val STATE = "state"
    const val CHAT = "chat"
    const val RANDOM = "random"
    const val UNCATEGORIZED = "uncategorized"
}

/** 注册时的无名参数描述（`unnamedArgs` 数组形态）。 */
data class MacroUnnamedArgDef(
    val name: String,
    val optional: Boolean = false,
    val defaultValue: String? = null,
    val type: List<MacroValueType> = listOf(MacroValueType.STRING),
    val sampleValue: String? = null,
    val description: String? = null,
)

/** `list` 尾参限制：true = 任意数量；否则带 min/max。 */
data class MacroListSpec(val min: Int = 0, val max: Int? = null)

data class MacroAliasDef(val alias: String, val visible: Boolean = true)

/** `MacroDefinitionOptions`：注册入口的形态。 */
data class MacroDefinitionOptions(
    val aliases: List<MacroAliasDef> = emptyList(),
    val category: String = MacroCategory.UNCATEGORIZED,
    /** 数组形态 = 带描述的参数；Int = n 个必填 string 参数。 */
    val unnamedArgs: List<MacroUnnamedArgDef>? = null,
    val unnamedArgsCount: Int? = null,
    /** true = 任意数量尾参；否则给 min/max。 */
    val list: MacroListSpec? = null,
    val strictArgs: Boolean = true,
    val description: String = "<no description>",
    val returns: String? = null,
    val returnType: List<MacroValueType> = listOf(MacroValueType.STRING),
    val displayOverride: String? = null,
    val exampleUsage: List<String> = emptyList(),
    val delayArgResolution: Boolean = false,
    val handler: (MacroExecutionContext) -> Any?,
)

/** `MacroDefinition`：注册进表后的形态。 */
data class MacroDefinition(
    val name: String,
    val aliases: List<MacroAliasDef>,
    val category: String,
    val minArgs: Int,
    val maxArgs: Int,
    val unnamedArgDefs: List<MacroUnnamedArgDef>,
    val list: MacroListSpec?,
    val strictArgs: Boolean,
    val description: String,
    val returns: String?,
    val returnType: List<MacroValueType>,
    val displayOverride: String?,
    val exampleUsage: List<String>,
    val delayArgResolution: Boolean,
    val handler: (MacroExecutionContext) -> Any?,
    /** 别名条目的主名；主名自己为 null。 */
    val aliasOf: String? = null,
    val aliasVisible: Boolean? = null,
)

/** 运行时错误（`{{宏::参数非法}}`）：引擎捕获后原样输出 `{{rawInner}}`。 */
class MacroRuntimeError(message: String, val call: MacroCall?, val def: MacroDefinition?) : RuntimeException(message)

/** 遍历器交给注册表的调用上下文（`MacroCall`）。 */
data class MacroCall(
    val name: String,
    val args: List<String>,
    val flags: MacroFlags,
    val isScoped: Boolean,
    /** `{{...}}` 内部已解析的内容（用于未知宏/错误时原样输出）。 */
    val rawInner: String,
    /** `{{...}}` 全量原文。 */
    val rawWithBraces: String,
    val rawArgs: List<String>,
    val env: MacroEnv,
    val cstNode: CstNode,
    val range: IntRange,
    /** 宏在整个文档中的偏移（`{{pick}}` 的确定性因子）。 */
    val globalOffset: Int,
)

/** `MacroExecutionContext`：传给宏 handler 的参数。 */
data class MacroExecutionContext(
    val name: String,
    val args: List<String>,
    val unnamedArgs: List<String>,
    /** list 宏的尾参（非 list 宏为 null）。 */
    val list: List<String>?,
    val flags: MacroFlags,
    val isScoped: Boolean,
    val raw: String,
    val rawOriginal: String,
    val rawArgs: List<String>,
    val env: MacroEnv,
    val cstNode: CstNode,
    val range: IntRange,
    val globalOffset: Int,
    val normalize: (Any?) -> String,
    val trimContent: (String) -> String,
    /** 在 call.globalOffset + offsetDelta 处重新走一遍完整 evaluate。 */
    val resolve: (String, Int) -> String,
    val warn: (String, Throwable?) -> Unit,
)


class MacroRegistry {
    private val macros = LinkedHashMap<String, MacroDefinition>()

    /**
     * `executionContext.resolve` 用的引擎入口（`MacroEngine.evaluate(text, env, contextOffset)`）。
     * 由拥有本注册表的 [MacroEngine] 在构造时注入。
     */
    var engineEvaluate: ((String, MacroEnv, Int) -> String)? = null

    // ST:MacroRegistry.js:94 registerMacro
    fun registerMacro(name: String, options: MacroDefinitionOptions) {
        val definition = buildMacroDefFromOptions(name, options)
        val nameKey = definition.name.lowercase()
        unregisterIfExists(nameKey)
        macros[nameKey] = definition
        for (aliasDef in definition.aliases) {
            registerMacroAlias(definition.name, aliasDef.alias, visible = aliasDef.visible)
        }
    }

    // ST:MacroRegistry.js:135 registerMacroAlias
    fun registerMacroAlias(name: String, alias: String, visible: Boolean = true) {
        val primary = getMacro(name)
            ?: throw IllegalArgumentException("Cannot register alias \"$alias\" for unknown macro \"$name\".")
        val aliasKey = alias.trim().lowercase()
        if (aliasKey.isEmpty()) throw IllegalArgumentException("Macro alias must be a non-empty string.")
        if (!isIdentifierValid(alias)) throw IllegalArgumentException("Macro alias \"$alias\" is invalid. Must start with a letter, followed by alphanumeric characters or hyphens.")
        if (aliasKey == primary.name.lowercase()) throw IllegalArgumentException("Macro alias \"$alias\" cannot be the same as the macro name (insensitive).")
        unregisterIfExists(aliasKey)
        val aliasDef = primary.copy(name = alias.trim(), aliases = emptyList(), aliasOf = primary.name, aliasVisible = visible)
        macros[aliasKey] = aliasDef
    }

    private fun unregisterIfExists(nameKey: String) {
        val existing = macros[nameKey] ?: return
        if (existing.aliasOf != null) {
            macros.remove(nameKey)
        } else {
            macros.remove(nameKey)
            val aliasKeys = macros.entries.filter { it.value.aliasOf == existing.name }.map { it.key }
            aliasKeys.forEach { macros.remove(it) }
        }
    }

    fun unregisterMacro(name: String) = unregisterIfExists(name.lowercase())

    fun hasMacro(name: String): Boolean = macros.containsKey(name.lowercase())

    fun getMacro(name: String): MacroDefinition? = macros[name.lowercase()]

    fun getPrimaryMacro(name: String): MacroDefinition? {
        val def = getMacro(name) ?: return null
        return def.aliasOf?.let { getMacro(it) } ?: def
    }

    fun getAllMacros(): List<MacroDefinition> = macros.values.toList()

    /**
     * ST:MacroRegistry.js:330 executeMacro。验证参数并调用 handler；
     * 不做 postProcess（引擎负责），不捕获异常。
     */
    fun executeMacro(call: MacroCall, defOverride: MacroDefinition? = null): Any? {
        val def = defOverride ?: getMacro(call.name)
            ?: throw MacroRuntimeError("Macro \"${call.name}\" is not registered.", call, null)

        val args = call.args
        if (!isArgsValid(def, args)) {
            val expectedMin = def.minArgs
            val expectedMax = if (def.list != null) null else def.maxArgs
            val expectation = when {
                expectedMax != null && expectedMax != expectedMin -> "between $expectedMin and $expectedMax"
                expectedMax != null && expectedMax == expectedMin -> "$expectedMin"
                else -> "at least $expectedMin"
            }
            val message = "Macro \"${def.name}\" called with ${args.size} unnamed arguments but expects $expectation."
            if (def.strictArgs) throw MacroRuntimeError(message, call, def)
            call.env.warn(message)
        }

        val unnamedArgsCount = minOf(args.size, def.maxArgs)
        val unnamedArgsValues = args.subList(0, unnamedArgsCount)
        val listValues = if (def.list != null) (if (args.size > def.maxArgs) args.subList(def.maxArgs, args.size) else emptyList()) else null

        validateArgTypes(call, def, unnamedArgsValues)

        val executionContext = MacroExecutionContext(
            name = def.name,
            args = args,
            unnamedArgs = unnamedArgsValues,
            list = listValues,
            flags = call.flags,
            isScoped = call.isScoped,
            raw = call.rawInner,
            rawOriginal = call.rawWithBraces,
            rawArgs = call.rawArgs,
            env = call.env,
            cstNode = call.cstNode,
            range = call.range,
            globalOffset = call.globalOffset,
            normalize = MacroEngine::normalizeMacroResult,
            trimContent = { MacroEngine.trimScopedContent(it) },
            resolve = { text, offsetDelta ->
                engineEvaluate?.invoke(text, call.env, call.globalOffset + offsetDelta) ?: text
            },
            warn = { message, _ -> call.env.warn(message) },
        )

        val result = def.handler(executionContext)
        return executionContext.normalize(result)
    }

    /** ST:MacroRegistry.js:484 buildMacroDefFromOptions。 */
    fun buildMacroDefFromOptions(name: String, options: MacroDefinitionOptions): MacroDefinition {
        val trimmedName = name.trim()
        if (!isIdentifierValid(trimmedName)) throw IllegalArgumentException("Macro name \"$trimmedName\" is invalid. Must start with a letter, followed by alphanumeric characters or hyphens.")

        val aliases = options.aliases.map { a ->
            val aliasName = a.alias.trim()
            if (aliasName.isEmpty()) throw IllegalArgumentException("Macro \"$trimmedName\" options.aliases alias must be a non-empty string.")
            if (!isIdentifierValid(aliasName)) throw IllegalArgumentException("Macro \"$trimmedName\" options.aliases \"$aliasName\" is invalid.")
            if (aliasName.lowercase() == trimmedName.lowercase()) throw IllegalArgumentException("Macro \"$trimmedName\" options.aliases cannot be the same as the macro name (insensitive).")
            MacroAliasDef(aliasName, a.visible)
        }

        var minArgs = 0
        var maxArgs = 0
        var unnamedArgDefs = emptyList<MacroUnnamedArgDef>()
        when {
            options.unnamedArgs != null -> {
                var foundOptional = false
                unnamedArgDefs = options.unnamedArgs.mapIndexed { index, def ->
                    if (foundOptional && !def.optional) {
                        throw IllegalArgumentException("Macro \"$trimmedName\" options.unnamedArgs[$index] is required but follows an optional argument. Optional args must be a suffix.")
                    }
                    if (def.optional) foundOptional = true
                    if (def.type.any { it !in MacroValueType.entries }) {
                        throw IllegalArgumentException("Macro \"$trimmedName\" options.unnamedArgs[$index].type must be one of \"string\", \"integer\", \"number\", or \"boolean\" when provided.")
                    }
                    def
                }
                maxArgs = unnamedArgDefs.size
                minArgs = unnamedArgDefs.indexOfFirst { it.optional }.let { if (it == -1) maxArgs else it }
            }
            options.unnamedArgsCount != null -> {
                require(options.unnamedArgsCount >= 0) { "Macro \"$trimmedName\" options.unnamedArgs must be a non-negative integer when provided." }
                minArgs = options.unnamedArgsCount
                maxArgs = options.unnamedArgsCount
                unnamedArgDefs = List(options.unnamedArgsCount) { i ->
                    MacroUnnamedArgDef(name = "arg${i + 1}", type = listOf(MacroValueType.STRING), sampleValue = "arg${i + 1}")
                }
            }
        }

        return MacroDefinition(
            name = trimmedName,
            aliases = aliases,
            category = options.category.ifBlank { MacroCategory.UNCATEGORIZED },
            minArgs = minArgs,
            maxArgs = maxArgs,
            unnamedArgDefs = unnamedArgDefs,
            list = options.list,
            strictArgs = options.strictArgs,
            description = options.description,
            returns = options.returns?.ifBlank { "<empty string>" },
            returnType = options.returnType,
            displayOverride = options.displayOverride,
            exampleUsage = options.exampleUsage,
            delayArgResolution = options.delayArgResolution,
            handler = options.handler,
        )
    }
}

/** ST:MacroRegistry.js:693 isIdentifierValid。 */
fun isIdentifierValid(name: String): Boolean =
    name.isNotBlank() && (name == "//" || MACRO_IDENTIFIER_PATTERN.matches(name))

/** ST:MacroRegistry.js:707 isArgsValid。 */
private fun isArgsValid(def: MacroDefinition, args: List<String>): Boolean {
    val hasListArgs = def.list != null
    if (!hasListArgs) return args.size >= def.minArgs && args.size <= def.maxArgs
    val minRequired = def.minArgs + def.list!!.min
    if (args.size < minRequired) return false
    val listCount = maxOf(0, args.size - def.maxArgs)
    if (def.list.max != null && listCount > def.list.max) return false
    return true
}

/** ST:MacroRegistry.js:736 validateArgTypes。 */
private fun validateArgTypes(call: MacroCall, def: MacroDefinition, unnamedArgs: List<String>) {
    if (def.unnamedArgDefs.isEmpty()) return
    val count = minOf(def.unnamedArgDefs.size, unnamedArgs.size)
    for (i in 0 until count) {
        val argDef = def.unnamedArgDefs[i]
        val value = unnamedArgs[i]
        if (!argDef.type.any { isValueOfType(value, it) }) {
            val argName = argDef.name
            val optionalLabel = if (argDef.optional) " (optional)" else ""
            val message = "Macro \"${call.name}\" (position ${i + 1}$optionalLabel) argument \"$argName\" expected type ${argDef.type} but got value \"$value\"."
            if (def.strictArgs) throw MacroRuntimeError(message, call, def)
            call.env.warn(message)
        }
    }
}

/** ST:MacroRegistry.js:763 isValueOfType。 */
private fun isValueOfType(value: String, type: MacroValueType): Boolean {
    val trimmed = Js.trim(value)
    return when (type) {
        MacroValueType.STRING -> true
        MacroValueType.INTEGER -> Regex("^-?\\d+$").matches(trimmed)
        MacroValueType.NUMBER -> Js.stringToNumber(trimmed).let { !it.isNaN() }
        MacroValueType.BOOLEAN -> Js.isTrueBoolean(trimmed) || Js.isFalseBoolean(trimmed)
    }
}
