package me.rerere.rikkihub.ui.pages.extensions.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceWritingStatsTest {
    /** The cases the web editor's port runs too. */
    @Test fun `shared stats cases`() {
        val cases = Json.parseToJsonElement(javaClass.getResource("/writing/stats-cases.json")!!.readText()).jsonArray
        for (case in cases.map { it.jsonObject }) {
            val text = case.getValue("text").jsonPrimitive.content
            val expected = WorkspaceTextStats(case.getValue("chars").jsonPrimitive.int, case.getValue("words").jsonPrimitive.int)
            assertEquals(text, expected, workspaceTextStats(text))
        }
    }

    @Test fun `a range counts only its own text`() {
        val text = "前文 hello 世界 后文"
        val start = text.indexOf("hello")
        val end = text.indexOf("世界") + 2
        assertEquals(WorkspaceTextStats(7, 3), workspaceTextStats(text, start, end))
        // A range splitting a word counts the part it has.
        assertEquals(WorkspaceTextStats(2, 1), workspaceTextStats(text, start + 3, start + 5))
    }
}
