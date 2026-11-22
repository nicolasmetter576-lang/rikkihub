package me.rerere.tavern.worldinfo

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.floor
import me.rerere.tavern.core.ChatLine
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.core.JsRegexFactory
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.core.WorldInfoDepthEntry
import me.rerere.tavern.core.WorldInfoExampleEntry
import me.rerere.tavern.core.WorldInfoPromptResult

/**
 * 世界书引擎对外部世界的全部依赖。差分测试里这些都被固定或记录。
 */
interface WorldInfoHost {
    /**
     * 对应上游的 `substituteParams`。关键词（每次扫描、每个条目、每个关键词都会调用）和条目内容（概率通过后调用一次）
     * 都会经过它；宏有副作用（`{{setvar}}` 等），所以调用次数和顺序必须与上游一致，引擎不做任何缓存。
     */
    fun substitute(text: String): String

    /**
     * 对应上游 `getRegexedString(text, regex_placement.WORLD_INFO, { depth, isMarkdown: false, isPrompt: true })`。
     * [depth] 只有 atDepth 条目才有值（`entry.depth ?? 4`），其它位置为 null。
     */
    suspend fun applyWorldInfoRegex(text: String, depth: Int?): String

    val regexFactory: JsRegexFactory

    /** 纯文本的 token 数（cl100k）。ST 的计数开销由引擎加，见 [WorldInfoEngine.ST_TEXT_COUNT_OVERHEAD]。 */
    fun countTokens(text: String): Int

    /** 对应 `Math.random()`：概率检查、包含组加权随机各取一次。 */
    val random: TavernRandom
}

/** 角色卡相关的过滤信息（条目的 `characterFilter`）。 */
data class WorldInfoCharacterContext(
    /** 角色卡文件名（不含扩展名），对应 `getCharaFilename()`。 */
    val fileName: String? = null,
    /** 当前角色的标签 id；null 表示不按标签过滤（上游找不到 tagKey / tagMap 条目时的行为）。 */
    val tagIds: List<String>? = null,
)

/**
 * 预演（dryRun）里对概率条目的处理。
 * 上游 dryRun 仍然会掷骰；方案要求预演不能带来随机结果，所以默认当作没有激活。
 */
enum class DryRunProbability {
    /** 偏离: 预演里需要掷骰的条目一律不激活（上游会掷骰）。 */
    NEVER_ACTIVATE,

    /** 与上游一致，预演也掷骰（差分测试用）。 */
    ROLL,
}

/** “激活记录”里的一行。 */
data class ActivatedWorldInfoEntry(
    val book: String,
    val uid: Int,
    /** 条目哈希，和计时效果里记录的 `hash` 是同一个值。 */
    val hash: Long,
    val comment: String,
    val position: Int,
    val order: Int,
    val depth: Int,
    val role: Int?,
    val outletName: String,
    /** 宏求值之后、提示词正则之前的内容。 */
    val content: String,
)

data class WorldInfoCheckResult(
    val prompt: WorldInfoPromptResult,
    /** 按激活顺序。 */
    val activated: List<ActivatedWorldInfoEntry>,
    /** 更新后的粘滞/冷却状态；dryRun 时除过期清理外不变。 */
    val timedEffects: TimedEffectsState,
    /** 本次评估是否撞到 token 预算（调用方据此提醒）。 */
    val budgetOverflowed: Boolean,
)

/**
 * 世界书激活引擎，逐段移植 `ST:public/scripts/world-info.js` 的 `checkWorldInfo` 及其依赖。
 */
class WorldInfoEngine(private val host: WorldInfoHost) {

