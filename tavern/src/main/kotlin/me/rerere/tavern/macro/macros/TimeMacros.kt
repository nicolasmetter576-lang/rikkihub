package me.rerere.tavern.macro.macros

import me.rerere.tavern.macro.MacroAliasDef
import me.rerere.tavern.macro.MacroCategory
import me.rerere.tavern.macro.MacroDefinitionOptions
import me.rerere.tavern.macro.MacroRegistry
import me.rerere.tavern.macro.MacroUnnamedArgDef
import me.rerere.tavern.macro.Moment

/**
 * 时间宏。ST:public/scripts/macros/definitions/time-macros.js。
 * 时钟与时区由 env.clock 注入；`{{timeDiff}}`/`{{idleDuration}}` 用 Moment.humanize 的 en 输出。
 */
fun registerTimeMacros(registry: MacroRegistry) {
    // {{time}} / {{time::UTC±N}}：偏移参数经 moment.utcOffset 语义（<16 按小时，否则按分钟）
    registry.registerMacro("time", MacroDefinitionOptions(
        category = MacroCategory.TIME,
        unnamedArgs = listOf(MacroUnnamedArgDef("offset", optional = true, defaultValue = "null")),
        description = "Current local time, or UTC offset when called as {{time::UTC±(offset)}}",
        returns = "A time string in the format HH:mm.",
        displayOverride = "{{time::[UTC±(offset)]}}",
        exampleUsage = listOf("{{time}}", "{{time::UTC+2}}", "{{time::UTC-7}}"),
        handler = { ctx ->
            val offsetSpec = ctx.unnamedArgs.getOrElse(0) { "" }
            val match = Regex("^UTC([+-]\\d+)$").find(offsetSpec)
            if (offsetSpec.isEmpty() || match == null) {
                Moment.format(ctx.env.clock.nowMillis(), ctx.env.clock.zone, "LT")
            } else {
                val offset = match.groupValues[1].toIntOrNull()
                if (offset == null) {
                    Moment.format(ctx.env.clock.nowMillis(), ctx.env.clock.zone, "LT")
                } else {
                    Moment.format(ctx.env.clock.nowMillis(), Moment.offsetZone(offset), "LT")
                }
            }
        },
    ))

    registry.registerMacro("date", MacroDefinitionOptions(
        category = MacroCategory.TIME,
        description = "Current local date as a string in the local short format.",
        returns = "Current local date in local short format.",
        handler = { ctx -> Moment.format(ctx.env.clock.nowMillis(), ctx.env.clock.zone, "LL") },
    ))

    registry.registerMacro("weekday", MacroDefinitionOptions(
        category = MacroCategory.TIME,
        description = "Current weekday name.",
        returns = "Current weekday name.",
        handler = { ctx -> Moment.format(ctx.env.clock.nowMillis(), ctx.env.clock.zone, "dddd") },
    ))

    registry.registerMacro("isotime", MacroDefinitionOptions(
        category = MacroCategory.TIME,
        description = "Current time in HH:mm format.",
        returns = "Current time in HH:mm format.",
        handler = { ctx -> Moment.format(ctx.env.clock.nowMillis(), ctx.env.clock.zone, "HH:mm") },
    ))

    registry.registerMacro("isodate", MacroDefinitionOptions(
        category = MacroCategory.TIME,
        description = "Current date in YYYY-MM-DD format.",
        returns = "Current date in YYYY-MM-DD format.",
        handler = { ctx -> Moment.format(ctx.env.clock.nowMillis(), ctx.env.clock.zone, "YYYY-MM-DD") },
    ))

    registry.registerMacro("datetimeformat", MacroDefinitionOptions(
        category = MacroCategory.TIME,
        unnamedArgs = listOf(MacroUnnamedArgDef("format", sampleValue = "YYYY-MM-DD HH:mm:ss")),
        description = "Formats the current date/time using the given moment.js format string.",
        returns = "Formatted date/time string.",
        exampleUsage = listOf("{{datetimeformat::YYYY-MM-DD HH:mm:ss}}", "{{datetimeformat::LLLL}}"),
        handler = { ctx -> Moment.format(ctx.env.clock.nowMillis(), ctx.env.clock.zone, ctx.unnamedArgs[0]) },
    ))

    // {{idleDuration}}：最后一条非 system 消息之前的那条 user 消息至今的时长（ST:time-macros.js:121）
    registry.registerMacro("idleDuration", MacroDefinitionOptions(
        aliases = listOf(MacroAliasDef("idle_duration", visible = false)),
        category = MacroCategory.TIME,
        description = "Human-readable duration since the last user message.",
        returns = "Human-readable duration since the last user message.",
        handler = { ctx ->
            val chat = ctx.env.chat
            var lastSendDate: Long? = null
            var takeNext = false
            for (i in chat.indices.reversed()) {
                val m = chat[i]
                if (m.isSystem) continue
                if (m.isUser && takeNext) {
                    lastSendDate = m.sendDateMillis
                    break
                }
                takeNext = true
            }
            if (lastSendDate != null) {
                val diff = (ctx.env.clock.nowMillis() - lastSendDate).toDouble()
                Moment.humanize(diff, withSuffix = false)
            } else {
                "just now"
            }
        },
    ))

    // {{timeDiff::left::right}} -> humanize(true)
    registry.registerMacro("timeDiff", MacroDefinitionOptions(
        category = MacroCategory.TIME,
        unnamedArgs = listOf(
            MacroUnnamedArgDef("left", sampleValue = "2023-01-01 12:00:00"),
            MacroUnnamedArgDef("right", sampleValue = "2023-01-01 15:00:00"),
        ),
        description = "Human-readable difference between two times.",
        returns = "Human-readable difference between two times.",
        displayOverride = "{{timeDiff::left::right}}",
        exampleUsage = listOf("{{ timeDiff :: 2023-01-01 12:00:00 :: 2023-01-01 15:00:00 }}"),
        handler = { ctx ->
            val left = Moment.parse(ctx.unnamedArgs[0], ctx.env.clock.zone)
            val right = Moment.parse(ctx.unnamedArgs[1], ctx.env.clock.zone)
            if (left == null || right == null) {
                // moment.invalid() 的 diff 是 NaN，humanize(NaN) → 'a few seconds'（moment 实际行为）
                Moment.humanize(Double.NaN, withSuffix = true)
            } else {
                Moment.humanize((left - right).toDouble(), withSuffix = true)
            }
        },
    ))
}
