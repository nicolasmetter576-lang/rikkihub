package me.rerere.tavern.worldinfo

import me.rerere.tavern.core.JsRegex
import me.rerere.tavern.core.JsRegexFactory
import me.rerere.tavern.core.JsRegexSyntaxException
import me.rerere.tavern.core.JsRegexTimeoutException

/** 与聊天无关、可按条目选择是否扫描的数据，对应 `WIGlobalScanData`（ST:public/scripts/world-info.js:101）。 */
data class WorldInfoGlobalScanData(
    val personaDescription: String = "",
    val characterDescription: String = "",
    val characterPersonality: String = "",
    val characterDepthPrompt: String = "",
    val scenario: String = "",
    val creatorNotes: String = "",
)

/** `scan_state`：ST:public/scripts/world-info.js:43 */
internal enum class ScanState { NONE, INITIAL, RECURSION, MIN_ACTIVATIONS }

private const val MATCHER = "\u0001"
private const val JOINER = "\n$MATCHER"

/**
 * 一次世界书评估用的扫描缓冲，ST:public/scripts/world-info.js:199 WorldInfoBuffer。
 *
 * [messages] 最新的在前（与上游一致）；已经按“包含名字”设置拼好 `名字: 正文`。
 */
internal class WorldInfoBuffer(
    messages: List<String>,
    private val globalScan: WorldInfoGlobalScanData,
    private val settings: WorldInfoSettings,
    private val regexFactory: JsRegexFactory,
) {
    /** ST:public/scripts/world-info.js:243 #initDepthBuffer：空消息不占位，但会让数组在它之前的位置保持为空串。 */
    private val depthBuffer: List<String>
    private val recurseBuffer = ArrayList<String>()
    private val injectBuffer = ArrayList<String>()
    private var skew = 0
    private val startDepth = 0

    init {
        val buf = ArrayList<String>()
        for (depth in 0 until WI_MAX_SCAN_DEPTH) {
            val m = messages.getOrNull(depth)
            if (!m.isNullOrEmpty()) {
                while (buf.size <= depth) buf += ""
                buf[depth] = jsTrim(m)
            }
            // break if last message is reached
            if (depth == messages.size - 1) break
        }
        depthBuffer = buf
    }

    /** ST:public/scripts/world-info.js:260 #transformString */
    private fun transformString(str: String, entry: WorldInfoEntry): String {
        val caseSensitive = entry.caseSensitive ?: settings.caseSensitive
        return if (caseSensitive) str else str.lowercase()
    }

    /** ST:public/scripts/world-info.js:271 get：到指定深度为止的消息 + 全局扫描数据 + 注入缓冲 + 递归缓冲。 */
    fun get(entry: WorldInfoEntry, scanState: ScanState): String {
        var depth = entry.scanDepth ?: getDepth()
        if (depth <= startDepth) return ""
        if (depth > WI_MAX_SCAN_DEPTH) depth = WI_MAX_SCAN_DEPTH

        val sb = StringBuilder(MATCHER)
        val end = minOf(depth, depthBuffer.size)
        for (i in startDepth until end) {
            if (i > startDepth) sb.append(JOINER)
            sb.append(depthBuffer[i])
        }

        if (entry.matchPersonaDescription && globalScan.personaDescription.isNotEmpty()) {
            sb.append(JOINER).append(globalScan.personaDescription)
        }
        if (entry.matchCharacterDescription && globalScan.characterDescription.isNotEmpty()) {
            sb.append(JOINER).append(globalScan.characterDescription)
        }
        if (entry.matchCharacterPersonality && globalScan.characterPersonality.isNotEmpty()) {
            sb.append(JOINER).append(globalScan.characterPersonality)
        }
        if (entry.matchCharacterDepthPrompt && globalScan.characterDepthPrompt.isNotEmpty()) {
            sb.append(JOINER).append(globalScan.characterDepthPrompt)
        }
        if (entry.matchScenario && globalScan.scenario.isNotEmpty()) {
            sb.append(JOINER).append(globalScan.scenario)
        }
        if (entry.matchCreatorNotes && globalScan.creatorNotes.isNotEmpty()) {
            sb.append(JOINER).append(globalScan.creatorNotes)
        }

        if (injectBuffer.isNotEmpty()) {
            sb.append(JOINER).append(injectBuffer.joinToString(JOINER))
        }

        // Min activations should not include the recursion buffer
        if (recurseBuffer.isNotEmpty() && scanState != ScanState.MIN_ACTIVATIONS) {
            sb.append(JOINER).append(recurseBuffer.joinToString(JOINER))
        }
        return sb.toString()
    }

    /**
     * ST:public/scripts/world-info.js:341 matchKeys
     * 关键词是 `/模式/标志` 时按正则匹配（覆盖大小写/整词设置），否则按条目/全局设置做子串或整词匹配。
     */
    suspend fun matchKeys(haystack: String, needle: String, entry: WorldInfoEntry): Boolean {
        // If the needle is a regex, we do regex pattern matching and override all the other options
        val keyRegex = parseRegexFromString(needle, regexFactory)
        if (keyRegex != null) {
            return try {
                keyRegex.test(haystack)
            } catch (_: JsRegexTimeoutException) {
                false
            }
        }

        // Otherwise we do normal matching of plaintext with the chosen entry settings
        val hay = transformString(haystack, entry)
        val transformed = transformString(needle, entry)
        val matchWholeWords = entry.matchWholeWords ?: settings.matchWholeWords

        if (!matchWholeWords) return hay.contains(transformed)

        if (hasInnerJsWhitespaceSplit(transformed)) {
            // keyWords.length > 1
            return hay.contains(transformed)
        }
        // 上游：new RegExp(`(?:^|\\W)(${escapeRegex(key)})(?:$|\\W)`).test(haystack)
        // 用自定义边界（含标点和其它非 [A-Za-z0-9_] 字符）。这里直接按定义查找，等价且不必逐词编译正则。
        return containsAsWholeWord(hay, transformed)
    }

    private fun containsAsWholeWord(hay: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val p = hay.indexOf(needle, from)
            if (p < 0) return false
            val before = p == 0 || !isJsWordChar(hay[p - 1])
            val endIdx = p + needle.length
            val after = endIdx == hay.length || !isJsWordChar(hay[endIdx])
            if (before && after) return true
            from = p + 1
            if (from > hay.length) return false
        }
    }

    fun addRecurse(message: String) {
        recurseBuffer += message
    }

    fun addInject(message: String) {
        injectBuffer += message
    }

    fun hasRecurse(): Boolean = recurseBuffer.isNotEmpty()

    fun advanceScan() {
        skew++
    }

    /** 设置的扫描深度 + 当前偏移。 */
    fun getDepth(): Int = settings.depth + skew

    /** ST:public/scripts/world-info.js:427 getScore：条目命中的关键词数（只有正向逻辑计分）。 */
    suspend fun getScore(entry: WorldInfoEntry, scanState: ScanState): Int {
        val bufferState = get(entry, scanState)
        var primaryScore = 0
        var secondaryScore = 0

        for (key in entry.key) if (matchKeys(bufferState, key, entry)) primaryScore++
        for (key in entry.keysecondary) if (matchKeys(bufferState, key, entry)) secondaryScore++

        // No keys == no score
        if (entry.key.isEmpty()) return 0

        // Only positive logic influences the score
        if (entry.keysecondary.isNotEmpty()) {
            when (entry.selectiveLogic) {
                WorldInfoLogic.AND_ANY -> return primaryScore + secondaryScore
                WorldInfoLogic.AND_ALL ->
                    return if (secondaryScore == entry.keysecondary.size) primaryScore + secondaryScore else primaryScore
            }
        }
        return primaryScore
    }
}