    /**
     * ST:public/scripts/world-info.js:892 getWorldInfoPrompt + :4709 checkWorldInfo
     *
     * @param chat 最旧的在前，调用方已经去掉不参与扫描的消息；引擎按“包含名字”设置拼 `名字: 正文` 并反向（最新在前）。
     * @param maxContext 本次生成的最大上下文，预算 = 百分比 × maxContext。
     */
    suspend fun check(
        chat: List<ChatLine>,
        books: WorldInfoBooks,
        settings: WorldInfoSettings,
        maxContext: Int,
        timedEffects: TimedEffectsState = TimedEffectsState.EMPTY,
        generationType: GenerationType = GenerationType.NORMAL,
        dryRun: Boolean = false,
        globalScanData: WorldInfoGlobalScanData = WorldInfoGlobalScanData(),
        character: WorldInfoCharacterContext = WorldInfoCharacterContext(),
        injectedScanTexts: List<String> = emptyList(),
        dryRunProbability: DryRunProbability = DryRunProbability.NEVER_ACTIVATE,
    ): WorldInfoCheckResult {
        val trigger = generationType.wire
        // ST:public/script.js:4624 chatForWI
        val scanMessages = chat.asReversed().map { if (settings.includeNames) "${it.name}: ${it.text}" else it.text }
        val buffer = WorldInfoBuffer(scanMessages, globalScanData, settings, host.regexFactory)

        for (text in injectedScanTexts) if (text.isNotEmpty()) buffer.addInject(text)

        var scanState = ScanState.INITIAL
        var tokenBudgetOverflowed = false
        var count = 0
        val allActivatedEntries = LinkedHashMap<String, ScanEntry>()
        val failedProbabilityChecks: MutableSet<ScanEntry> = Collections.newSetFromMap(IdentityHashMap())
        var allActivatedText = ""

        var budget = jsRound(settings.budget.toDouble() * maxContext / 100.0).let { if (it == 0) 1 else it }
        if (settings.budgetCap > 0 && budget > settings.budgetCap) budget = settings.budgetCap

        val sortedEntries = books.resolveScanEntries(settings.characterStrategy)
        val timed = WorldInfoTimedEffects(chat.size, sortedEntries, dryRun, timedEffects)
        timed.checkTimedEffects()

        if (sortedEntries.isEmpty()) {
            return WorldInfoCheckResult(WorldInfoPromptResult(), emptyList(), timed.state(), false)
        }

        // Represents the delay levels for entries that are delayed until recursion
        val availableRecursionDelayLevels = sortedEntries
            .map { it.entry.delayUntilRecursion }
            .filter { it != 0 }
            .toSortedSet()
            .toMutableList()
        var currentRecursionDelayLevel = availableRecursionDelayLevels.removeFirstOrNull() ?: 0

        val sortedEntriesIndex = IdentityHashMap<ScanEntry, Int>().also { m -> sortedEntries.forEachIndexed { i, e -> m[e] = i } }

        while (scanState != ScanState.NONE) {
            // if world_info_max_recursion_steps is non-zero min activations are disabled, and vice versa
            if (settings.maxRecursionSteps != 0 && settings.maxRecursionSteps <= count) break

            count++

            // Until decided otherwise, we set the loop to stop scanning after this
            var nextScanState = ScanState.NONE

            // Loop and find all entries that can activate here
            val activatedNow = LinkedHashSet<ScanEntry>()

            for (scan in sortedEntries) {
                val entry = scan.entry
                // Already processed, considered and then skipped entries should still be skipped
                if (scan in failedProbabilityChecks || allActivatedEntries.containsKey(timedKey(scan.world, entry.uid))) continue

                if (entry.disable) continue

                // Check for generation type trigger filter
                if (entry.triggers.isNotEmpty() && trigger !in entry.triggers) continue

                // Check if this entry applies to the character or if it's excluded
                val filter = entry.characterFilter
                if (filter != null && filter.names.isNotEmpty()) {
                    val nameIncluded = character.fileName != null && character.fileName in filter.names
                    val filtered = if (filter.isExclude) nameIncluded else !nameIncluded
                    if (filtered) continue
                }
                if (filter != null && filter.tags.isNotEmpty() && character.tagIds != null) {
                    val includesTag = character.tagIds.any { it in filter.tags }
                    val filtered = if (filter.isExclude) includesTag else !includesTag
                    if (filtered) continue
                }

                val isSticky = timed.isStickyActive(scan)
                val isCooldown = timed.isCooldownActive(scan)
                val isDelay = timed.isDelayActive(scan)

                if (isDelay) continue
                if (isCooldown && !isSticky) continue

                // Only use checks for recursion flags if the scan step was activated by recursion
                if (scanState != ScanState.RECURSION && entry.delayUntilRecursion != 0 && !isSticky) continue
                if (scanState == ScanState.RECURSION && entry.delayUntilRecursion != 0 &&
                    entry.delayUntilRecursion > currentRecursionDelayLevel && !isSticky
                ) continue
                if (scanState == ScanState.RECURSION && settings.recursive && entry.excludeRecursion && !isSticky) continue

                if ("@@activate" in scan.decorators) {
                    activatedNow.add(scan)
                    continue
                }
                if ("@@dont_activate" in scan.decorators) continue

                // 偏离: 不支持 WORLDINFO_FORCE_ACTIVATE（只有向量扩展会触发，向量化不在移植范围）。

                // Now do checks for immediate activations
                if (entry.constant) {
                    activatedNow.add(scan)
                    continue
                }
                if (isSticky) {
                    activatedNow.add(scan)
                    continue
                }

                if (entry.key.isEmpty()) continue

                // Cache the text to scan before the loop, it won't change its content
                val textToScan = buffer.get(entry, scanState)

                // PRIMARY KEYWORDS
                var primaryKeyMatch: String? = null
                for (key in entry.key) {
                    val substituted = host.substitute(key)
                    if (substituted.isNotEmpty() && buffer.matchKeys(textToScan, jsTrim(substituted), entry)) {
                        primaryKeyMatch = key
                        break
                    }
                }
                // 上游 `find` 的结果还要过一次真值判断：空串键不会成为命中键（本来也无法命中）。
                if (primaryKeyMatch.isNullOrEmpty()) continue

                val hasSecondaryKeywords = entry.selective && entry.keysecondary.isNotEmpty()
                if (!hasSecondaryKeywords) {
                    activatedNow.add(scan)
                    continue
                }

                // SECONDARY KEYWORDS
                if (matchSecondaryKeys(entry, textToScan, buffer)) activatedNow.add(scan)
            }

            // Sort the entries for the probability and the budget limit checks
            val newEntries: MutableList<ScanEntry> = if (activatedNow.size > 1) {
                activatedNow.sortedWith { a, b ->
                    val isASticky = if (timed.isStickyActive(a)) 1 else 0
                    val isBSticky = if (timed.isStickyActive(b)) 1 else 0
                    val byS = isBSticky - isASticky
                    if (byS != 0) byS else (sortedEntriesIndex[a] ?: -1) - (sortedEntriesIndex[b] ?: -1)
                }.toMutableList()
            } else {
                activatedNow.toMutableList()
            }

            var newContent = ""
            val textToScanTokens = stTextTokens(allActivatedText)

            filterByInclusionGroups(newEntries, allActivatedEntries, buffer, scanState, timed, settings)

            var ignoresBudget = newEntries.count { it.entry.ignoreBudget }

            for (scan in newEntries) {
                val entry = scan.entry
                if (entry.ignoreBudget) ignoresBudget -= 1
                if (tokenBudgetOverflowed && !entry.ignoreBudget) {
                    if (ignoresBudget > 0) continue
                    break
                }

                if (!verifyProbability(scan, timed, failedProbabilityChecks, dryRun, dryRunProbability)) continue

                // Substitute macros inline, for both this checking and also future processing
                scan.content = host.substitute(scan.content)
                newContent += scan.content + "\n"

                if (!entry.ignoreBudget && (textToScanTokens + stTextTokens(newContent)) >= budget) {
                    tokenBudgetOverflowed = true
                    continue
                }

                allActivatedEntries[timedKey(scan.world, entry.uid)] = scan
            }

            val successfulNewEntries = newEntries.filter { it !in failedProbabilityChecks }
            val successfulNewEntriesForRecursion = successfulNewEntries.filter { !it.entry.preventRecursion }

            // After processing and rolling entries is done, see if we should continue with normal recursion
            if (settings.recursive && !tokenBudgetOverflowed && successfulNewEntriesForRecursion.isNotEmpty()) {
                nextScanState = ScanState.RECURSION
            }

            // If we are inside min activations scan, and we have recursive buffer, we should do a recursive scan
            // before increasing the buffer again
            if (settings.recursive && !tokenBudgetOverflowed && scanState == ScanState.MIN_ACTIVATIONS && buffer.hasRecurse()) {
                nextScanState = ScanState.RECURSION
            }

            // If scanning is planned to stop, but min activations is set and not satisfied, check if we should continue
            val minActivationsNotSatisfied = settings.minActivations > 0 && allActivatedEntries.size < settings.minActivations
            if (nextScanState == ScanState.NONE && !tokenBudgetOverflowed && minActivationsNotSatisfied) {
                val overMax = (settings.minActivationsDepthMax > 0 && buffer.getDepth() > settings.minActivationsDepthMax) ||
                    buffer.getDepth() > chat.size
                if (!overMax) {
                    nextScanState = ScanState.MIN_ACTIVATIONS
                    buffer.advanceScan()
                }
            }

            // If the scan is done, but we still have open "delay until recursion" levels, continue with the next one
            if (nextScanState == ScanState.NONE && availableRecursionDelayLevels.isNotEmpty()) {
                nextScanState = ScanState.RECURSION
                currentRecursionDelayLevel = availableRecursionDelayLevels.removeFirst()
            }

            // Final check if we should really continue scan, and extend the current WI recurse buffer
            scanState = nextScanState
            if (scanState != ScanState.NONE) {
                val text = successfulNewEntriesForRecursion.joinToString("\n") { it.content }
                if (text.isNotEmpty()) {
                    buffer.addRecurse(text)
                    allActivatedText = text + "\n" + allActivatedText
                }
            }
        }

        // ---- BUILDING PROMPT ----
        // Forward-sorted list of entries for joining
        val wiBeforeEntries = ArrayList<String>()
        val wiAfterEntries = ArrayList<String>()
        val emEntries = ArrayList<WorldInfoExampleEntry>()
        val anTopEntries = ArrayList<String>()
        val anBottomEntries = ArrayList<String>()
        val wiDepthEntries = ArrayList<DepthGroup>()
        val wiOutletEntries = LinkedHashMap<String, MutableList<String>>()

        // Appends from insertion order 999 to 1. Use unshift (add at 0) for this purpose
        for (scan in allActivatedEntries.values.sortedWith(compareByDescending { it.entry.order })) {
            val entry = scan.entry
            val regexDepth = if (entry.position == WorldInfoPosition.AT_DEPTH) entry.depth else null
            val content = host.applyWorldInfoRegex(scan.content, regexDepth)

            if (content.isEmpty()) continue

            when (entry.position) {
                WorldInfoPosition.BEFORE -> wiBeforeEntries.add(0, content)
                WorldInfoPosition.AFTER -> wiAfterEntries.add(0, content)
                WorldInfoPosition.EM_TOP -> emEntries.add(0, WorldInfoExampleEntry(before = true, content = content))
                WorldInfoPosition.EM_BOTTOM -> emEntries.add(0, WorldInfoExampleEntry(before = false, content = content))
                WorldInfoPosition.AN_TOP -> anTopEntries.add(0, content)
                WorldInfoPosition.AN_BOTTOM -> anBottomEntries.add(0, content)
                WorldInfoPosition.AT_DEPTH -> {
                    val roleCode = entry.role ?: 0
                    val existing = wiDepthEntries.firstOrNull { it.depth == entry.depth && it.roleCode == roleCode }
                    if (existing != null) existing.entries.add(0, content)
                    else wiDepthEntries += DepthGroup(entry.depth, roleCode, mutableListOf(content))
                }

                WorldInfoPosition.OUTLET -> {
                    // 上游：没有 outlet 名字的条目只告警并跳过
                    if (entry.outletName.isNotEmpty()) wiOutletEntries.getOrPut(entry.outletName) { ArrayList() }.add(content)
                }

                else -> Unit
            }
        }

        val worldInfoBefore = wiBeforeEntries.joinToString("\n")
        val worldInfoAfter = wiAfterEntries.joinToString("\n")

        timed.setTimedEffects(allActivatedEntries.values)

        val activated = allActivatedEntries.values.map {
            ActivatedWorldInfoEntry(
                book = it.world,
                uid = it.entry.uid,
                hash = it.hash,
                comment = it.entry.comment,
                position = it.entry.position,
                order = it.entry.order,
                depth = it.entry.depth,
                role = it.entry.role,
                outletName = it.entry.outletName,
                content = it.content,
            )
        }

        val prompt = WorldInfoPromptResult(
            worldInfoString = worldInfoBefore + worldInfoAfter,
            worldInfoBefore = worldInfoBefore,
            worldInfoAfter = worldInfoAfter,
            worldInfoExamples = emEntries,
            worldInfoDepth = wiDepthEntries.map { WorldInfoDepthEntry(it.depth, PromptRole.fromCode(it.roleCode), it.entries) },
            anBefore = anTopEntries,
            anAfter = anBottomEntries,
            outletEntries = wiOutletEntries,
        )
        return WorldInfoCheckResult(prompt, activated, timed.state(), tokenBudgetOverflowed)
    }

