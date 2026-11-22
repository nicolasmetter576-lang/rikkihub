package me.rerere.tavern.card

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放真 SillyTavern 1.19 的 `convertCharacterBook` 录制结果（`tavern/oracle/card/record.sh book`）：
 * 同一本卡内世界书，本实现转出的条目必须和上游转出的条目逐字段相同。
 */
class CharacterBookGoldenTest {
    private val golden = CardSupport.json.parseToJsonElement(
        CharacterBookGoldenTest::class.java.getResource("/golden/card/convert-book.json")!!.readText(),
    ).jsonObject.getValue("cases").jsonObject

    /** 上游转出的条目：在比较前用同一个解码器规整，条目里的 `extensions` 是卡内原对象，对比时不看。 */
    private fun typed(e: WorldInfoEntry) = e.copy(extra = JsonObject(emptyMap()), layout = null)

    @Test
    fun everyBookConvertsLikeTheRealTavern() {
        assertTrue(golden.size >= 4)
        for ((name, case) in golden) {
            val c = case.jsonObject
            val ours = CharacterBooks.toWorldInfo(c.getValue("input").jsonObject)
            val upstream = c.getValue("entries").jsonObject.map { (k, v) ->
                WorldInfoCodec.decodeEntry(v.jsonObject, k.toInt())
            }
            assertEquals("$name: uids", upstream.map { it.uid }, ours.entries.map { it.uid })
            for ((theirs, mine) in upstream.zip(ours.entries)) {
                assertEquals("$name uid ${theirs.uid}", typed(theirs), typed(mine))
            }
        }
    }

    @Test
    fun theUpstreamEntryExtensionsObjectIsKeptWholeOnOurEntries() {
        for ((name, case) in golden) {
            val c = case.jsonObject
            val ours = CharacterBooks.toWorldInfo(c.getValue("input").jsonObject)
            for ((k, v) in c.getValue("entries").jsonObject) {
                val mine = ours.entries.first { it.uid == k.toInt() }
                assertEquals("$name $k extensions", v.jsonObject["extensions"], mine.extra["extensions"])
            }
        }
    }

    @Test
    fun oursAlsoWritesTheSameKeysTheUpstreamEntryHas() {
        // every key the upstream entry carries is a key our decoder knows or keeps (nothing is silently dropped)
        for ((name, case) in golden) {
            val c = case.jsonObject
            for ((k, v) in c.getValue("entries").jsonObject) {
                val decoded = WorldInfoCodec.decodeEntry(v.jsonObject, k.toInt())
                val re = WorldInfoCodec.encodeEntry(decoded)
                for (key in v.jsonObject.keys) assertTrue("$name $k lost $key", key in re.keys)
            }
        }
    }
}
