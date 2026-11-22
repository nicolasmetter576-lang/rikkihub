package me.rerere.rikkihub.data.ai.tavern

import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.regex.SubstituteFindRegex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexCheckTest {
    private val runtime = TavernRuntime()
    private val regexes = TavernRegexes(runtime)
    private val settings = Settings(displaySetting = Settings().displaySetting.copy(userNickname = "Alice"))

    @After fun close() = runtime.close()

    private fun check(find: String, substitute: Int = SubstituteFindRegex.NONE) =
        runBlocking { regexes.checkFindRegex(find, substitute) }

    @Test fun blankIsEmpty() = assertEquals(RegexCheck.Empty, check("  "))

    @Test fun literalsAndBarePatternsAreOk() {
        assertEquals(RegexCheck.Ok, check("/a+/gi"))
        assertEquals(RegexCheck.Ok, check("a+"))
        assertEquals(RegexCheck.Ok, check("/(?<n>x)\\k<n>/g"))
    }

    @Test fun brokenPatternsReportTheEnginesMessage() {
        val literal = check("/(/g")
        assertTrue(literal is RegexCheck.Invalid)
        assertTrue((literal as RegexCheck.Invalid).message?.isNotBlank() == true)
        assertTrue(check("(") is RegexCheck.Invalid)
        assertTrue(check("/a/gg") is RegexCheck.Invalid)
    }

    @Test fun findStringsWithSubstitutionAreNotJudgedAhead() {
        assertEquals(RegexCheck.Skipped, check("/{{user}}/g", SubstituteFindRegex.RAW))
        assertEquals(RegexCheck.Skipped, check("/(/g", SubstituteFindRegex.ESCAPED))
    }

    private fun preview(script: RegexScript, sample: String) =
        runBlocking { regexes.preview(script, sample, settings, Assistant(name = "Bot")) }

    @Test fun previewRunsTheRealEngineWithCapturesAndMacros() {
        val s = RegexScript(id = "1", findRegex = "/(\\w+)@(\\w+)/g", replaceString = "{{user}}: $2 <- $1", placement = listOf(2))
        assertEquals("Alice: host <- me and Alice: x <- y", preview(s, "me@host and y@x"))
    }

    @Test fun previewRunsDisabledScriptsToo() {
        val s = RegexScript(id = "1", findRegex = "/a/g", replaceString = "b", placement = listOf(2), disabled = true)
        assertEquals("bbb", preview(s, "aaa"))
    }

    @Test fun previewLeavesTextAloneWhenNothingMatchesOrPatternIsBroken() {
        assertEquals("hello", preview(RegexScript(id = "1", findRegex = "/zzz/g", replaceString = "x"), "hello"))
        assertEquals("hello", preview(RegexScript(id = "1", findRegex = "/(/g", replaceString = "x"), "hello"))
    }
}