    private class DepthGroup(val depth: Int, val roleCode: Int, val entries: MutableList<String>)

    /**
     * ST:public/scripts/world-info.js:4949 matchSecondaryKeys（checkWorldInfo 里的内部函数）。
     * AND_ANY / NOT_ALL 在循环里提前返回；NOT_ANY / AND_ALL 要看完全部次要关键词。
     * 每个次要关键词都会调用一次 substitute（按顺序，提前返回后不再继续）。
     */
    private suspend fun matchSecondaryKeys(entry: WorldInfoEntry, textToScan: String, buffer: WorldInfoBuffer): Boolean {
        val selectiveLogic = entry.selectiveLogic
        var hasAnyMatch = false
        var hasAllMatch = true
        for (keysecondary in entry.keysecondary) {
            val secondarySubstituted = host.substitute(keysecondary)
            val hasSecondaryMatch =
                secondarySubstituted.isNotEmpty() && buffer.matchKeys(textToScan, jsTrim(secondarySubstituted), entry)

            if (hasSecondaryMatch) hasAnyMatch = true
            if (!hasSecondaryMatch) hasAllMatch = false

            if (selectiveLogic == WorldInfoLogic.AND_ANY && hasSecondaryMatch) return true
            if (selectiveLogic == WorldInfoLogic.NOT_ALL && !hasSecondaryMatch) return true
        }
        if (selectiveLogic == WorldInfoLogic.NOT_ANY && !hasAnyMatch) return true
        if (selectiveLogic == WorldInfoLogic.AND_ALL && hasAllMatch) return true
        return false
    }

