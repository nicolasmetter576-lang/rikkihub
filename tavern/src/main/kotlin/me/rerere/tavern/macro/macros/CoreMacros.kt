package me.rerere.tavern.macro.macros

import me.rerere.tavern.macro.MACRO_VARIABLE_SHORTHAND_PATTERN
import me.rerere.tavern.macro.MacroAliasDef
import me.rerere.tavern.macro.MacroCategory
import me.rerere.tavern.macro.MacroDefinitionOptions
import me.rerere.tavern.macro.MacroEngine
import me.rerere.tavern.macro.MacroListSpec
import me.rerere.tavern.macro.MacroParser
import me.rerere.tavern.macro.MacroRegistry
import me.rerere.tavern.macro.MacroUnnamedArgDef
import me.rerere.tavern.macro.MacroValueType
import me.rerere.tavern.macro.MacroWalker
import me.rerere.tavern.macro.SeedRandom
import me.rerere.tavern.macro.Droll
import me.rerere.tavern.variable.Js

/**
 * 内置宏注册入口。ST:public/scripts/macros/macro-system.js initRegisterMacros。
 * 顺序与上游一致：core → env → state → chat → time → variables（instruct 不移植）。
 */
fun registerBuiltinMacros(registry: MacroRegistry) {
    registerCoreMacros(registry)
    registerEnvMacros(registry)
    registerStateMacros(registry)
    registerChatMacros(registry)
    registerTimeMacros(registry)
    registerVariableMacros(registry)
}

/**
 * ST:public/scripts/macros/definitions/core-macros.js。
 * 覆盖：space newline noop trim if else input maxPrompt/Context/Response reverse // roll random pick outlet。
 * 不做：banned（textgen 专用，rikkihub 只走 chat completion）。
 */
