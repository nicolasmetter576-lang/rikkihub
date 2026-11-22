package me.rerere.tavern.regex

import me.rerere.tavern.core.JsMatch
import me.rerere.tavern.core.JsRegexFactory

/**
 * 正则引擎需要的宏求值。对应上游 `substituteParams` / `substituteParamsExtended`。
 */
interface RegexMacroHost {
    /**
     * @param nameOverride 临时覆盖 `{{char}}`（上游的 `name2Override`）
     * @param postProcess 对每个宏的返回值做后处理（上游 `substituteParamsExtended` 的第三个参数）
     */
    fun substitute(text: String, nameOverride: String? = null, postProcess: ((String) -> String)? = null): String
}

/** ST:public/scripts/extensions/regex/engine.js 的 RegexParams。 */
data class RegexParams(
    val characterOverride: String? = null,
    val isMarkdown: Boolean = false,
    val isPrompt: Boolean = false,
    val isEdit: Boolean = false,
    val depth: Int? = null,
)

/**
 * 酒馆正则的执行（ST:public/scripts/extensions/regex/engine.js:281-460）。
 *
 * 脚本列表由调用方按上游顺序拼好并按“允许名单”过滤：全局 → 预设 → 角色
 * （上游 SCRIPT_TYPES 的声明顺序，engine.js:11-16；不是“全局→角色→预设”）。
 */
class RegexEngine(private val js: JsRegexFactory) {

    /** ST:engine.js:300-345 getRegexedString。 */
    suspend fun getRegexedString(
        scripts: List<RegexScript>,
        rawString: String,
        placement: Int,
        host: RegexMacroHost,
        params: RegexParams = RegexParams(),
    ): String {
        if (rawString.isEmpty()) return rawString
        var final = rawString
        for (script in scripts) {
            val applies =
                (script.markdownOnly && params.isMarkdown) ||
                    (script.promptOnly && params.isPrompt) ||
                    (!script.markdownOnly && !script.promptOnly && !params.isMarkdown && !params.isPrompt)
            if (!applies) continue
            if (params.isEdit && !script.runOnEdit) continue
            val depth = params.depth
            if (depth != null) {
                val min = script.minDepth
                if (min != null && min >= -1 && depth < min) continue
                val max = script.maxDepth
                if (max != null && max >= 0 && depth > max) continue
            }
            if (placement in script.placement) {
                final = runRegexScript(script, final, host, params.characterOverride)
            }
        }
        return final
    }

    /** ST:engine.js:355-420 runRegexScript。 */
    suspend fun runRegexScript(
        script: RegexScript,
        rawString: String,
        host: RegexMacroHost,
        characterOverride: String? = null,
    ): String {
        if (script.disabled || script.findRegex.isEmpty() || rawString.isEmpty()) return rawString

        val regexString = when (script.substituteRegex) {
            SubstituteFindRegex.NONE -> script.findRegex
            SubstituteFindRegex.RAW -> host.substitute(script.findRegex)
            SubstituteFindRegex.ESCAPED -> host.substitute(script.findRegex, postProcess = ::sanitizeRegexMacro)
            else -> script.findRegex // 上游对未知值同样退回原样
        }
        val regex = js.compileFromString(regexString) ?: return rawString

        val matches = regex.matchAll(rawString)
        if (matches.isEmpty()) return rawString

        val replaceString = MATCH_MACRO.replace(script.replaceString) { "\$0" }
        val out = StringBuilder()
        var last = 0
        for (m in matches) {
            out.append(rawString, last, m.index)
            out.append(replacement(script, replaceString, m, host, characterOverride))
            last = m.index + m.text.length
        }
        out.append(rawString, last, rawString.length)
        return out.toString()
    }

    /** 回调体：ST:engine.js:395-416。`$N`/`$<名字>` 的值直接插入（不再解释 `$`），最后整体求宏。 */
    private fun replacement(
        script: RegexScript,
        replaceString: String,
        m: JsMatch,
        host: RegexMacroHost,
        characterOverride: String?,
    ): String {
        val withGroups = GROUP_REF.replace(replaceString) { ref ->
            val num = ref.groups[1]?.value
            val name = ref.groups[2]?.value
            // 偏离: 上游 `$N` 超出捕获组个数时取到的是回调参数里的 offset/整串/groups 对象（上游缺陷），这里按空串处理。
            val value: String? = when {
                num != null -> when (val n = num.toIntOrNull()) {
                    null -> null
                    0 -> m.text
                    else -> m.groups.getOrNull(n - 1)
                }
                name != null -> m.named[name]
                else -> null
            }
            if (value.isNullOrEmpty()) "" else filterString(value, script.trimStrings, host, characterOverride)
        }
        return host.substitute(withGroups)
    }

    /** ST:engine.js:430-445 filterString。 */
    private fun filterString(raw: String, trimStrings: List<String>, host: RegexMacroHost, characterOverride: String?): String {
        var final = raw
        for (trim in trimStrings) {
            val sub = host.substitute(trim, nameOverride = characterOverride)
            if (sub.isNotEmpty()) final = final.replace(sub, "")
        }
        return final
    }

    private companion object {
        val MATCH_MACRO = Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE)
        // 只用 [0-9]：Android 的 \d 是 Unicode 数字
        val GROUP_REF = Regex("\\$([0-9]+)|\\$<([^>]+)>")
    }
}

/** ST:engine.js:164-185 sanitizeRegexMacro：转义宏值里的正则元字符。 */
fun sanitizeRegexMacro(x: String): String {
    val sb = StringBuilder(x.length + 8)
    for (c in x) {
        when (c) {
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u000B' -> sb.append("\\v")
            '\u000C' -> sb.append("\\f")
            '\u0000' -> sb.append("\\0")
            '.', '^', '$', '*', '+', '?', '{', '}', '[', ']', '\\', '/', '|', '(', ')' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