    /** ST:public/scripts/world-info.js:5002 verifyProbability */
    private fun verifyProbability(
        scan: ScanEntry,
        timed: WorldInfoTimedEffects,
        failed: MutableSet<ScanEntry>,
        dryRun: Boolean,
        dryRunProbability: DryRunProbability,
    ): Boolean {
        val entry = scan.entry
        // If we don't need to roll, it's always true
        if (!entry.useProbability || entry.probability == 100) return true
        if (timed.isStickyActive(scan)) return true // sticky entries do not need to re-roll

        if (dryRun && dryRunProbability == DryRunProbability.NEVER_ACTIVATE) {
            failed.add(scan)
            return false
        }

        val rollValue = host.random.nextDouble() * 100
        if (rollValue <= entry.probability) return true

        failed.add(scan)
        return false
    }

    /**
     * ST:public/scripts/world-info.js:5388 filterByInclusionGroups
     * 就地修改 [newEntries]。
     */
    private suspend fun filterByInclusionGroups(
        newEntries: MutableList<ScanEntry>,
        allActivatedEntries: Map<String, ScanEntry>,
        buffer: WorldInfoBuffer,
        scanState: ScanState,
        timed: WorldInfoTimedEffects,
        settings: WorldInfoSettings,
    ) {
        val grouped = LinkedHashMap<String, MutableList<ScanEntry>>()
        for (item in newEntries) {
            if (item.entry.group.isEmpty()) continue
            for (g in splitGroupNames(item.entry.group)) grouped.getOrPut(g) { ArrayList() }.add(item)
        }
        if (grouped.isEmpty()) return

        // Object.entries(grouped) 的遍历顺序
        val keys = jsKeyOrder(grouped.keys)

        // 注意：上游 `newEntries.splice(newEntries.indexOf(entry), 1)`，entry 已不在列表里时 indexOf 为 -1，
        // 会删掉最后一个元素。一个条目属于多个包含组时就会触发。这里照搬这个行为，保持与真酒馆一致。
        fun removeEntry(entry: ScanEntry) {
            val idx = newEntries.indexOfFirst { it === entry }
            if (idx >= 0) newEntries.removeAt(idx) else if (newEntries.isNotEmpty()) newEntries.removeAt(newEntries.size - 1)
        }

        fun removeAllBut(group: List<ScanEntry>, chosen: ScanEntry?) {
            for (entry in group) {
                if (entry === chosen) continue
                removeEntry(entry)
            }
        }

        // filterGroupsByTimedEffects: ST:public/scripts/world-info.js:5337
        val hasStickyMap = HashMap<String, Boolean>()
        for (key in keys) {
            val group = grouped.getValue(key)
            hasStickyMap[key] = false

            // If the group has any sticky entries, leave only the sticky entries
            val stickyEntries = group.filter { timed.isStickyActive(it) }
            if (stickyEntries.isNotEmpty()) {
                for (entry in group) {
                    if (stickyEntries.any { it === entry }) continue
                    removeEntry(entry)
                }
                hasStickyMap[key] = true
            }

            val cooldownEntries = group.filter { timed.isCooldownActive(it) }
            for (entry in cooldownEntries) removeEntry(entry)

            val delayEntries = group.filter { timed.isDelayActive(it) }
            for (entry in delayEntries) removeEntry(entry)
        }

        // filterGroupsByScoring: ST:public/scripts/world-info.js:5292
        for (key in keys) {
            val group = grouped.getValue(key)
            // Group scoring is disabled both globally and for the group entries
            if (!settings.useGroupScoring && group.none { it.entry.useGroupScoring == true }) continue
            // If the group has any sticky entries, the rest are already removed by the timed effects filter
            if (hasStickyMap[key] == true) continue

            val scores = group.map { buffer.getScore(it.entry, scanState) }.toMutableList()
            val maxScore = scores.maxOrNull() ?: continue

            var i = 0
            while (i < group.size) {
                val isScored = group[i].entry.useGroupScoring ?: settings.useGroupScoring
                if (!isScored) {
                    i++
                    continue
                }
                if (scores[i] < maxScore) {
                    removeEntry(group[i])
                    group.removeAt(i)
                    scores.removeAt(i)
                    continue
                }
                i++
            }
        }

        for (key in keys) {
            val group = grouped.getValue(key)

            // If the group has any sticky entries, the rest are already removed by the timed effects filter
            if (hasStickyMap[key] == true) continue

            if (allActivatedEntries.values.any { it.entry.group == key }) {
                // We need to forcefully deactivate all other entries in the group
                removeAllBut(group, null)
                continue
            }

            if (group.size <= 1) continue

            // Check for group prio
            val prios = group.filter { it.entry.groupOverride }.sortedWith(compareByDescending { it.entry.order })
            if (prios.isNotEmpty()) {
                removeAllBut(group, prios[0])
                continue
            }

            // Do weighted random using entry's weight
            val totalWeight = group.sumOf { it.entry.groupWeight.toDouble() }
            val rollValue = host.random.nextDouble() * totalWeight
            var currentWeight = 0.0
            var winner: ScanEntry? = null
            for (entry in group) {
                currentWeight += entry.entry.groupWeight
                if (rollValue <= currentWeight) {
                    winner = entry
                    break
                }
            }
            if (winner == null) continue

            // Remove every group item from newEntries but the winner
            removeAllBut(group, winner)
        }
    }

