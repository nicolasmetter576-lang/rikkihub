package me.rerere.rikkihub.data.ai.qubagu

import me.rerere.ai.ui.stripMessageTitle
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tavern.regex.QuickJsRegexRuntime
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QubaguTest {
    companion object {
        private val runtime = QuickJsRegexRuntime()

        @AfterClass
        @JvmStatic
        fun close() = runtime.close()
    }

    private val engine = Qubagu(runtime)

    /** 什么规则都没有：App 默认就是这样。 */
    private val bare = QubaguConfig()

    /** 原来内置、现在作为规则文件提供的规则。 */
    private val file by lazy { QubaguRulesJson.decode(checkNotNull(javaClass.getResource("/qubagu/rules-v1.json")).readText()) }

    /** 规则文件里 [pack] 包中 id 以 [prefix] 开头的规则（包强制打开）。 */
    private fun fromFile(pack: String, prefix: String = "") = QubaguConfig(
        packs = file.filter { it.id == pack }.map { p -> p.copy(enabled = true, rules = p.rules.filter { it.id.startsWith(prefix) }) },
    )

    /** 规则文件全部规则，按文件里的开关（隐喻直白化三个包默认关）。 */
    private val packsOnly by lazy { QubaguConfig(packs = file) }

    private var ruleIds = 0

    private fun rule(find: String, vararg replacements: String, regex: Boolean = false, dialogue: Boolean = false, keepFirst: Boolean = false, atEnd: Boolean = false) =
        QubaguRule.Replace(id = "r${ruleIds++}", find = find, regex = regex, replacements = replacements.toList(), dialogue = dialogue, keepFirst = keepFirst, atEnd = atEnd)

    /** 在 [this] 的规则包后面加一个用户自己的包，放进 [rules]。 */
    private fun QubaguConfig.with(vararg rules: QubaguRule) = copy(packs = packs + QubaguRulePack(id = "mine", rules = rules.toList()))

    private fun clean(text: String, config: QubaguConfig, recent: String = "") = runBlocking {
        val record = engine.clean(text, config, recent)
        record.render(text)
    }

    private fun record(text: String, config: QubaguConfig, recent: String = "") = runBlocking { engine.clean(text, config, recent) }

    // ---- 绕弯否定 ----

    private val negation by lazy { fromFile("sentence", "negation_") }

    @Test
    fun negationKeepsOnlyTheRealStatement() {
        assertEquals("那是一种执念。", clean("那不是愤怒，而是一种执念。", negation))
        assertEquals("他是在哭。", clean("他并不是在笑，是在哭。", negation))
        assertEquals("这是命令。", clean("这不是请求，也不是商量，而是命令。", negation))
    }

    @Test
    fun negationAcrossASentenceBreak() {
        assertEquals("这是命令。", clean("这不是请求。是命令。", negation))
    }

    @Test
    fun negationOtherShapes() {
        assertEquals("这是试探。", clean("这并非安慰，而是试探。", negation))
        assertEquals("更像是试探。", clean("与其说是安慰，不如说是试探。", negation))
        assertEquals("她的眼神多了几分温柔。", clean("她的眼神少了几分凌厉，却多了几分温柔。", negation))
    }

    @Test
    fun negationLeavesOrdinaryNegativesAlone() {
        for (text in listOf(
            "他问她是不是累了，是该休息了。",
            "他选择沉默而不是争辩，是因为太累了。",
            "这不是他的错，是谁的错？",
            "要不是下雨，是可以出门的。",
            "这不是梦。",
        )) {
            assertEquals(text, clean(text, negation))
        }
    }

    @Test
    fun negationOnlyReplacesTheFirstHalfSoLaterHitsStayIndependent() {
        val config = negation.with(rule("执念", "念想"))
        val record = record("那不是愤怒，而是一种执念。", config)
        assertEquals(listOf("不是愤怒，而是" to "是", "执念" to "念想"), record.edits.map { it.before to it.after })
        assertEquals("那是一种念想。", record.render("那不是愤怒，而是一种执念。"))
    }

    // ---- 结尾套话 ----

    private val finale by lazy { fromFile("sentence", "finale_") }

    @Test
    fun finaleDropsAClosingSentence() {
        assertEquals("她转身离开。", clean("她转身离开。夜还很长。", finale))
        assertEquals("她转身离开。", clean("她转身离开。\n\n而属于他们的故事，才刚刚开始。", finale))
        assertEquals("他把信放在桌上。", clean("他把信放在桌上。现在，选择权在你手里。", finale))
    }

    @Test
    fun finaleCutsAClosingClauseAndKeepsTheSentence() {
        assertEquals("她笑了笑。", clean("她笑了笑，毕竟夜还很长。", finale))
        assertEquals("她笑了笑！", clean("她笑了笑，一切才刚刚开始！", finale))
        assertEquals("他看着你。", clean("他看着你，那么，你会怎么做呢？", finale))
    }

    @Test
    fun finaleLooksAboveThePerTurnTitleAndKeepsIt() {
        val full = "她转身离开。夜还很长。\n<rk-title>离开</rk-title>"
        val record = record(full.stripMessageTitle(), finale)
        assertEquals("她转身离开。\n<rk-title>离开</rk-title>", record.render(full))
    }

    @Test
    fun finaleOnlyTouchesTheVeryEnd() {
        assertEquals("夜还很长。她转身离开。", clean("夜还很长。她转身离开。", finale))
        assertEquals("夜还很长。", clean("夜还很长。", finale))
    }

    @Test
    fun finaleLooksPastATrailingStatusBlock() {
        val text = "她转身离开。夜还很长。\n<details><summary>状态</summary>好感 +1</details>"
        assertEquals("她转身离开。\n<details><summary>状态</summary>好感 +1</details>", clean(text, finale))
    }

    @Test
    fun finaleInsideDialogueIsKept() {
        assertEquals("她说：“夜还很长。”", clean("她说：“夜还很长。”", finale))
    }

    // ---- 水词配额 ----

    @Test
    fun quotaKeepsTheFirstFewAndDropsTheRest() {
        val config = bare.with(QubaguRule.Quota(id = "q", words = listOf("微微", "缓缓"), max = 2))
        assertEquals(
            "她微微一笑，微微点头，又侧身，叹气，缓缓坐下。",
            clean("她微微一笑，微微点头，又微微侧身，微微地叹气，缓缓坐下。", config),
        )
    }

    @Test
    fun quotaCountsNarrationOnly() {
        val config = bare.with(QubaguRule.Quota(id = "q", words = listOf("轻轻"), max = 1))
        assertEquals("她轻轻说：“轻轻放，轻轻拿。”然后推门。", clean("她轻轻说：“轻轻放，轻轻拿。”然后轻轻推门。", config))
    }

    @Test
    fun quotaOfZeroRemovesEveryOccurrence() {
        val config = bare.with(QubaguRule.Quota(id = "q", words = listOf("缓缓"), max = 0))
        assertEquals("他抬头。", clean("他缓缓抬头。", config))
    }

    // ---- 碎句合并 ----

    private val merge = bare.copy(merge = true)

    @Test
    fun mergeJoinsRunsOfVeryShortLines() {
        val text = "七步。\n五步。\n三步。\n他停下了脚步，没有再往前走。"
        val record = record(text, merge)
        assertEquals("七步，五步，三步。\n他停下了脚步，没有再往前走。", record.render(text))
        assertEquals(listOf(QubaguRules.MERGE), record.edits.map { it.rule })
        assertEquals("七步。\n五步。\n三步。", record.edits.single().before)
    }

    @Test
    fun mergeBridgesOneBlankLineButNotTwo() {
        assertEquals("沉默，寂静。", clean("沉默。\n\n寂静。", merge))
        assertEquals("沉默。\n\n\n寂静。", clean("沉默。\n\n\n寂静。", merge))
    }

    @Test
    fun mergeNeedsShortPlainLines() {
        for (text in listOf(
            "七步。\n他停下了脚步，没有再往前走了。",
            "- 七步。\n- 五步。",
            "“七步。”\n“五步。”",
            "七步！\n五步！",
            "1. 七步。\n2. 五步。",
        )) {
            assertEquals(text, clean(text, merge))
        }
    }

    @Test
    fun mergeFoldsOtherEditsInTheRunIntoOneRecord() {
        val config = merge.with(rule("寂静", "安静"))
        val text = "沉默。\n寂静。\n之后他才开口说话，声音很低。"
        val record = record(text, config)
        assertEquals(1, record.edits.size)
        assertEquals("沉默，安静。", record.edits.single().after)
        assertEquals(text, record.withAllApplied(false).render(text))
    }

    @Test
    fun mergeRespectsTheLengthLimit() {
        assertEquals("一二三四五。\n六七八九十。", clean("一二三四五。\n六七八九十。", merge.copy(mergeMaxChars = 4)))
        assertEquals("一二三四五，六七八九十。", clean("一二三四五。\n六七八九十。", merge.copy(mergeMaxChars = 5)))
    }

    // ---- 自定义规则与替换词 ----

    @Test
    fun literalRulesAreNotRegexes() {
        val config = bare.with(rule("(笑)", "[笑]"))
        assertEquals("他[笑]了，笑了。", clean("他(笑)了，笑了。", config))
    }

    @Test
    fun regexRulesSubstituteGroups() {
        val config = bare.with(rule("嘴角(?:勾起|扬起)一抹(.{1,4}?)的弧度", "$1地笑了笑", regex = true))
        assertEquals("他玩味地笑了笑。", clean("他嘴角勾起一抹玩味的弧度。", config))
    }

    @Test
    fun regexLiteralSyntaxCarriesFlags() {
        val config = bare.with(rule("/smirk(ed)?/i", "smile", regex = true))
        assertEquals("He smile. smile.", clean("He Smirked. SMIRK.", config))
    }

    @Test
    fun emptyReplacementsDelete() {
        assertEquals("他愣住了。", clean("他不由得愣住了。", bare.with(rule("不由得"))))
    }

    @Test
    fun brokenOrDisabledRulesAreSkipped() {
        val config = bare.with(
            rule("(未闭合", "x", regex = true),
            rule("喉结", "x").copy(enabled = false),
            rule("滚动", "动了动"),
        )
        assertEquals("喉结动了动。", clean("喉结滚动。", config))
        assertNotNull(runBlocking { engine.check(rule("(未闭合", regex = true)) })
        assertNull(runBlocking { engine.check(rule("(未闭合")) })
        assertNull(runBlocking { engine.check(rule("闭合(.)", regex = true)) })
    }

    @Test
    fun keepFirstLeavesTheFirstOccurrence() {
        val config = bare.with(rule("猛地", "忽然", keepFirst = true))
        assertEquals("他猛地回头，忽然站起，忽然冲出去。", clean("他猛地回头，猛地站起，猛地冲出去。", config))
    }

    @Test
    fun replacementsAvoidWhatRecentRepliesAlreadyUse() {
        val config = bare.with(rule("心头一震", "愣了一下", "怔了怔", "顿了顿"))
        repeat(20) { i ->
            val picked = record("第${i}段。他心头一震。", config, recent = "她愣了一下，又顿了顿。")
            assertEquals("text $i", "怔了怔", picked.edits.single().after)
        }
    }

    @Test
    fun replacementsAvoidWhatThisReplyAlreadyUses() {
        val config = bare.with(rule("心头一震", "愣了一下", "怔了怔"))
        repeat(20) { i ->
            val picked = record("第${i}段。他心头一震。可她怔了怔。", config)
            assertEquals("text $i", "愣了一下", picked.edits.single().after)
        }
    }

    @Test
    fun replacementsDoNotRepeatWithinAReplyUntilThePoolRunsOut() {
        val config = bare.with(rule("心头一震", "甲", "乙", "丙"))
        repeat(20) { i ->
            val picked = record("第${i}段。心头一震，心头一震，心头一震，心头一震。", config)
            assertEquals("text $i", setOf("甲", "乙", "丙"), picked.edits.take(3).map { it.after }.toSet())
            assertEquals(4, picked.edits.size)
        }
    }

    @Test
    fun theSameTextAlwaysGivesTheSameRecord() {
        val config = bare.with(rule("心头一震", "甲", "乙", "丙", "丁"), rule("嘴角", "唇边", "唇角", "笑意"))
        val text = "他心头一震，她心头一震，嘴角一动，嘴角又一动。"
        val first = record(text, config, recent = "乙")
        repeat(20) { assertEquals("run $it", first, record(text, config, recent = "乙")) }
        assertEquals(first, runBlocking { Qubagu(runtime).clean(text, config, "乙") })
    }

    @Test
    fun differentTextsStillGetDifferentPicks() {
        val config = bare.with(rule("心头一震", "甲", "乙", "丙", "丁"))
        val picks = (0 until 40).map { record("第${it}段。他心头一震。", config).edits.single().after }.toSet()
        assertTrue("picked only $picks", picks.size >= 3)
    }

    @Test
    fun earlierRulesWinOverlaps() {
        val config = bare.with(rule("深邃的眼眸", "眼睛"), rule("眼眸", "双眼"), rule("深邃", "深"))
        assertEquals("他的眼睛里映着双眼。", clean("他的深邃的眼眸里映着眼眸。", config))
    }

    // ---- 保护区 ----

    private val guarded = bare.with(rule("喉结", "X"))

    @Test
    fun dialogueIsKeptUnlessTheRuleAsksForIt() {
        val text = "喉结。“喉结”「喉结」\"喉结\""
        assertEquals("X。“喉结”「喉结」\"喉结\"", clean(text, guarded))
        assertEquals("X。“X”「X」\"X\"", clean(text, bare.with(rule("喉结", "X", dialogue = true))))
        assertEquals("X。“X”「X」\"X\"", clean(text, guarded.copy(protectDialogue = false)))
    }

    @Test
    fun protectedContentIsNeverTouched() {
        for (kept in listOf(
            "『喉结』",
            "```\n喉结\n```",
            "`喉结`",
            "<details><summary>喉结</summary>喉结</details>",
            "<span title=\"喉结\">",
            "[链接](https://example.com/喉结)",
            "<pre>喉结</pre>",
        )) {
            assertEquals("X$kept X", clean("喉结$kept 喉结", bare.with(rule("喉结", "X", dialogue = true))))
        }
    }

    @Test
    fun protectionSwitchesOpenTheirZones() {
        assertEquals("『X』", clean("『喉结』", guarded.copy(protectCornerQuotes = false)))
        assertEquals("<details>X</details>", clean("<details>喉结</details>", guarded.copy(protectDetails = false)))
    }

    @Test
    fun aMatchCrossingIntoDialogueIsDropped() {
        val config = bare.with(rule("笑.{0,3}好", "X", regex = true))
        assertEquals("他笑：“好。”", clean("他笑：“好。”", config))
    }

    // ---- 规则包（原内置规则，现在由规则文件提供） ----

    @Test
    fun punctuationRulesTidyRunsEverywhere() {
        val config = fromFile("punctuation")
        assertEquals("他……“走——”她问？？？", clean("他…………“走—————”她问？？？？？？", config))
        val noEllipsis = config.copy(packs = config.packs.map { p -> p.copy(rules = p.rules.map { if (it.id == "punct_ellipsis_run") it.withEnabled(false) else it }) })
        assertEquals("他…………", clean("他…………", noEllipsis))
        assertEquals("他…………", clean("他…………", bare))
    }

    @Test
    fun wordPacksReplaceStockPhrasesFromTheirPools() {
        val rule = packsOnly.rule("expression/phys_pupil_quake") as QubaguRule.Replace
        val record = record("他瞳孔骤缩，没有说话。", packsOnly)
        assertEquals(listOf("expression/phys_pupil_quake" to "瞳孔骤缩"), record.edits.map { it.rule to it.before })
        assertTrue(record.edits.single().after in rule.replacements)
        val packOff = packsOnly.copy(packs = packsOnly.packs.map { if (it.id == "expression") it.copy(enabled = false) else it })
        assertEquals("他瞳孔骤缩，没有说话。", clean("他瞳孔骤缩，没有说话。", packOff))
        val ruleOff = packsOnly.copy(packs = packsOnly.packs.map { p -> p.copy(rules = p.rules.map { if (it.id == "phys_pupil_quake") it.withEnabled(false) else it }) })
        assertEquals("他瞳孔骤缩，没有说话。", clean("他瞳孔骤缩，没有说话。", ruleOff))
        assertEquals("她说：“他瞳孔骤缩。”", clean("她说：“他瞳孔骤缩。”", packsOnly))
    }

    @Test
    fun englishPackRulesIgnoreCase() {
        val cleaned = clean("Their silence was A Testament To years of trust.", packsOnly)
        assertFalse(cleaned, "testament" in cleaned.lowercase())
    }

    @Test
    fun explicitPacksAreOffUntilOneIsSwitchedOn() {
        val text = "紫红的伞冠。他拧开水龙头。"
        assertEquals(text, clean(text, packsOnly))
        for (tier in listOf("explicit_plain", "explicit_erotic", "explicit_vulgar")) {
            val config = packsOnly.copy(packs = packsOnly.packs.map { if (it.id == tier) it.copy(enabled = true) else it })
            val rule = config.rule("$tier/nsfw_glans_metaphor") as QubaguRule.Replace
            val cleaned = clean(text, config)
            assertTrue("$tier: $cleaned", cleaned.endsWith("。他拧开水龙头。") && cleaned.startsWith("紫红的"))
            val word = cleaned.removePrefix("紫红的").removeSuffix("。他拧开水龙头。")
            assertTrue("$tier: $word", "\$1$word" in rule.replacements)
        }
    }

    @Test
    fun englishSentenceHabits() {
        val config = fromFile("sentence")
        assertEquals("It was hope.", clean("It wasn't fear, it was hope.", config))
        assertEquals("He paused. It is a promise.", clean("He paused. It's not a threat — it's a promise.", config))
        assertEquals("He knew it wasn't fear, it was hope that drove her.", clean("He knew it wasn't fear, it was hope that drove her.", config))
        assertEquals("She left the room.", clean("She left the room. The night was still young.", config))
        assertEquals("She smiled.", clean("She smiled, and the night was still young.", config))
        assertEquals("The night was still young. She left the room.", clean("The night was still young. She left the room.", config))
    }

    @Test
    fun packOrderDecidesOverlaps() {
        val first = QubaguRulePack(id = "a", rules = listOf(rule("深邃的眼眸", "眼睛")))
        val second = QubaguRulePack(id = "b", rules = listOf(rule("眼眸", "双眼")))
        assertEquals("他的眼睛。", clean("他的深邃的眼眸。", QubaguConfig(packs = listOf(first, second))))
        assertEquals("他的深邃的双眼。", clean("他的深邃的眼眸。", QubaguConfig(packs = listOf(second, first))))
        assertEquals(listOf("b/" + second.rules.single().id), record("他的深邃的眼眸。", QubaguConfig(packs = listOf(second, first))).edits.map { it.rule })
    }

    @Test
    fun atEndRulesOnlyMatchWhereTheTextEnds() {
        val config = bare.with(rule("再见。", atEnd = true))
        assertEquals("再见。他走了。", clean("再见。他走了。再见。", config))
        assertEquals("再见。他走了。", clean("再见。他走了。", config))
        assertEquals("他走了。\n\n", clean("他走了。再见。\n\n", config))
        // 末尾跟着的台词与折叠块不算正文，它们前面才是结尾
        assertEquals("他走了。“再见。”", clean("他走了。再见。“再见。”", config))
        assertEquals("他走了。\n<details>再见。</details>", clean("他走了。再见。\n<details>再见。</details>", config))
    }

    @Test
    fun reviewModeRecordsWithoutApplying() {
        val text = "那不是愤怒，而是执念。"
        val record = record(text, negation.copy(mode = QubaguMode.REVIEW))
        assertEquals(1, record.edits.size)
        assertEquals(0, record.appliedCount)
        assertEquals(text, record.render(text))
        assertEquals("那是执念。", record.withAllApplied(true).render(text))
    }

    @Test
    fun cleanTextYieldsNoEdits() {
        val everything = packsOnly.copy(merge = true)
        assertTrue(record("他推开门，看见桌上放着一封信。", everything).edits.isEmpty())
        assertTrue(record("   ", everything).edits.isEmpty())
        assertTrue(record("他瞳孔骤缩。夜还很长。", bare).edits.isEmpty())
    }

    // ---- 记录 ----

    private val sample = "甲乙丙丁戊己庚辛"
    private val edits = QubaguRecord(edits = listOf(
        QubaguEdit(1, "乙", "BB", "mine/a"),
        QubaguEdit(3, "丁戊", "", "q/q"),
        QubaguEdit(6, "庚", "G", "mine/b", applied = false),
    ))

    @Test
    fun recordRendersAppliedEditsAndRecoversTheOriginal() {
        assertEquals("甲BB丙己庚辛", edits.render(sample))
        assertEquals(sample, edits.original("甲BB丙己庚辛"))
        assertEquals(listOf(1, 4, 5), edits.currentStarts())
    }

    @Test
    fun recordTogglesOneEditAtATime() {
        val toggled = edits.withApplied(0, false).withApplied(2, true)
        assertEquals("甲乙丙己G辛", toggled.render(sample))
        assertEquals(sample, toggled.original("甲乙丙己G辛"))
        assertEquals(sample, edits.withAllApplied(false).render(sample))
    }

    @Test
    fun recordGoesStaleWhenTheTextWasEditedByHand() {
        assertNull(edits.original("甲XX丙己庚辛"))
        assertNull(edits.original("甲"))
        assertNull(edits.original(""))
    }

    @Test
    fun recordTravelsInThePartMetadata() {
        val part = UIMessagePart.Text(sample).withQubaguRecord(sample, edits)
        assertEquals("甲BB丙己庚辛", part.text)
        assertEquals(edits.edits, QubaguRecord.of(part)!!.edits)
        assertNull(QubaguRecord.of(part.copy(text = "手动改过")))
        // 只有删除的记录：位置上什么都对得上，靠写入时的正文指纹认出正文变过
        val deletion = UIMessagePart.Text("甲乙丙").withQubaguRecord("甲乙丙", QubaguRecord(edits = listOf(QubaguEdit(1, "乙", "", "q/q"))))
        assertEquals("甲丙", deletion.text)
        assertEquals(1, QubaguRecord.of(deletion)!!.edits.size)
        assertNull(QubaguRecord.of(deletion.copy(text = "甲丙丁")))
        assertNull(QubaguRecord.of(deletion.copy(text = "子丑")))
        assertNull(QubaguRecord.of(UIMessagePart.Text(sample)))

        val restored = part.withQubagu { _, record -> record.withAllApplied(false) }
        assertEquals(sample, restored.text)
        assertEquals(0, QubaguRecord.of(restored)!!.appliedCount)

        val cleared = part.withQubagu { _, _ -> QubaguRecord() }
        assertEquals(sample, cleared.text)
        assertNull(cleared.metadata)
    }

    @Test
    fun recordKeepsOtherMetadataOnThePart() {
        val other = kotlinx.serialization.json.buildJsonObject { put("tavernActivations", kotlinx.serialization.json.JsonPrimitive(1)) }
        val part = UIMessagePart.Text(sample, other).withQubaguRecord(sample, edits)
        assertEquals(setOf("tavernActivations", QubaguRecord.KEY), part.metadata!!.keys)
        val cleared = part.withQubagu { _, _ -> QubaguRecord() }
        assertEquals(setOf("tavernActivations"), cleared.metadata!!.keys)
        assertFalse(QubaguRecord.KEY in cleared.metadata!!)
    }
}
