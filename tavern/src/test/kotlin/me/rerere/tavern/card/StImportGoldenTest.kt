package me.rerere.tavern.card

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三张自造卡交给真 SillyTavern 1.19 的服务端导入（`tavern/oracle/card/record.sh import`），上游存下的卡和本实现读出的卡
 * 在所有有意义的字段上必须一致。已知的、有意的差异：话痨度上游保留原文（字符串 "0.7"），这里按数字读；
 * 上游在顶层多存一份 V1 字段和运行时字段，这里不存。
 */
class StImportGoldenTest {
    private val golden = CardSupport.json.parseToJsonElement(
        StImportGoldenTest::class.java.getResource("/golden/card/st-import.json")!!.readText(),
    ).jsonObject.getValue("cases").jsonObject

    private fun ours(file: String) = CardFiles.read(CardSupport.text(file).toByteArray()).card

    @Test
    fun theFieldsOfEveryFixtureMatchWhatTheTavernStored() {
        for ((file, case) in golden) {
            val st = case.jsonObject.getValue("data").jsonObject
            val mine = ours(file)
            for (key in listOf("name", "description", "personality", "scenario", "first_mes", "mes_example", "creator_notes",
                "system_prompt", "post_history_instructions", "creator", "character_version")) {
                assertEquals("$file $key", (st[key] as JsonPrimitive).content, (mine.data[key] as JsonPrimitive).content)
            }
            assertEquals("$file tags", st["tags"], mine.data["tags"])
            assertEquals("$file alternate_greetings", st["alternate_greetings"], mine.data["alternate_greetings"])
            assertEquals("$file character_book", st["character_book"], mine.data["character_book"])
            for (key in listOf("assets", "nickname", "creator_notes_multilingual", "source", "group_only_greetings", "creation_date", "modification_date")) {
                assertEquals("$file $key", st[key], mine.data[key])
            }
        }
    }

    @Test
    fun theExtensionsMatchAfterTheTavernsOwnImportRules() {
        for ((file, case) in golden) {
            val st = case.jsonObject.getValue("data").jsonObject.getValue("extensions").jsonObject
            val mine = ours(file)
            assertEquals("$file world", (st["world"] as? JsonPrimitive)?.content ?: "", mine.world)
            assertEquals("$file fav", false, mine.fav)
            assertEquals("$file talkativeness", number(st["talkativeness"]) ?: 0.5, mine.talkativeness, 0.0)
            val dp = st["depth_prompt"] as JsonObject
            assertEquals("$file depth prompt text", (dp["prompt"] as JsonPrimitive).content, mine.depthPrompt.prompt)
            assertEquals("$file depth", number(dp["depth"])!!.toInt(), mine.depthPrompt.depth)
            assertEquals("$file role", (dp["role"] as JsonPrimitive).content, mine.depthPrompt.role.name.lowercase())
            assertEquals("$file regex_scripts", st["regex_scripts"], mine.extensions["regex_scripts"])
            // keys we have no field for are carried through untouched
            for ((k, v) in st) if (k !in setOf("talkativeness", "fav", "world", "depth_prompt")) assertEquals("$file ext $k", v, mine.extensions[k])
        }
    }

    @Test
    fun v1V2AndV3AreReportedAsSuch() {
        assertEquals(listOf("v1", "v2", "v3"), listOf("v1-lantern-keeper.json", "v2-harbor-cartographer.json", "v3-clockwork-orchard.json")
            .map { CardFiles.read(CardSupport.text(it).toByteArray()).cardFormat })
    }

    private fun number(el: JsonElement?): Double? = (el as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
}
