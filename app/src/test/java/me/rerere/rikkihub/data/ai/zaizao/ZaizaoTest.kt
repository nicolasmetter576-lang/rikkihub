package me.rerere.rikkihub.data.ai.zaizao

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZaizaoTest {
    private val source = "第一章\n\n他抄起铁锹，朝门口走去。\n\n门外站着一个人。"
    private val selStart = source.indexOf("他")
    private val selEnd = source.indexOf("门外") - 2

    private fun request(
        history: List<ZaizaoTurn> = emptyList(),
        round: ZaizaoRound = ZaizaoRound(ZaizaoType.MODIFY, "铲子上有三斤大便"),
        includeAfter: Boolean = true,
        avoid: List<String> = emptyList(),
        fileName: String = "ch1.md",
        start: Int = selStart - 1,
        end: Int = selEnd + 1,
    ) = ZaizaoRequest(fileName, source, start, end, includeAfter, history, round, avoid)

    private fun reason(block: () -> Unit): ZaizaoException.Reason {
        try {
            block()
        } catch (e: ZaizaoException) {
            return e.reason
        }
        fail("expected ZaizaoException")
        error("unreachable")
    }

    @Test
    fun firstRoundCarriesTheWholeDocumentTheTypeAndTheRequirement() {
        val prepared = Zaizao.prepare(ZaizaoConfig(), request())
        assertEquals("他抄起铁锹，朝门口走去。", prepared.original) // trimmed
        assertEquals(selStart, prepared.start)
        assertEquals(TuiqiaoFormat.MARKDOWN, prepared.format)
        val message = prepared.messages.single()
        assertEquals(MessageRole.USER, message.role)
        val text = message.toText()
        assertTrue("第一章\n\n<selection>他抄起铁锹，朝门口走去。</selection>\n\n门外站着一个人。\n</document>" in text)
        assertTrue("本轮类型：修改" in text)
        assertTrue(ZaizaoPrompts.DEFAULT_MODIFY in text)
        assertTrue("<requirement>\n铲子上有三斤大便\n</requirement>" in text)
        assertTrue("只输出 <result>…</result>" in text)
        assertTrue("Markdown 标记" in text)
        assertFalse("<plan>" in text)
        assertFalse("不必考虑与后文衔接" in text)
    }

    @Test
    fun afterTextOffSendsNothingAfterTheSelection() {
        val text = Zaizao.prepare(ZaizaoConfig(), request(includeAfter = false)).messages.single().toText()
        assertFalse("门外站着一个人" in text)
        assertTrue("<selection>他抄起铁锹，朝门口走去。</selection>\n</document>" in text)
        assertTrue("不必考虑与后文衔接" in text)
    }

    @Test
    fun plainTextFilesGetThePlainMarkupRule() {
        val text = Zaizao.prepare(ZaizaoConfig(), request(fileName = "ch1.txt")).messages.single().toText()
        assertTrue("不要添加 Markdown 标记" in text)
    }

    @Test
    fun rewriteAsksForAPlanFirst() {
        val text = Zaizao.prepare(ZaizaoConfig(), request(round = ZaizaoRound(ZaizaoType.REWRITE, "换个写法"))).messages.single().toText()
        assertTrue("本轮类型：重写" in text)
        assertTrue(ZaizaoPrompts.DEFAULT_REWRITE in text)
        assertTrue("先输出 <plan>…</plan>" in text)
    }

    @Test
    fun laterRoundsReplayTheChainAndOnlyAppendShortMessages() {
        val history = listOf(
            ZaizaoTurn(ZaizaoRound(ZaizaoType.REWRITE, "换个写法"), "<plan>事件：出门</plan>\n<result>一稿</result>"),
            ZaizaoTurn(ZaizaoRound(ZaizaoType.MODIFY, "铲子上有三斤大便"), "<result>二稿</result>"),
        )
        val prepared = Zaizao.prepare(ZaizaoConfig(), request(history = history, round = ZaizaoRound(ZaizaoType.INCREMENT, "加一段气味")))
        val roles = prepared.messages.map { it.role }
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), roles)
        // The first message is the first round, exactly as it was sent then (prefix cache).
        val firstOnly = Zaizao.prepare(ZaizaoConfig(), request(round = history[0].round)).messages.single().toText()
        assertEquals(firstOnly, prepared.messages[0].toText())
        assertEquals(history[0].output, prepared.messages[1].toText())
        val second = prepared.messages[2].toText()
        assertTrue(second.startsWith("接着改"))
        assertTrue("本轮类型：修改" in second && "铲子上有三斤大便" in second)
        assertFalse("<document" in second)
        val last = prepared.messages[4].toText()
        assertTrue("本轮类型：增量" in last && "加一段气味" in last)
        assertTrue("上一条回复里 <result>" in last)
    }

    @Test
    fun anotherDraftListsTheDraftsAlreadyGiven() {
        val first = Zaizao.prepare(ZaizaoConfig(), request(round = ZaizaoRound(ZaizaoType.REWRITE, "换"), avoid = listOf("甲稿", " "))).messages.single().toText()
        assertTrue("这一轮已经给过下面这些稿" in first)
        assertTrue("呈现手法、描写和段落结构都不得重复" in first)
        assertTrue("<draft>\n甲稿\n</draft>" in first)
        assertEquals(1, Regex("<draft>").findAll(first).count())

        val history = listOf(ZaizaoTurn(ZaizaoRound(ZaizaoType.MODIFY, "甲"), "<result>一稿</result>"))
        val later = Zaizao.prepare(ZaizaoConfig(), request(history = history, avoid = listOf("乙稿"))).messages
        assertFalse("<draft>" in later[0].toText()) // only the round being redone lists them
        assertTrue("<draft>\n乙稿\n</draft>" in later[2].toText())
        assertTrue("换一种改法" in later[2].toText())
    }

    @Test
    fun customTemplatesReplaceOnlyTheTask() {
        val config = ZaizaoConfig().withPrompt(ZaizaoType.MODIFY, "只改动词。")
        val text = Zaizao.prepare(config, request()).messages.single().toText()
        assertTrue("任务：\n只改动词。\n" in text)
        assertFalse(ZaizaoPrompts.DEFAULT_MODIFY in text)
        assertTrue("<requirement>" in text && "只输出 <result>" in text)
        assertEquals(ZaizaoPrompts.DEFAULT_MODIFY, ZaizaoConfig(modifyPrompt = "  ").promptFor(ZaizaoType.MODIFY))
    }

    @Test
    fun rejectsBadSelectionsRequirementsAndTooManyRounds() {
        assertEquals(ZaizaoException.Reason.EMPTY_SELECTION, reason { Zaizao.prepare(ZaizaoConfig(), request(start = 3, end = 5)) })
        val long = "字".repeat(Zaizao.MAX_SELECTION_CHARS + 1)
        assertEquals(ZaizaoException.Reason.SELECTION_TOO_LONG, reason {
            Zaizao.prepare(ZaizaoConfig(), ZaizaoRequest("a.md", long, 0, long.length, true, emptyList(), ZaizaoRound(ZaizaoType.MODIFY, "x")))
        })
        assertEquals(ZaizaoException.Reason.EMPTY_REQUIREMENT, reason { Zaizao.prepare(ZaizaoConfig(), request(round = ZaizaoRound(ZaizaoType.MODIFY, " \n"))) })
        assertEquals(ZaizaoException.Reason.REQUIREMENT_TOO_LONG, reason {
            Zaizao.prepare(ZaizaoConfig(), request(round = ZaizaoRound(ZaizaoType.MODIFY, "x".repeat(ZaizaoConfig.MAX_REQUIREMENT_CHARS + 1))))
        })
        val turn = ZaizaoTurn(ZaizaoRound(ZaizaoType.MODIFY, "x"), "<result>y</result>")
        Zaizao.prepare(ZaizaoConfig(), request(history = List(Zaizao.MAX_ROUNDS - 1) { turn }))
        assertEquals(ZaizaoException.Reason.TOO_MANY_ROUNDS, reason { Zaizao.prepare(ZaizaoConfig(), request(history = List(Zaizao.MAX_ROUNDS) { turn })) })
    }

    @Test
    fun parsesTaggedOutputWhileStreamingAndAtTheEnd() {
        assertEquals(ZaizaoOutput("", ""), Zaizao.parse("<res", finished = false))
        assertEquals(ZaizaoOutput("", "他抄起"), Zaizao.parse("<result>他抄起", finished = false))
        assertEquals(ZaizaoOutput("", "他抄起铁锹"), Zaizao.parse("<result>他抄起铁锹</res", finished = false))
        assertEquals(ZaizaoOutput("", "他抄起铁锹。"), Zaizao.parse("<result>\n他抄起铁锹。\n</result>\n多余的话", finished = true))
        assertEquals(ZaizaoOutput("事件：出门", ""), Zaizao.parse("<plan>事件：出门</plan>\n<re", finished = false))
        assertEquals(ZaizaoOutput("事件", ""), Zaizao.parse("<plan>事件</pl", finished = false))
        assertEquals(ZaizaoOutput("事件：出门", "门被铁锹截成两半。"),
            Zaizao.parse("<plan>事件：出门</plan>\n<result>门被铁锹截成两半。</result>", finished = true))
    }

    @Test
    fun untaggedOutputFallsBackToTheWholeText() {
        assertEquals("他抄起铁锹", Zaizao.parse("他抄起铁锹", finished = false).result)
        assertEquals("他抄起铁锹。", Zaizao.parse("  他抄起铁锹。\n", finished = true).result)
        assertEquals("他抄起铁锹。", Zaizao.parse("```text\n他抄起铁锹。\n```", finished = true).result)
        assertEquals("", Zaizao.parse("```te", finished = false).result)
        assertEquals("他抄起", Zaizao.parse("```\n他抄起", finished = false).result)
        assertEquals(ZaizaoOutput("事件", "正文。"), Zaizao.parse("<plan>事件</plan>\n正文。", finished = true))
        assertEquals("", Zaizao.parse("<plan>事件</plan>\n正文", finished = false).result) // planning: wait for <result>
        assertEquals("", Zaizao.parse("<plan>只写了构思", finished = true).result)
    }

    @Test
    fun rememberKeepsTheNewestTenWithoutDuplicates() {
        var config = ZaizaoConfig()
        (1..12).forEach { config = config.remember("要求$it") }
        assertEquals((12 downTo 3).map { "要求$it" }, config.recentRequirements)
        config = config.remember(" 要求5 ")
        assertEquals("要求5", config.recentRequirements.first())
        assertEquals(ZaizaoConfig.MAX_RECENT, config.recentRequirements.size)
        assertEquals(1, config.recentRequirements.count { it == "要求5" })
        assertEquals(config, config.remember("  "))
        assertFalse("要求5" in config.forget("要求5").recentRequirements)
    }

    @Test
    fun configDecodesOldAndPartialJson() {
        assertEquals(ZaizaoConfig(), JsonInstant.decodeFromString<ZaizaoConfig>("{}"))
        val decoded = JsonInstant.decodeFromString<ZaizaoConfig>(
            """{"reasoningLevel":"high","temperature":0.7,"defaultType":"REWRITE","unknown":1}""")
        assertEquals(ReasoningLevel.HIGH, decoded.reasoningLevel)
        assertEquals(0.7f, decoded.temperature)
        assertEquals(ZaizaoType.REWRITE, decoded.defaultType)
        val round = JsonInstant.decodeFromString<ZaizaoConfig>(JsonInstant.encodeToString(ZaizaoConfig.serializer(), decoded))
        assertEquals(decoded, round)
    }

    @Test
    fun effectiveParametersAreClampedOrDropped() {
        val config = ZaizaoConfig(temperature = 9f, topP = -1f, maxTokens = 0)
        assertEquals(2f, config.effectiveTemperature)
        assertEquals(0f, config.effectiveTopP)
        assertEquals(null, config.effectiveMaxTokens)
        assertEquals(null, ZaizaoConfig(temperature = Float.NaN).effectiveTemperature)
    }
}