    // 上游 `group.split(/,\s*/).filter(x => x)`：逗号后的 JS 空白一并吃掉，不 trim 其它位置。
    private fun splitGroupNames(group: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < group.length) {
            val c = group[i]
            if (c == ',') {
                if (sb.isNotEmpty()) out += sb.toString()
                sb.setLength(0)
                i++
                while (i < group.length && isJsWhitespace(group[i])) i++
            } else {
                sb.append(c)
                i++
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /**
     * `getTokenCountAsync(文本)` 在 openai 来源下的实际路径：`counterWrapperOpenAIAsync` → `countTokensOpenAIAsync({content: 文本}, full=true)`
     * （ST:public/scripts/tokenizers.js:443-457、:564、:846），即 每条消息 +3、整体 +3、不扣 2，总共比纯文本多 6；空串直接为 0。
     */
    private fun stTextTokens(text: String): Int = if (text.isEmpty()) 0 else host.countTokens(text) + ST_TEXT_COUNT_OVERHEAD

    private fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

    companion object {
        /** `getTokenCountAsync` 走 `{content}` 消息计数时多出的 token：消息 +3、整体 +3（ST:src/endpoints/tokenizers.js:998-1017）。 */
        const val ST_TEXT_COUNT_OVERHEAD = 6
    }
}