/**
 * ST:public/scripts/world-info.js:2901 parseRegexFromString
 * 解析 `/模式/标志`；不合法（格式不对、模式里有未转义的 `/`、编译失败）返回 null。
 */
internal suspend fun parseRegexFromString(input: String, factory: JsRegexFactory): JsRegex? {
    val parsed = splitRegexLiteral(input) ?: return null
    return try {
        factory.compile(parsed.first, parsed.second)
    } catch (_: JsRegexSyntaxException) {
        null
    }
}

/**
 * 上游 `input.match(/^\/([\w\W]+?)\/([gimsuy]*)$/)` 之后的处理，不依赖正则引擎：
 * 惰性模式 + 末尾只能是标志，所以结束分隔符必然是最后一个 `/`。
 */
internal fun splitRegexLiteral(input: String): Pair<String, String>? {
    if (input.length < 3 || input[0] != '/') return null
    val end = input.lastIndexOf('/')
    if (end < 2) return null // 模式至少一个字符
    val flags = input.substring(end + 1)
    if (!flags.all { it in "gimsuy" }) return null
    var pattern = input.substring(1, end)

    // If we find any unescaped slash delimiter, we also exit out.
    for (i in pattern.indices) {
        if (pattern[i] == '/' && (i == 0 || pattern[i - 1] != '\\')) return null
    }

    // 上游 `pattern.replace('\\/', '/')`：字符串形式只替换第一处。
    val idx = pattern.indexOf("\\/")
    if (idx >= 0) pattern = pattern.substring(0, idx) + "/" + pattern.substring(idx + 2)
    return pattern to flags
}
