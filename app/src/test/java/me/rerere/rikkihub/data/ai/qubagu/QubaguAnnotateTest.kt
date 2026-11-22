package me.rerere.rikkihub.data.ai.qubagu

import kotlinx.coroutines.runBlocking
import me.rerere.tavern.regex.QuickJsRegexRuntime
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作区文件的八股标注：整句范围、同句合并、本地建议怎么拼。 */
class QubaguAnnotateTest {
    companion object {
        private val runtime = QuickJsRegexRuntime()

        @AfterClass
        @JvmStatic
        fun close() = runtime.close()
    }

    private val engine = Qubagu(runtime)

    private fun config(vararg rules: QubaguRule, merge: Boolean = false) =
        QubaguConfig(packs = listOf(QubaguRulePack(id = "p", rules = rules.toList())), merge = merge)

    private fun annotate(text: String, config: QubaguConfig) = runBlocking { engine.annotate(text, config) }

    private val throat = QubaguRule.Replace(id = "throat", find = "喉结滚动", replacements = listOf("顿了顿", "沉默片刻", "一时无言", "欲言又止"))
    private val shock = QubaguRule.Replace(id = "shock", find = "心头一震", replacements = listOf("愣了一下", "怔了怔"))

    @Test
    fun aHitGrowsToItsWholeSentence() {
        val text = "门开了。他喉结滚动，没有说话。她走了进来。"
        val mark = annotate(text, config(throat)).single()
        assertEquals("他喉结滚动，没有说话。", mark.original)
        assertEquals(mark.original, text.substring(mark.start, mark.end))
        assertEquals(listOf("喉结滚动"), mark.phrases)
        assertEquals(listOf("p/throat"), mark.rules)
    }

    @Test
    fun hitsInOneSentenceShareAMarkAndOtherSentencesGetTheirOwn() {
        val text = "他喉结滚动，心头一震。\n她心头一震。"
        val marks = annotate(text, config(throat, shock))
        assertEquals(listOf("他喉结滚动，心头一震。", "她心头一震。"), marks.map { it.original })
        assertEquals(listOf("p/throat", "p/shock"), marks[0].rules)
        assertEquals(listOf("喉结滚动", "心头一震"), marks[0].phrases)
    }

    @Test
    fun suggestionsAreUpToThreeDistinctRewritesFromTheCandidates() {
        val mark = annotate("他喉结滚动，没有说话。", config(throat)).single()
        assertEquals(3, mark.suggestions.size)
        assertEquals(mark.suggestions.size, mark.suggestions.toSet().size)
        for (suggestion in mark.suggestions) {
            assertTrue(suggestion, suggestion.startsWith("他") && suggestion.endsWith("，没有说话。"))
            assertTrue(suggestion, suggestion.removePrefix("他").removeSuffix("，没有说话。") in throat.replacements)
        }
        // 第一条就是自动清理会挑的那个
        val cleaned = runBlocking { engine.clean("他喉结滚动，没有说话。", config(throat)) }.render("他喉结滚动，没有说话。")
        assertEquals(cleaned, mark.suggestions.first())
    }

    @Test
    fun fewerCandidatesGiveFewerSuggestions() {
        val single = QubaguRule.Replace(id = "s", find = "喉结滚动", replacements = listOf("顿了顿"))
        assertEquals(listOf("他顿了顿。"), annotate("他喉结滚动。", config(single)).single().suggestions)
        val delete = QubaguRule.Replace(id = "d", find = "不由得")
        assertEquals(listOf("他愣住了。"), annotate("他不由得愣住了。", config(delete)).single().suggestions)
        val sentence = QubaguRule.Replace(id = "end", find = "夜还很长。", atEnd = true)
        val ending = annotate("他走了。夜还很长。", config(sentence)).single()
        assertEquals("夜还很长。", ending.original)
        assertEquals(listOf(""), ending.suggestions)
    }

    @Test
    fun groupReferencesAreFilledInForEveryCandidate() {
        val rule = QubaguRule.Replace(id = "g", find = "嘴角勾起一抹(.{1,4}?)的弧度", regex = true, replacements = listOf("$1地笑了笑", "笑得$1"))
        val mark = annotate("他嘴角勾起一抹玩味的弧度。", config(rule)).single()
        assertEquals(setOf("他玩味地笑了笑。", "他笑得玩味。"), mark.suggestions.toSet())
    }

    @Test
    fun aSentenceWithSeveralHitsCombinesTheirCandidates() {
        val mark = annotate("他喉结滚动，心头一震。", config(throat, shock)).single()
        assertEquals(3, mark.suggestions.size)
        assertTrue(mark.suggestions.none { "喉结滚动" in it || "心头一震" in it })
    }

    @Test
    fun dialogueMarksStayInsideTheQuotes() {
        val rule = throat.copy(dialogue = true)
        val marks = annotate("他说：“我喉结滚动了。”然后喉结滚动。", config(rule))
        assertEquals(listOf("我喉结滚动了。", "然后喉结滚动。"), marks.map { it.original })
    }

    @Test
    fun joinedChoppyLinesAreOneMarkOverThoseLines() {
        val text = "七步。\n五步。\n三步。\n他停下了脚步，没有再往前走。"
        val mark = annotate(text, config(merge = true)).single()
        assertEquals("七步。\n五步。\n三步。", mark.original)
        assertEquals(listOf("七步，五步，三步。"), mark.suggestions)
        assertEquals(listOf(QubaguRules.MERGE), mark.rules)
    }

    @Test
    fun aJoinedRunKeepsItsOwnSingleSuggestion() {
        // 合并的那几行里还有别的命中：它们被并进合并那一条，候选不再单独拼
        val text = "沉默。\n心头一震。\n之后他才开口说话，声音很低。"
        val mark = annotate(text, config(shock, merge = true)).single()
        assertEquals(1, mark.suggestions.size)
        assertTrue(mark.suggestions.single(), mark.suggestions.single().startsWith("沉默，"))
    }

    @Test
    fun nothingToMarkWithoutRulesOrHits() {
        assertTrue(annotate("他喉结滚动。", QubaguConfig()).isEmpty())
        assertTrue(annotate("他推开门。", config(throat)).isEmpty())
        assertTrue(annotate("  ", config(throat)).isEmpty())
    }
}
