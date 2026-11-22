package me.rerere.tavern.worldinfo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目/世界书 JSON 的往返无损：解码再编码与原 JSON 语义相等（JsonObject 相等，不比较字段顺序）。
 *
 * 上游 `default/content` 里没有世界书文件，所以 `resources/worldinfo/roundtrip-book.json` 是自造的：
 * uid=7 的条目覆盖全部已知字段的非默认值加两个未知字段；uid=3 的条目故意用非规范写法
 * （`"3"`、`disable:1`、`delayUntilRecursion:false`、`role:null`、`sticky:null`、字符串化的 order），
 * 解码后语义相同的话原始写法要原样写回；uid=5 只有 uid，编码后不得凭空冒出默认字段。
 * 顶层 entries 之外的名/描述/未知键也要求保留。
 */
class WorldInfoCodecRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): JsonObject =
        json.parseToJsonElement(
            WorldInfoCodecRoundTripTest::class.java.getResource(path)!!.readText(),
        ) as JsonObject

    @Test
    fun fullFieldBookRoundTripsLosslessly() {
        val original = resource("/worldinfo/roundtrip-book.json")

        val book = WorldInfoCodec.decodeBook(original)
        assertEquals("条目数", 3, book.entries.size)
        assertEquals("RoundTripBook", book.name)

        val encoded = WorldInfoCodec.encodeBook(book)
        assertEquals(original, encoded)
    }

    @Test
    fun entryRoundTripsLosslessly() {
        val book = resource("/worldinfo/roundtrip-book.json")
        val entries = book.getValue("entries") as JsonObject

        for ((uid, entryEl) in entries) {
            val entry = WorldInfoCodec.decodeEntry(entryEl as JsonObject, uid.toInt())
            val encoded = WorldInfoCodec.encodeEntry(entry)
            assertEquals("条目 $uid 往返不一致", entryEl, encoded)
        }
    }

    @Test
    fun doubleRoundTripIsStable() {
        val original = resource("/worldinfo/roundtrip-book.json")

        val once = WorldInfoCodec.encodeBook(WorldInfoCodec.decodeBook(original))
        val twice = WorldInfoCodec.encodeBook(WorldInfoCodec.decodeBook(once))
        assertEquals(once, twice)
        assertEquals(original, twice)
    }

    @Test
    fun bookEntryPointsMatchCodec() {
        val original = resource("/worldinfo/roundtrip-book.json")
        val viaCodec = WorldInfoCodec.encodeBook(WorldInfoCodec.decodeBook(original))
        val viaModel = WorldInfoBook.fromJson(original).toJson()
        assertTrue(viaCodec == viaModel)
    }
}
