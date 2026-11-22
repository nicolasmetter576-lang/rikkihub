package me.rerere.rikkihub.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableEditingTest {
    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

    @Test fun displayShowsScalarsPlainAndStructuresAsJson() {
        assertEquals("calm", variableDisplay(JsonPrimitive("calm")))
        assertEquals("3", variableDisplay(JsonPrimitive(3)))
        assertEquals("true", variableDisplay(JsonPrimitive(true)))
        assertEquals("", variableDisplay(JsonNull))
        assertEquals("[1,2]", variableDisplay(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))))
        assertEquals("{\"a\":1}", variableDisplay(obj("a" to JsonPrimitive(1))))
    }

    @Test fun newVariablesAreStrings() {
        assertEquals(JsonPrimitive("5"), parseVariableValue("5", null))
        assertTrue((parseVariableValue("5", null) as JsonPrimitive).isString)
    }

    @Test fun numbersStayNumbersWhileTheTextIsNumeric() {
        val previous = JsonPrimitive(3)
        assertEquals(JsonPrimitive(7L), parseVariableValue(" 7 ", previous))
        assertEquals(JsonPrimitive(2.5), parseVariableValue("2.5", previous))
        val turned = parseVariableValue("many", previous)
        assertTrue("non-numeric text turns it into a string", (turned as JsonPrimitive).isString)
        assertEquals("many", turned.content)
    }

    @Test fun booleansStayBooleansForTrueAndFalse() {
        assertEquals(JsonPrimitive(false), parseVariableValue("false", JsonPrimitive(true)))
        assertTrue((parseVariableValue("maybe", JsonPrimitive(true)) as JsonPrimitive).isString)
    }

    @Test fun stringsThatLookNumericStayStrings() {
        val result = parseVariableValue("42", JsonPrimitive("7")) as JsonPrimitive
        assertTrue(result.isString)
        assertEquals("42", result.content)
    }

    @Test fun settingAndRemovingVariablesKeepsTheOthersAndTheirOrder() {
        val vars = obj("a" to JsonPrimitive(1), "b" to JsonPrimitive("x"))
        val edited = vars.withVariable("a", "9").withVariable("c", "new")
        assertEquals(listOf("a", "b", "c"), edited.keys.toList())
        assertEquals(JsonPrimitive(9L), edited["a"])
        assertEquals(JsonPrimitive("new"), edited["c"])
        assertEquals(listOf("b"), edited.withoutVariable("a").withoutVariable("c").keys.toList())
    }

    @Test fun blankNamesAreIgnoredAndNamesAreTrimmed() {
        val vars = obj("a" to JsonPrimitive(1))
        assertEquals(vars, vars.withVariable("   ", "x"))
        assertEquals(listOf("a", "b"), vars.withVariable("  b ", "x").keys.toList())
    }
}
