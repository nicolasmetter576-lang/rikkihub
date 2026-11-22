package me.rerere.tavern.macro.macros

import me.rerere.tavern.macro.MacroAliasDef
import me.rerere.tavern.macro.MacroCategory
import me.rerere.tavern.macro.MacroDefinitionOptions
import me.rerere.tavern.macro.MacroEngine
import me.rerere.tavern.macro.MacroRegistry
import me.rerere.tavern.macro.MacroUnnamedArgDef
import me.rerere.tavern.macro.MacroValueType
import me.rerere.tavern.variable.VariableScope

/**
 * 变量宏。ST:public/scripts/macros/definitions/variable-macros.js。
 * 18 个（9 本地 + 9 全局），薄封装 [VariableScope]；`ctx.variables` 打注入的 env.variables。
 */
fun registerVariableMacros(registry: MacroRegistry) {
    fun arg(name: String, types: List<MacroValueType> = listOf(MacroValueType.STRING)) =
        MacroUnnamedArgDef(name, type = types)
    val strOrNum = listOf(MacroValueType.STRING, MacroValueType.NUMBER)

    fun register(
        name: String,
        argDefs: List<MacroUnnamedArgDef>,
        desc: String,
        returns: String,
        returnType: List<MacroValueType> = listOf(MacroValueType.STRING, MacroValueType.NUMBER),
        aliases: List<MacroAliasDef> = emptyList(),
        handler: (VariableScope, List<String>) -> Any?,
    ) {
        registry.registerMacro(name, MacroDefinitionOptions(
            aliases = aliases,
            category = MacroCategory.VARIABLE,
            unnamedArgs = argDefs,
            description = desc,
            returns = returns,
            returnType = returnType,
            handler = { ctx ->
                val scope = if (name.contains("global", ignoreCase = true)) ctx.env.variables.global else ctx.env.variables.local
                handler(scope, ctx.unnamedArgs)
            },
        ))
    }

    // —— 本地 ——
    register("setvar", listOf(arg("name"), arg("value", strOrNum)), "Sets a local variable.", "") { s, a -> s.set(a[0], a[1]); "" }
    register("addvar", listOf(arg("name"), arg("value", strOrNum)), "Adds a value to an existing local variable.", "") { s, a -> s.add(a[0], a[1]); "" }
    register("incvar", listOf(arg("name")), "Increments a local variable by 1 and returns the new value.", "The new value.", listOf(MacroValueType.NUMBER)) { s, a -> s.inc(a[0]) }
    register("decvar", listOf(arg("name")), "Decrements a local variable by 1 and returns the new value.", "The new value.", listOf(MacroValueType.NUMBER)) { s, a -> s.dec(a[0]) }
    register("getvar", listOf(arg("name")), "Gets the value of a local variable.", "The value.") { s, a -> s.get(a[0]) }
    register("hasvar", listOf(arg("name")), "Checks if a local variable exists.", "\"true\" if the variable exists, \"false\" otherwise.", listOf(MacroValueType.STRING), listOf(MacroAliasDef("varexists"))) { s, a -> if (s.has(a[0])) "true" else "false" }
    register("deletevar", listOf(arg("name")), "Deletes a local variable.", "", listOf(MacroValueType.STRING), listOf(MacroAliasDef("flushvar"))) { s, a -> s.del(a[0]); "" }
    register("setvarkey", listOf(arg("name"), arg("key", strOrNum), arg("value", strOrNum)), "Sets a value at a specific key or index.", "", listOf(MacroValueType.STRING), listOf(MacroAliasDef("setvarindex"))) { s, a -> s.set(a[0], a[2], a[1]); "" }
    register("getvarkey", listOf(arg("name"), arg("key", strOrNum)), "Gets a value at a specific key or index.", "The value at the key or index.", strOrNum, listOf(MacroAliasDef("getvarindex"))) { s, a -> s.get(a[0], a[1]) }

    // —— 全局 ——
    register("setglobalvar", listOf(arg("name"), arg("value", strOrNum)), "Sets a global variable.", "") { s, a -> s.set(a[0], a[1]); "" }
    register("addglobalvar", listOf(arg("name"), arg("value", strOrNum)), "Adds a value to an existing global variable.", "") { s, a -> s.add(a[0], a[1]); "" }
    register("incglobalvar", listOf(arg("name")), "Increments a global variable by 1 and returns the new value.", "The new value.", listOf(MacroValueType.NUMBER)) { s, a -> s.inc(a[0]) }
    register("decglobalvar", listOf(arg("name")), "Decrements a global variable by 1 and returns the new value.", "The new value.", listOf(MacroValueType.NUMBER)) { s, a -> s.dec(a[0]) }
    register("getglobalvar", listOf(arg("name")), "Gets the value of a global variable.", "The value.") { s, a -> s.get(a[0]) }
    register("hasglobalvar", listOf(arg("name")), "Checks if a global variable exists.", "\"true\" if the variable exists, \"false\" otherwise.", listOf(MacroValueType.STRING), listOf(MacroAliasDef("globalvarexists"))) { s, a -> if (s.has(a[0])) "true" else "false" }
    register("deleteglobalvar", listOf(arg("name")), "Deletes a global variable.", "", listOf(MacroValueType.STRING), listOf(MacroAliasDef("flushglobalvar"))) { s, a -> s.del(a[0]); "" }
    register("setglobalvarkey", listOf(arg("name"), arg("key", strOrNum), arg("value", strOrNum)), "Sets a value at a specific key or index in a global variable.", "", listOf(MacroValueType.STRING), listOf(MacroAliasDef("setglobalvarindex"))) { s, a -> s.set(a[0], a[2], a[1]); "" }
    register("getglobalvarkey", listOf(arg("name"), arg("key", strOrNum)), "Gets a value at a specific key or index in a global variable.", "The value at the key or index.", strOrNum, listOf(MacroAliasDef("getglobalvarindex"))) { s, a -> s.get(a[0], a[1]) }
}
