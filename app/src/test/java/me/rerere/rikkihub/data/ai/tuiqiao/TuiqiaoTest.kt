package me.rerere.rikkihub.data.ai.tuiqiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuiqiaoTest {
    private fun sub(source: String, range: IntRange) = source.substring(range.first, range.last + 1)

    @Test
    fun classifiesWordsSentencesAndParagraphs() {
        assertEquals(TuiqiaoMode.WORD, Tuiqiao.classify("推"))
        assertEquals(TuiqiaoMode.WORD, Tuiqiao.classify("缓缓地"))
        assertEquals(TuiqiaoMode.WORD, Tuiqiao.classify("月下老人"))
        assertEquals(TuiqiaoMode.WORD, Tuiqiao.classify("quietly"))
        assertEquals(TuiqiaoMode.WORD, Tuiqiao.classify("very quietly"))
        assertEquals(TuiqiaoMode.SENTENCE, Tuiqiao.classify("僧推月下门"))
        assertEquals(TuiqiaoMode.SENTENCE, Tuiqiao.classify("推，"))
        assertEquals(TuiqiaoMode.SENTENCE, Tuiqiao.classify("鸟宿池边树，僧敲月下门。"))
        assertEquals(TuiqiaoMode.SENTENCE, Tuiqiao.classify("“你来了？”"))
        assertEquals(TuiqiaoMode.SENTENCE, Tuiqiao.classify("He opened the door slowly."))
        assertEquals(TuiqiaoMode.PARAGRAPH, Tuiqiao.classify("他推开门。屋里很暗。"))
        assertEquals(TuiqiaoMode.PARAGRAPH, Tuiqiao.classify("他推开门。屋里很暗"))
        assertEquals(TuiqiaoMode.PARAGRAPH, Tuiqiao.classify("第一行\n第二行"))
        assertEquals(TuiqiaoMode.PARAGRAPH, Tuiqiao.classify("He left. She stayed."))
    }

    @Test
    fun sentenceCountTreatsPunctuationRunsAsOneEnd() {
        assertEquals(1, Tuiqiao.sentenceEnds("什么？！"))
        assertEquals(1, Tuiqiao.sentenceEnds("他说……”"))
        assertEquals(1, Tuiqiao.sentenceEnds("pi is 3.14 here"))
        assertEquals(2, Tuiqiao.sentenceEnds("“走吧。”他说。"))
    }

    @Test
    fun normalizeRangeTrimsWhitespaceAndRejectsBlank() {
        val source = "  他推开门。 \n"
        assertEquals("他推开门。", sub(source, Tuiqiao.normalizeRange(source, 0, source.length)!!))
        assertNull(Tuiqiao.normalizeRange(source, 0, 2))
        assertNull(Tuiqiao.normalizeRange(source, 3, 3))
        assertNull(Tuiqiao.normalizeRange(source, -1, 3))
        assertNull(Tuiqiao.normalizeRange(source, 0, source.length + 1))
    }

    @Test
    fun expandsToSentence() {
        val source = "夜深了。他轻轻推开门，屋里很暗。“谁？”她问。"
        val at = source.indexOf("推开")
        assertEquals("他轻轻推开门，屋里很暗。", sub(source, Tuiqiao.expandToSentence(source, at, at + 2)))
        val q = source.indexOf("谁")
        assertEquals("“谁？”", sub(source, Tuiqiao.expandToSentence(source, q, q + 1)))
        // Already a whole sentence: unchanged.
        val s = source.indexOf("他")
        val e = source.indexOf("屋里很暗。") + "屋里很暗。".length
        assertEquals("他轻轻推开门，屋里很暗。", sub(source, Tuiqiao.expandToSentence(source, s, e)))
    }

    @Test
    fun sentenceExpansionStopsAtLineStartAndSkipsBlockMarkers() {
        val source = "# 标题\n\n> 他推开门，没有说话\n- 列表里的一句话"
        val at = source.indexOf("推开")
        assertEquals("他推开门，没有说话", sub(source, Tuiqiao.expandToSentence(source, at, at + 2)))
        val l = source.indexOf("一句")
        assertEquals("列表里的一句话", sub(source, Tuiqiao.expandToSentence(source, l, l + 2)))
        val h = source.indexOf("标")
        assertEquals("标题", sub(source, Tuiqiao.expandToSentence(source, h, h + 1)))
    }

    @Test
    fun expandsToParagraphBetweenBlankLinesAndHeadings() {
        val source = "# 第一章\n第一段第一行。\n第一段第二行。\n\n第二段。\n| a | b |\n"
        val at = source.indexOf("第二行")
        assertEquals("第一段第一行。\n第一段第二行。", sub(source, Tuiqiao.expandToParagraph(source, at, at + 3)))
        val p2 = source.indexOf("第二段")
        assertEquals("第二段。", sub(source, Tuiqiao.expandToParagraph(source, p2, p2 + 2)))
        val first = source.indexOf("第一段第一行")
        assertEquals("第一段第一行。\n第一段第二行。", sub(source, Tuiqiao.expandToParagraph(source, first, first + 2)))
    }

    @Test
    fun paragraphExpansionAtDocumentEdges() {
        val source = "只有一段，没有换行"
        assertEquals(source, sub(source, Tuiqiao.expandToParagraph(source, 2, 4)))
        val quote = "> 引用第一行\n> 引用第二行"
        val at = quote.indexOf("第二")
        assertEquals("引用第一行\n> 引用第二行", sub(quote, Tuiqiao.expandToParagraph(quote, at, at + 2)))
    }

    @Test
    fun contextKeepsWholeBeforeAndAFewSentencesAfter() {
        val before = "很长的前文。".repeat(50)
        val source = before + "选区" + "一。二。三。四。五。"
        val range = source.indexOf("选区").let { it until it + 2 }
        val context = Tuiqiao.context(source, range)
        assertEquals(before, context.before)
        assertEquals("选区", context.selection)
        assertEquals("一。二。三。", context.after)
    }

    @Test
    fun afterContextStopsAtHeadingAndLengthCap() {
        assertEquals("尾巴", Tuiqiao.afterContext("选区尾巴\n## 下一章\n正文。", 2))
        val long = "选" + "长".repeat(1000)
        assertEquals(Tuiqiao.AFTER_MAX_CHARS, Tuiqiao.afterContext(long, 1).length)
    }

    @Test
    fun streamParserEmitsObjectsAsTheyComplete() {
        val parser = Tuiqiao.StreamParser()
        assertTrue(parser.feed("""{"text":"敲","to""").isEmpty())
        val first = parser.feed("""{"text":"敲","tone":"清脆","why":"有声"}""" + "\n" + """{"text":"叩""")
        assertEquals(listOf(TuiqiaoCandidate("敲", "清脆", "有声")), first)
        val second = parser.finish("""{"text":"敲","tone":"清脆","why":"有声"}""" + "\n" + """{"text":"叩","tone":"郑重"}""")
        assertEquals(listOf(TuiqiaoCandidate("叩", "郑重")), second)
    }

    @Test
    fun streamParserHandlesFencesArraysBracesInStringsAndBadLines() {
        val output = """
            ```json
            [{"text":"他说{不}","tone":"直白"},
             not json at all
             {"text": "带\"引号\"的", "tone": "冷"}, {"broken": }
             {"tone":"没有 text"}]
            ```
        """.trimIndent()
        val parsed = Tuiqiao.StreamParser().finish(output)
        assertEquals(listOf("他说{不}", "带\"引号\"的"), parsed.map { it.text })
    }

    @Test
    fun streamParserFallsBackToLinesWhenModelIgnoresJson() {
        val parsed = Tuiqiao.StreamParser().finish("1. 敲\n2、叩\n- 扣\n```\n")
        assertEquals(listOf("敲", "叩", "扣"), parsed.map { it.text })
    }

    @Test
    fun matchingBraceIgnoresEscapedQuotes() {
        val text = """{"a":"x\"}y"}tail"""
        assertEquals(text.indexOf("}tail"), Tuiqiao.matchingBrace(text, 0))
        assertEquals(-1, Tuiqiao.matchingBrace("""{"a":"open""", 0))
    }

    @Test
    fun markupMustMatchOriginal() {
        assertTrue(Tuiqiao.keepsMarkup("他**猛地**推开门", "他**一把**推开了门"))
        assertFalse(Tuiqiao.keepsMarkup("他**猛地**推开门", "他猛地推开门"))
        assertFalse(Tuiqiao.keepsMarkup("推开门", "**推**开门"))
        assertTrue(Tuiqiao.keepsMarkup("见[月光](https://a.example)", "望见[月色](https://a.example)"))
        assertFalse(Tuiqiao.keepsMarkup("见[月光](https://a.example)", "望见[月色](https://b.example)"))
        assertFalse(Tuiqiao.keepsMarkup("*斜*", "**斜**"))
        assertTrue(Tuiqiao.keepsMarkup("`code` 和 ~~删~~", "~~删~~ 与 `code`"))
    }

    @Test
    fun rejectsSameDuplicateAndBrokenMarkup() {
        val seen = setOf("叩")
        assertEquals(Tuiqiao.Rejection.SAME_AS_ORIGINAL, Tuiqiao.reject("推", TuiqiaoCandidate(" 推 ", ""), seen))
        assertEquals(Tuiqiao.Rejection.DUPLICATE, Tuiqiao.reject("推", TuiqiaoCandidate("叩", ""), seen))
        assertEquals(Tuiqiao.Rejection.MARKUP, Tuiqiao.reject("推", TuiqiaoCandidate("**敲**", ""), seen))
        assertNull(Tuiqiao.reject("推", TuiqiaoCandidate("敲", ""), seen))
    }

    @Test
    fun promptCarriesSettingsContextAndFixedFormat() {
        val config = TuiqiaoConfig(candidateCount = 4, explanation = TuiqiaoExplanation.ONE_LINE)
        val prompt = TuiqiaoPrompts.build(config, TuiqiaoMode.WORD, "第一章.md", TuiqiaoContext("鸟宿池边树，僧", "推", "月下门。"))
        assertTrue(prompt.contains("给出 4 个"))
        assertTrue(prompt.contains("why 用一句话"))
        assertTrue(prompt.contains("<document name=\"第一章.md\">\n鸟宿池边树，僧<selection>推</selection>月下门。\n</document>"))
        assertTrue(prompt.contains("只输出 4 行"))
        assertTrue(prompt.contains("text、tone、why"))
        assertFalse(prompt.contains("<setting>"))
        // Document first, task last (prefix caching).
        assertTrue(prompt.indexOf("<document") < prompt.indexOf("任务："))
    }

    @Test
    fun promptWithoutExplanationOmitsWhyAndKeepsCustomTemplate() {
        val config = TuiqiaoConfig(candidateCount = 9, explanation = TuiqiaoExplanation.OFF, sentencePrompt = "自定义：{count} 个，{explanation} {selection}")
        val prompt = TuiqiaoPrompts.build(config, TuiqiaoMode.SENTENCE, "a.md", TuiqiaoContext("", "{count}", ""), setting = "主角冷淡")
        assertTrue(prompt.contains("自定义：5 个，不需要解释"))
        assertTrue(prompt.contains("{selection}")) // unknown placeholders stay literal
        assertTrue(prompt.contains("<selection>{count}</selection>")) // selection text is never expanded
        assertTrue(prompt.contains("字段为 text、tone；"))
        assertTrue(prompt.contains("<setting>\n主角冷淡\n</setting>"))
    }

    @Test
    fun configClampsCountAndFallsBackToDefaultPrompts() {
        assertEquals(3, TuiqiaoConfig(candidateCount = 1).effectiveCandidateCount)
        assertEquals(5, TuiqiaoConfig(candidateCount = 8).effectiveCandidateCount)
        assertEquals(TuiqiaoPrompts.DEFAULT_PARAGRAPH, TuiqiaoConfig(paragraphPrompt = "  ").promptFor(TuiqiaoMode.PARAGRAPH))
        assertEquals("x", TuiqiaoConfig().withPrompt(TuiqiaoMode.WORD, "x").promptFor(TuiqiaoMode.WORD))
    }

    @Test
    fun collectorFiltersStopsAtCountAndBlanksWhyWhenOff() {
        val collector = TuiqiaoCollector("**推**", 3, TuiqiaoExplanation.OFF)
        val first = collector.offer(listOf(
            TuiqiaoCandidate("**推**", "同"), TuiqiaoCandidate("**敲**", "脆", "因为"), TuiqiaoCandidate("敲", "丢标记"),
            TuiqiaoCandidate(" **敲** ", "重复"),
        ))
        assertEquals(listOf(TuiqiaoCandidate("**敲**", "脆", "")), first)
        val rest = collector.offer(listOf(TuiqiaoCandidate("**叩**", "a"), TuiqiaoCandidate("**扣**", "b"), TuiqiaoCandidate("**拍**", "c")))
        assertEquals(listOf("**叩**", "**扣**"), rest.map { it.text })
        assertNull(collector.failure())
    }

    @Test
    fun collectorExplainsWhyNothingWasShown() {
        val broken = TuiqiaoCollector("**推**", 3, TuiqiaoExplanation.ONE_LINE)
        assertTrue(broken.offer(listOf(TuiqiaoCandidate("敲", ""))).isEmpty())
        assertEquals(TuiqiaoException.Reason.MARKUP_BROKEN, broken.failure())
        val empty = TuiqiaoCollector("推", 3, TuiqiaoExplanation.ONE_LINE)
        assertTrue(empty.offer(listOf(TuiqiaoCandidate("推", ""))).isEmpty())
        assertEquals(TuiqiaoException.Reason.NO_CANDIDATES, empty.failure())
        val keepsWhy = TuiqiaoCollector("推", 3, TuiqiaoExplanation.DETAILED)
        assertEquals("因为", keepsWhy.offer(listOf(TuiqiaoCandidate("敲", "", "因为"))).single().why)
    }

    @Test
    fun preparerNormalizesRangeAndRejectsBadSelections() {
        val source = "僧 推 月下门"
        val prepared = TuiqiaoPreparer.prepare(TuiqiaoConfig(), TuiqiaoRequest("a.md", source, 1, 4))
        assertEquals(2, prepared.start)
        assertEquals(3, prepared.end)
        assertEquals("推", prepared.original)
        assertEquals(TuiqiaoMode.WORD, prepared.mode)
        assertEquals(TuiqiaoMode.PARAGRAPH, TuiqiaoPreparer.prepare(TuiqiaoConfig(), TuiqiaoRequest("a.md", source, 1, 4, TuiqiaoMode.PARAGRAPH)).mode)
        val empty = runCatching { TuiqiaoPreparer.prepare(TuiqiaoConfig(), TuiqiaoRequest("a.md", source, 1, 2)) }.exceptionOrNull()
        assertEquals(TuiqiaoException.Reason.EMPTY_SELECTION, (empty as TuiqiaoException).reason)
        val long = "长".repeat(Tuiqiao.MAX_SELECTION_CHARS + 1)
        val tooLong = runCatching { TuiqiaoPreparer.prepare(TuiqiaoConfig(), TuiqiaoRequest("a.md", long, 0, long.length)) }.exceptionOrNull()
        assertEquals(TuiqiaoException.Reason.SELECTION_TOO_LONG, (tooLong as TuiqiaoException).reason)
    }

    // ---------- 文件格式 ----------

    @Test
    fun formatAndEnabledExtensions() {
        assertEquals(TuiqiaoFormat.MARKDOWN, TuiqiaoFormat.of("dir/第一章.MD"))
        assertEquals(TuiqiaoFormat.PLAIN, TuiqiaoFormat.of("小说.txt"))
        assertEquals(TuiqiaoFormat.PLAIN, TuiqiaoFormat.of("ep1.srt"))
        val config = TuiqiaoConfig()
        assertTrue(config.enabledFor("a.txt"))
        assertTrue(config.enabledFor("a.fountain"))
        assertFalse(config.enabledFor("a.json"))
        assertFalse(config.enabledFor("README"))
        val custom = TuiqiaoConfig(extensions = listOf("json"))
        assertTrue(custom.enabledFor("a.json"))
        assertFalse(custom.enabledFor("a.md"))
        // Every default must be something the editor opens as text.
        assertTrue(TuiqiaoConfig.DEFAULT_EXTENSIONS.all { it in TuiqiaoConfig.AVAILABLE_EXTENSIONS })
        assertEquals("txt", TuiqiaoConfig.normalizeExtension(" .TXT "))
        assertNull(TuiqiaoConfig.normalizeExtension("exe"))
    }

    @Test
    fun plainTextParagraphIsOneLine() {
        val source = "第一章\n　　他推开门。屋里很暗。\n　　她没有回头。\n\n- 不是列表"
        val at = source.indexOf("屋里")
        assertEquals("他推开门。屋里很暗。", sub(source, Tuiqiao.expandToParagraph(source, at, at + 2, TuiqiaoFormat.PLAIN)))
        // Markdown would have merged the adjacent lines.
        assertTrue(sub(source, Tuiqiao.expandToParagraph(source, at, at + 2)).contains("她没有回头"))
        val across = source.indexOf("很暗")
        val end = source.indexOf("回头") + 1
        assertEquals("他推开门。屋里很暗。\n　　她没有回头。", sub(source, Tuiqiao.expandToParagraph(source, across, end, TuiqiaoFormat.PLAIN)))
        val dash = source.indexOf("列表")
        assertEquals("- 不是列表", sub(source, Tuiqiao.expandToParagraph(source, dash, dash + 2, TuiqiaoFormat.PLAIN)))
        assertEquals("- 不是列表", sub(source, Tuiqiao.expandToSentence(source, dash, dash + 2, TuiqiaoFormat.PLAIN)))
        assertEquals("不是列表", sub(source, Tuiqiao.expandToSentence(source, dash, dash + 2)))
    }

    @Test
    fun plainPromptDropsMarkdownRuleAndCollectorSkipsMarkupCheck() {
        val prompt = TuiqiaoPrompts.build(TuiqiaoConfig(), TuiqiaoMode.WORD, "a.txt", TuiqiaoContext("", "推", ""))
        assertFalse(prompt.contains("Markdown 标记（如"))
        assertTrue(prompt.contains("时间码"))
        val collector = TuiqiaoCollector("推", 3, TuiqiaoExplanation.ONE_LINE, Tuiqiao.MarkupPolicy.NONE)
        assertEquals(listOf("**敲**"), collector.offer(listOf(TuiqiaoCandidate("**敲**", ""))).map { it.text })
    }

    // ---------- 推敲角色 ----------

    private val humor = TuiqiaoRole(name = "幽默旁白", prompt = "为选中部分加上幽默讽刺的旁白", modes = setOf(TuiqiaoMode.SENTENCE, TuiqiaoMode.PARAGRAPH))

    @Test
    fun rolesTakeSlotsInOrderUpToTheCandidateCount() {
        val word = TuiqiaoRole(name = "古风", prompt = "改成古风", modes = setOf(TuiqiaoMode.WORD))
        val off = humor.copy(id = kotlin.uuid.Uuid.random(), name = "停用", enabled = false)
        val config = TuiqiaoConfig(candidateCount = 3, roles = listOf(off, humor, word))
        val paragraph = TuiqiaoPreparer.prepare(config, TuiqiaoRequest("a.md", "他推开门。屋里很暗。", 0, 10))
        assertEquals(TuiqiaoMode.PARAGRAPH, paragraph.mode)
        assertEquals(listOf(humor.id.toString(), TuiqiaoSlot.DEFAULT_KEY), paragraph.slots.map { it.key })
        assertEquals(listOf(1, 2), paragraph.slots.map { it.count })
        assertTrue(paragraph.slots[1].prompt.contains("只输出 2 行"))
        assertEquals(Tuiqiao.MarkupPolicy.LENIENT, paragraph.slots[0].markup)
        assertEquals(Tuiqiao.MarkupPolicy.STRICT, paragraph.slots[1].markup)
        // Word level: only the word role applies.
        val wordPrepared = TuiqiaoPreparer.prepare(config, TuiqiaoRequest("a.txt", "推", 0, 1))
        assertEquals(listOf(word.id.toString(), TuiqiaoSlot.DEFAULT_KEY), wordPrepared.slots.map { it.key })
        assertEquals(Tuiqiao.MarkupPolicy.NONE, wordPrepared.slots[0].markup)
        // Roles may fill every slot; extra roles (in list order) don't apply.
        val many = (1..5).map { TuiqiaoRole(name = "r$it", prompt = "p$it", modes = setOf(TuiqiaoMode.WORD)) }
        val full = TuiqiaoPreparer.prepare(TuiqiaoConfig(candidateCount = 4, roles = many), TuiqiaoRequest("a.md", "推", 0, 1))
        assertEquals(many.take(4).map { it.id.toString() }, full.slots.map { it.key })
        assertNull(full.slots.firstOrNull { it.role == null })
        assertEquals(listOf(humor.id.toString()), paragraph.only(humor.id.toString())!!.slots.map { it.key })
        assertNull(paragraph.only("nope"))
    }

    @Test
    fun rolePromptReplacesOnlyTheTaskAndAsksForOne() {
        val config = TuiqiaoConfig(explanation = TuiqiaoExplanation.OFF, paragraphPrompt = "常规模板")
        val prompt = TuiqiaoPrompts.buildRole(config, humor, "第一章.md", TuiqiaoContext("前文。", "他推开门。", "后文。"))
        assertTrue(prompt.contains("<document name=\"第一章.md\">\n前文。<selection>他推开门。</selection>后文。\n</document>"))
        assertTrue(prompt.contains("任务：\n为选中部分加上幽默讽刺的旁白"))
        assertTrue(prompt.contains("只输出 1 行"))
        assertTrue(prompt.contains("字段为 text；"))
        assertTrue(prompt.contains("不需要解释"))
        assertTrue(prompt.contains("新增的标记要成对"))
        assertFalse(prompt.contains("常规模板"))
        assertFalse(prompt.contains("tone"))
        val withWhy = TuiqiaoPrompts.buildRole(TuiqiaoConfig(), humor, "a.txt", TuiqiaoContext("", "x", ""))
        assertTrue(withWhy.contains("字段为 text、why；"))
        assertFalse(withWhy.contains("Markdown 标记（如"))
    }

    @Test
    fun lenientMarkupAllowsAdditionsButNotLosses() {
        assertTrue(Tuiqiao.keepsMarkupLeniently("他**推开**门", "他**推开**门（*旁白：门很无辜*）"))
        assertTrue(Tuiqiao.keepsMarkupLeniently("见[月光](https://a.example)", "见[月光](https://a.example)，[注](https://b.example)"))
        assertFalse(Tuiqiao.keepsMarkupLeniently("他**推开**门", "他推开门"))
        assertFalse(Tuiqiao.keepsMarkupLeniently("推开门", "推开门（*旁白"))
        assertFalse(Tuiqiao.keepsMarkupLeniently("见[月光](https://a.example)", "见[月光](https://b.example)"))
        val collector = TuiqiaoCollector("推", 1, TuiqiaoExplanation.ONE_LINE, Tuiqiao.MarkupPolicy.LENIENT, keepTone = false)
        assertEquals(listOf(TuiqiaoCandidate("推（*门：疼*）", "", "w")),
            collector.offer(listOf(TuiqiaoCandidate("推（*门：疼*）", "tone", "w"), TuiqiaoCandidate("敲", ""))))
        val broken = TuiqiaoCollector("**推**", 1, TuiqiaoExplanation.ONE_LINE, Tuiqiao.MarkupPolicy.LENIENT)
        assertTrue(broken.offer(listOf(TuiqiaoCandidate("推", ""))).isEmpty())
        assertEquals(TuiqiaoException.Reason.MARKUP_BROKEN, broken.failure())
    }

    @Test
    fun oldConfigJsonStillDecodes() {
        val old = me.rerere.rikkihub.utils.JsonInstant.decodeFromString<TuiqiaoConfig>(
            """{"candidateCount":4,"explanation":"OFF","wordPrompt":null,"sentencePrompt":"s","paragraphPrompt":null}""")
        assertEquals(4, old.candidateCount)
        assertEquals("s", old.sentencePrompt)
        assertTrue(old.roles.isEmpty())
        assertNull(old.extensions)
        val withRole = TuiqiaoConfig(roles = listOf(humor), extensions = listOf("txt"))
        val json = me.rerere.rikkihub.utils.JsonInstant
        assertEquals(withRole, json.decodeFromString<TuiqiaoConfig>(json.encodeToString(withRole)))
    }
}
