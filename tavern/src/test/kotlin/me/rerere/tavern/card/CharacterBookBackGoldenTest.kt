package me.rerere.tavern.card

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回放真 SillyTavern 1.19 的 `convertWorldInfoToCharacterBook`（`tavern/oracle/card/record.sh back`）：
 * 世界书条目转回卡内世界书，条目的每个键都要和上游一致。唯一的写法差异是 `delay_until_recursion`
 * （上游原样写回 `false`，这里存的是层数 0），按真值比较。
 */
class CharacterBookBackGoldenTest {
    private val golden = CardSupport.json.parseToJsonElement(
        CharacterBookBackGoldenTest::class.java.getResource("/golden/card/book-back.json")!!.readText(),
    ).jsonObject.getValue("cases").jsonObject

    private val input = CardSupport.json.parseToJsonElement(
        CharacterBookBackGoldenTest::class.java.getResource("/golden/card/convert-book.json")!!.readText(),
    ).jsonObject.getValue("cases").jsonObject

    private fun normalise(entry: JsonObject): JsonObject {
        val ext = entry["extensions"] as JsonObject
        val delay = ext["delay_until_recursion"] as JsonPrimitive
        val asInt = delay.booleanOrNull?.let { if (it) 1 else 0 } ?: delay.content.toDouble().toInt()
        // upstream copies a truthy non-boolean (`"yes"`, `1`) through as it came; the meaning is the same
        val truthy = listOf("constant", "selective").mapNotNull { k -> entry[k]?.let { k to JsonPrimitive(it.isTruthy()) } }
        return JsonObject(entry + truthy + ("extensions" to JsonObject(ext + ("delay_until_recursion" to JsonPrimitive(asInt)))))
    }

    private fun kotlinx.serialization.json.JsonElement.isTruthy(): Boolean {
        val p = this as? JsonPrimitive ?: return true
        if (p.booleanOrNull != null) return p.booleanOrNull!!
        return p.isString && p.content.isNotEmpty() || !p.isString && p.content.toDoubleOrNull()?.let { it != 0.0 } == true
    }

    @Test
    fun everyBookConvertsBackLikeTheRealTavern() {
        for ((name, case) in golden) {
            val c = case.jsonObject
            val book = CharacterBooks.toWorldInfo(input.getValue(name).jsonObject.getValue("input").jsonObject)
            val ours = CharacterBooks.fromWorldInfo(book, c.getValue("name").jsonPrimitive.content)
            val theirs = c.getValue("output").jsonObject
            assertEquals("$name: book name", theirs["name"], ours["name"])
            val a = theirs.getValue("entries").jsonArray.map { normalise(it.jsonObject) }
            val b = (ours.getValue("entries") as JsonArray).map { normalise(it.jsonObject) }
            assertEquals("$name: entry count", a.size, b.size)
            for ((x, y) in a.zip(b)) {
                // an entry that had no `insertion_order` has `order: undefined` upstream, which JSON drops; ours writes the default
                val mine = if ("insertion_order" in x) y else JsonObject(y - "insertion_order")
                assertEquals("$name entry ${x["id"]}", x, mine)
            }
        }
    }
}
