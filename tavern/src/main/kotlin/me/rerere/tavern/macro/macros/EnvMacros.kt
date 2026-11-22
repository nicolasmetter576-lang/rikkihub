package me.rerere.tavern.macro.macros

import me.rerere.tavern.macro.MacroAliasDef
import me.rerere.tavern.macro.MacroCategory
import me.rerere.tavern.macro.MacroDefinitionOptions
import me.rerere.tavern.macro.MacroRegistry
import me.rerere.tavern.macro.MacroUnnamedArgDef
import me.rerere.tavern.macro.MacroValueType
import me.rerere.tavern.variable.Js

/**
 * env / 角色卡 / 系统字段宏。ST:public/scripts/macros/definitions/env-macros.js + state-macros.js。
 * group/groupNotMuted/notChar 按单人语义（names 字段由 env 填好，单人时 = char/char/user）。
 */
fun registerEnvMacros(registry: MacroRegistry) {
    fun names(name: String, desc: String, aliases: List<MacroAliasDef> = emptyList(), get: (me.rerere.tavern.macro.MacroEnv) -> String) {
        registry.registerMacro(name, MacroDefinitionOptions(
            aliases = aliases, category = MacroCategory.NAMES, description = desc, returns = desc,
            handler = { ctx -> get(ctx.env) },
        ))
    }
    fun character(name: String, desc: String, aliases: List<MacroAliasDef> = emptyList(), get: (me.rerere.tavern.macro.MacroEnv) -> String) {
        registry.registerMacro(name, MacroDefinitionOptions(
            aliases = aliases, category = MacroCategory.CHARACTER, description = desc, returns = desc,
            handler = { ctx -> get(ctx.env) },
        ))
    }

    names("user", "Your current Persona username.") { it.names.user }
    names("char", "The character's name.") { it.names.char }
    names("group", "Comma-separated list of group member names (including muted) or the character name in solo chats.",
        listOf(MacroAliasDef("charIfNotGroup", visible = false))) { it.names.group }
    names("groupNotMuted", "Comma-separated list of group member names excluding muted members.") { it.names.groupNotMuted }
    names("notChar", "Comma-separated list of all participants except the current speaker.") { it.names.notChar }

    character("charPrompt", "The character's Main Prompt override.") { it.character.charPrompt }
    character("charInstruction", "The character's Post-History Instructions override.") { it.character.charInstruction }
    character("charDescription", "The character's description.", listOf(MacroAliasDef("description"))) { it.character.description }
    character("charPersonality", "The character's personality.", listOf(MacroAliasDef("personality"))) { it.character.personality }
    character("charScenario", "The character's scenario.", listOf(MacroAliasDef("scenario"))) { it.character.scenario }
    character("persona", "Your current Persona description.") { it.character.persona }
    character("mesExamplesRaw", "Unformatted dialogue examples from the character card.") { it.character.mesExamplesRaw }

    // {{mesExamples}} — 偏离：只移植 openai 路径（parseMesExamples 的 blockHeading='<START>\n'）；
    // instruct/非 openai 的 example_separator 分支不做（1A 只走 Chat Completion）
    registry.registerMacro("mesExamples", MacroDefinitionOptions(
        category = MacroCategory.CHARACTER,
        description = "The character's dialogue examples, formatted for instruct mode when enabled.",
        returns = "Formatted dialogue examples.",
        handler = { ctx ->
            var raw = ctx.env.character.mesExamplesRaw
            if (raw.isEmpty() || raw == "<START>") {
                ""
            } else {
                if (!raw.startsWith("<START>")) raw = "<START>\n" + raw.trim()
                val blocks = Regex("<START>", RegexOption.IGNORE_CASE).split(raw).drop(1)
                    .map { "<START>\n${it.trim()}\n" }
                blocks.joinToString("")
            }
        },
    ))

    character("charDepthPrompt", "The character's @ Depth Note.") { it.character.charDepthPrompt }
    character("charCreatorNotes", "Creator notes from the character card.",
        listOf(MacroAliasDef("creatorNotes"))) { it.character.creatorNotes }

    // {{charFirstMessage::i}} / {{greeting::i}}：0=主问候，1+=备选问候
    registry.registerMacro("charFirstMessage", MacroDefinitionOptions(
        aliases = listOf(MacroAliasDef("greeting")),
        category = MacroCategory.CHARACTER,
        unnamedArgs = listOf(MacroUnnamedArgDef("index", optional = true, defaultValue = "0", type = listOf(MacroValueType.INTEGER))),
        description = "The character's first message / greeting.",
        returns = "Character greeting at the given index, or empty string if out of bounds.",
        exampleUsage = listOf("{{greeting}}", "{{greeting::0}}", "{{greeting::1}}"),
        handler = { ctx ->
            val i = Js.stringToNumber(ctx.unnamedArgs.getOrElse(0) { "0" }).toInt()
            if (i == 0) ctx.env.character.firstMessage
            else ctx.env.character.alternateGreetings.getOrNull(i - 1) ?: ""
        },
    ))

    character("charVersion", "The character's version number.",
        listOf(MacroAliasDef("version", visible = false), MacroAliasDef("char_version", visible = false))) { it.character.version }

    // {{model}} -> env.system.model
    registry.registerMacro("model", MacroDefinitionOptions(
        category = MacroCategory.STATE,
        description = "Model name for the currently selected API.",
        returns = "Model name.",
        handler = { ctx -> ctx.env.model },
    ))

    // {{original}}：env.original 为 null 时按内部错误处理（引擎输出原文，上游同）
    registry.registerMacro("original", MacroDefinitionOptions(
        category = MacroCategory.CHARACTER,
        description = "Original message content.",
        returns = "Original message content.",
        handler = { ctx -> ctx.env.original?.invoke() ?: throw IllegalStateException("env.functions.original is not set") },
    ))

    // {{isMobile}}
    registry.registerMacro("isMobile", MacroDefinitionOptions(
        category = MacroCategory.STATE,
        description = "\"true\" if currently running in a mobile environment, \"false\" otherwise.",
        returns = "Whether the environment is mobile.",
        returnType = listOf(MacroValueType.BOOLEAN),
        handler = { ctx -> ctx.env.isMobile.toString() },
    ))
}

/**
 * ST:public/scripts/macros/definitions/state-macros.js。
 * lastGenerationType 由 env 注入（上游是事件跟踪）；hasExtension 恒 false（1A 无扩展系统）。
 */
fun registerStateMacros(registry: MacroRegistry) {
    registry.registerMacro("lastGenerationType", MacroDefinitionOptions(
        category = MacroCategory.STATE,
        description = "Type of the last queued generation request.",
        returns = "Type of the last queued generation request.",
        handler = { ctx -> ctx.env.lastGenerationType },
    ))

    registry.registerMacro("hasExtension", MacroDefinitionOptions(
        category = MacroCategory.STATE,
        unnamedArgs = listOf(MacroUnnamedArgDef("extensionName")),
        description = "Checks if a specific extension is enabled.",
        returns = "true if the extension is enabled, false otherwise.",
        handler = { "false" },
    ))
}