fun registerCoreMacros(registry: MacroRegistry) {
    // {{space}} -> ' '
    registry.registerMacro("space", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        unnamedArgs = listOf(MacroUnnamedArgDef("count", optional = true, defaultValue = "1", type = listOf(MacroValueType.INTEGER))),
        description = "Returns one or more spaces.",
        returns = "One or more spaces.",
        exampleUsage = listOf("{{space}}", "{{space::4}}"),
        handler = { ctx -> " ".repeat(ctx.unnamedArgs.firstOrNull()?.let { Js.stringToNumber(it) }?.toInt() ?: 1) },
    ))

    // {{newline}} -> '\n'
    registry.registerMacro("newline", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        unnamedArgs = listOf(MacroUnnamedArgDef("count", optional = true, defaultValue = "1", type = listOf(MacroValueType.INTEGER))),
        description = "Inserts one or more newlines.",
        returns = "One or more \\n.",
        exampleUsage = listOf("{{newline}}", "{{newline::2}}"),
        handler = { ctx -> "\n".repeat(ctx.unnamedArgs.firstOrNull()?.let { Js.stringToNumber(it) }?.toInt() ?: 1) },
    ))

    // {{noop}} -> ''
    registry.registerMacro("noop", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        description = "Does nothing and produces an empty string.",
        returns = "",
        handler = { "" },
    ))

    // {{trim}}：非作用域时原样返回（由后处理器吞掉）；作用域时返回已 auto-trim 的内容
    registry.registerMacro("trim", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        unnamedArgs = listOf(MacroUnnamedArgDef("content", optional = true)),
        description = "Trims whitespace.",
        returns = "",
        handler = { ctx ->
            if (ctx.isScoped) ctx.unnamedArgs.getOrElse(0) { "" } else "{{trim}}"
        },
    ))

    /**
     * 在 raw content 的嵌套深度 0 找第一个 `{{else}}`。
     * ST:core-macros.js:104 splitOnTopLevelElse。
     */
    fun splitOnTopLevelElse(content: String): Pair<String, String?> {
        val parsed = MacroParser.parseDocument(content)
        val macroNodes = parsed.cst?.nodes("macro") ?: return content to null
        var depth = 0
        for (macroNode in macroNodes) {
            val info = MacroWalker.extractMacroInfo(macroNode) ?: continue
            // 只把 `{{if}}` 单参数（仅条件）算作作用域块；`{{if c::x}}` 内联不算
            if (info.name == "if" && !info.isClosing && info.argCount == 1) {
                depth++
            } else if (info.name == "if" && info.isClosing) {
                depth--
            } else if (info.name == "else" && depth == 0) {
                return content.substring(0, info.startOffset) to content.substring(info.endOffset + 1)
            }
        }
        return content to null
    }

    // {{if condition}}content{{/if}} — delayArgResolution：条件与内容都拿原文，自选分支再求值
    registry.registerMacro("if", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        unnamedArgs = listOf(
            MacroUnnamedArgDef("condition"),
            MacroUnnamedArgDef("content"),
        ),
        description = "Conditional macro.",
        returns = "The content if condition is truthy, else branch or empty string otherwise.",
        displayOverride = "{{if condition}}then{{else}}other{{/if}}",
        exampleUsage = listOf("{{if description}}# Description\n{{description}}{{/if}}"),
        delayArgResolution = true,
        handler = { ctx ->
            val rawCondition = ctx.unnamedArgs.getOrElse(0) { "" }
            val rawContent = ctx.unnamedArgs.getOrElse(1) { "" }

            var inverted = false
            var condition = rawCondition
            if (Regex("^\\s*!").containsMatchIn(rawCondition)) {
                inverted = true
                condition = rawCondition.replace(Regex("^\\s*!\\s*"), "")
            }

            condition = ctx.resolve(condition, 0)

            // 变量简写（.x / $x）→ getvar/getglobalvar
            val shorthandMatch = Regex("^([.$])(${MACRO_VARIABLE_SHORTHAND_PATTERN.pattern})$").find(condition)
            if (shorthandMatch != null) {
                val varMacro = if (shorthandMatch.groupValues[1] == ".") "getvar" else "getglobalvar"
                condition = ctx.resolve("{{${varMacro}::${shorthandMatch.groupValues[2]}}}", 0)
            } else {
                // 已注册的零参宏名直接当宏求值
                val macroDef = registry.getPrimaryMacro(condition)
                if (macroDef != null && macroDef.minArgs == 0) {
                    condition = ctx.resolve("{{$condition}}", 0)
                }
            }

            var isFalsy = condition.isEmpty() || Js.isFalseBoolean(condition)
            if (inverted) isFalsy = !isFalsy

            val (thenBranch, elseBranch) = splitOnTopLevelElse(rawContent)
            val chosen = if (!isFalsy) thenBranch else elseBranch
            if (chosen == null) return@MacroDefinitionOptions ""

            var result = ctx.resolve(chosen, 0)
            if (!ctx.flags.preserveWhitespace) result = ctx.trimContent(result)
            result
        },
    ))

    // {{else}} -> ELSE_MARKER（顶层使用时后处理器清理）
    registry.registerMacro("else", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        description = "Marks the else branch inside a scoped {{if}} block.",
        returns = "Invisible marker.",
        handler = { MacroEngine.ELSE_MARKER },
    ))

    // {{input}} -> 注入的输入框内容
    registry.registerMacro("input", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        description = "Current text from the send textarea.",
        returns = "Current text from the send textarea.",
        handler = { ctx -> ctx.env.input },
    ))

    // {{maxPrompt}} / {{maxContext}} / {{maxResponse}}（各带 *Tokens 别名）
    registry.registerMacro("maxPrompt", MacroDefinitionOptions(
        aliases = listOf(MacroAliasDef("maxPromptTokens", visible = true)),
        category = MacroCategory.STATE,
        description = "Maximum prompt context size.",
        returns = "Maximum prompt context size.",
        returnType = listOf(MacroValueType.INTEGER),
        handler = { ctx -> ctx.env.maxPromptTokens.toString() },
    ))
    registry.registerMacro("maxContext", MacroDefinitionOptions(
        aliases = listOf(MacroAliasDef("maxContextTokens", visible = true)),
        category = MacroCategory.STATE,
        description = "Maximum context token limit.",
        returns = "Maximum context token limit.",
        returnType = listOf(MacroValueType.INTEGER),
        handler = { ctx -> ctx.env.maxContextTokens.toString() },
    ))
    registry.registerMacro("maxResponse", MacroDefinitionOptions(
        aliases = listOf(MacroAliasDef("maxResponseTokens", visible = true)),
        category = MacroCategory.STATE,
        description = "Maximum response token limit.",
        returns = "Maximum response token limit.",
        returnType = listOf(MacroValueType.INTEGER),
        handler = { ctx -> ctx.env.maxResponseTokens.toString() },
    ))

    // {{reverse::str}}
    registry.registerMacro("reverse", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        unnamedArgs = listOf(MacroUnnamedArgDef("value")),
        description = "Reverses the characters of the argument provided.",
        returns = "Reversed string.",
        exampleUsage = listOf("{{reverse::I am Lana}}"),
        handler = { ctx -> ctx.unnamedArgs[0].reversed() },
    ))

    // {{// ...}} 注释宏（含 scoped 用法 {{//}}x{{///}}）；别名 {{comment}}
    registry.registerMacro("//", MacroDefinitionOptions(
        aliases = listOf(MacroAliasDef("comment", visible = false)),
        category = MacroCategory.UTILITY,
        unnamedArgs = listOf(MacroUnnamedArgDef("comment")),
        description = "Comment macro that produces an empty string.",
        returns = "",
        displayOverride = "{{// ...}}",
        exampleUsage = listOf("{{// This is a comment}}"),
        handler = { "" },
    ))

    // {{roll::NdM±K}}
    registry.registerMacro("roll", MacroDefinitionOptions(
        category = MacroCategory.RANDOM,
        unnamedArgs = listOf(MacroUnnamedArgDef("formula", sampleValue = "1d20")),
        description = "Rolls dice using droll syntax.",
        returns = "Dice roll result.",
        returnType = listOf(MacroValueType.INTEGER),
        exampleUsage = listOf("{{roll::1d20}}", "{{roll::6}}", "{{roll::3d6+4}}"),
        handler = { ctx ->
            var formula = ctx.unnamedArgs[0]
            if (Regex("^\\d+$").matches(formula)) formula = "1d$formula"
            if (!Droll.validate(formula)) {
                ctx.warn("Invalid roll formula: $formula", null)
                ""
            } else {
                Droll.roll(formula, ctx.env.random)?.toString() ?: ""
            }
        },
    ))

    // 单参数列表兜底：'::' 优先，否则按逗号（\, 转义逗号）
    fun readSingleArgsRandomList(listString: String): List<String> {
        if (listString.contains("::")) {
            return listString.split("::").map { Js.trim(it) }
        }
        return listString
            .replace("\\,", "�COMMA�")
            .split(',')
            .map { Js.trim(it).replace("�COMMA�", ",") }
    }

    // {{random::a::b}} — 每次求值都重掷
    registry.registerMacro("random", MacroDefinitionOptions(
        category = MacroCategory.RANDOM,
        list = MacroListSpec(0, null),
        description = "Picks a random item from a list.",
        returns = "Randomly selected item from the list.",
        exampleUsage = listOf("{{random::blonde::brown::red::black::blue}}"),
        handler = { ctx ->
            var list = ctx.list ?: emptyList()
            if (list.size == 1) list = readSingleArgsRandomList(list[0])
            if (list.isEmpty()) {
                ""
            } else {
                // 偏离：上游用带熵的 seedrandom（会话内不可复现），这里用注入的随机源
                val idx = kotlin.math.floor(ctx.env.random.nextDouble() * list.size).toInt()
                list[idx]
            }
        },
    ))

    // {{pick::a::b}} — 同聊天+同位置结果稳定
    registry.registerMacro("pick", MacroDefinitionOptions(
        category = MacroCategory.RANDOM,
        list = MacroListSpec(0, null),
        description = "Picks a random item from a list, stable for a given chat and macro position.",
        returns = "Stable randomly selected item from the list.",
        exampleUsage = listOf("{{pick::blonde::brown::red::black::blue}}"),
        handler = { ctx ->
            var list = ctx.list ?: emptyList()
            if (list.size == 1) list = readSingleArgsRandomList(list[0])
            if (list.isEmpty()) {
                ""
            } else {
                // ST:core-macros.js:401 种子 = getStringHash(join('-', 4 因子))；
                // 上游 filter(it !== null)：rerollSeed 未设时整个段被滤掉（'…-0'，无尾横线）。
                // env.chatIdHash 覆盖会话缓存（ST:chat_metadata.chat_id_hash）；否则按 chatId 现算
                val chatIdHash = ctx.env.chatIdHash ?: Js.getStringHash(ctx.env.chatId ?: "")
                val rawContentHash = ctx.env.contentHash
                val combinedSeedString = listOfNotNull(
                    Js.numberToString(chatIdHash),
                    Js.numberToString(rawContentHash),
                    ctx.globalOffset.toString(),
                    ctx.env.pickRerollSeed,
                ).joinToString("-")
                val finalSeed = Js.getStringHash(combinedSeedString)
                val rng = SeedRandom(Js.numberToString(finalSeed))
                val idx = kotlin.math.floor(rng.nextDouble() * list.size).toInt()
                list[idx]
            }
        },
    ))

    // {{outlet::key}} -> 世界书 outlet 内容
    registry.registerMacro("outlet", MacroDefinitionOptions(
        category = MacroCategory.UTILITY,
        unnamedArgs = listOf(MacroUnnamedArgDef("key", sampleValue = "my-outlet-key")),
        description = "Returns the world info outlet prompt for a given outlet key.",
        returns = "World info outlet prompt.",
        exampleUsage = listOf("{{outlet::character-achievements}}"),
        handler = { ctx ->
            val outlet = ctx.unnamedArgs[0]
            if (outlet.isEmpty()) {
                ""
            } else {
                ctx.env.outletEntries[outlet]?.joinToString("\n") ?: ""
            }
        },
    ))
}
