package me.rerere.tavern.card

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PngCardsTest {
    private val card = CardSupport.parse("v2-harbor-cartographer.json")
    private val image = PngCards.placeholder("fixture", 32, 48)

    private fun types(png: ByteArray) = PngCards.chunks(png).map { it.type }

    @Test
    fun writtenCardReadsBackAsV3AndTheImageChunksAreUntouched() {
        val png = CardFiles.toPng(card, image)
        val before = PngCards.chunks(image).filter { it.type != "tEXt" }
        val after = PngCards.chunks(png).filter { it.type != "tEXt" }
        assertEquals(before.map { it.type }, after.map { it.type })
        before.zip(after).forEach { (a, b) -> assertArrayEquals(a.raw, b.raw) }
        assertEquals(listOf("IHDR", "IDAT", "tEXt", "tEXt", "IEND"), types(png))
        val read = CardCodec.parse(PngCards.read(png))
        assertEquals("v3", read.sourceFormat)
        assertEquals(card.toJson(), read.card.toJson())
    }

    @Test
    fun theCharaBlockIsAV2CardAndTheCcv3BlockIsV3() {
        val png = CardFiles.toPng(CardSupport.parse("v3-clockwork-orchard.json"), image)
        val texts = PngCards.chunks(png).filter { it.type == "tEXt" }.map {
            val nul = it.data.indexOf(0.toByte())
            String(it.data, 0, nul) to String(Base64.getDecoder().decode(String(it.data, nul + 1, it.data.size - nul - 1)), Charsets.UTF_8)
        }.toMap()
        assertTrue(texts.getValue("chara").contains("\"chara_card_v2\""))
        assertFalse(texts.getValue("chara").contains("group_only_greetings"))
        assertTrue(texts.getValue("ccv3").contains("\"chara_card_v3\""))
        assertTrue(texts.getValue("ccv3").contains("group_only_greetings"))
    }

    @Test
    fun rewritingReplacesTheOldBlocksInsteadOfStackingThem() {
        val once = CardFiles.toPng(card, image)
        val twice = CardFiles.toPng(card.withData("description", kotlinx.serialization.json.JsonPrimitive("second")), once)
        assertEquals(listOf("IHDR", "IDAT", "tEXt", "tEXt", "IEND"), types(twice))
        assertEquals("second", CardCodec.parse(PngCards.read(twice)).card.description)
    }

    @Test
    fun ccv3WinsOverCharaWhateverTheirOrderAndKeywordCase() {
        val v2 = CardCodec.encodeV2(card).toString()
        val v3 = CardCodec.encodeV3(card.withData("name", kotlinx.serialization.json.JsonPrimitive("From V3"))).toString()
        // ccv3 first, then chara; keywords in upper case
        val png = injectText(image, "CCV3" to v3, "CHARA" to v2)
        assertEquals("From V3", CardCodec.parse(PngCards.read(png)).card.name)
        val charaOnly = injectText(image, "chara" to v2)
        assertEquals("Ines Okafor", CardCodec.parse(PngCards.read(charaOnly)).card.name)
    }

    @Test
    fun aPngWrittenByTheRealTavernReadsBackWithTheV3BlockWinning() {
        // recorded with the tavern's own character-card-parser.write (tavern/oracle/card/st_node.mjs): it writes
        // `chara` plus a `ccv3` copy whose spec is rewritten to chara_card_v3
        val png = PngCardsTest::class.java.getResource("/golden/card/st-written.png")!!.readBytes()
        val expected = PngCardsTest::class.java.getResource("/golden/card/st-written.read.json")!!.readText()
        assertEquals(expected, PngCards.read(png))
        val imported = CardFiles.read(png)
        assertEquals("png", imported.container)
        assertEquals("Ines Okafor", imported.card.name)
        assertEquals(3, imported.card.characterBook!!.getValue("entries").let { (it as kotlinx.serialization.json.JsonArray).size })
        assertEquals(1, imported.card.regexScripts.size)
        // the stored image has no card blocks left
        assertEquals(listOf("IHDR", "IDAT", "IEND"), types(imported.avatar!!))
        // and it carries the same character as the plain V2 file, minus the favourite mark an import drops
        val plain = CardSupport.parse("v2-harbor-cartographer.json").withExtension("fav", kotlinx.serialization.json.JsonPrimitive(false))
        assertEquals(plain.data, imported.card.data)
    }

    @Test
    fun uncompressedItxtBlocksAreReadToo() {
        val v3 = CardCodec.encodeV3(card).toString()
        val b64 = Base64.getEncoder().encodeToString(v3.toByteArray())
        val data = "ccv3".toByteArray() + byteArrayOf(0, 0, 0, 0, 0) + b64.toByteArray()
        val png = insertBeforeEnd(image, chunk("iTXt", data))
        assertEquals("Ines Okafor", CardCodec.parse(PngCards.read(png)).card.name)
    }

    @Test
    fun stripCardLeavesOnlyTheImage() {
        val png = CardFiles.toPng(card, image)
        assertArrayEquals(image, PngCards.stripCard(png))
    }

    @Test
    fun aPngWithoutCardDataIsRejected() {
        try { PngCards.read(image); fail() } catch (e: CardFormatException) { assertTrue(e.message!!.contains("text")) }
        val other = injectText(image, "Software" to "paint")
        try { PngCards.read(other); fail() } catch (e: CardFormatException) { assertTrue(e.message!!.contains("chara")) }
    }

    @Test
    fun brokenInputIsRejectedNotCrashed() {
        val png = CardFiles.toPng(card, image)
        for (bad in listOf(ByteArray(0), byteArrayOf(1, 2, 3), png.copyOf(png.size - 20), png.copyOf(30))) {
            try { PngCards.read(bad); fail("should reject ${bad.size} bytes") } catch (_: CardFormatException) {}
        }
        val badB64 = injectText(image, "chara" to "###", rawText = true)
        try { PngCards.read(badB64); fail() } catch (e: CardFormatException) { assertTrue(e.message!!.contains("base64")) }
    }

    @Test
    fun anImageIsNotRequiredAndAnyOtherBytesFallBackToThePlaceholder() {
        val png = CardFiles.toPng(card, null)
        assertTrue(PngCards.isPng(png))
        assertEquals("Ines Okafor", CardCodec.parse(PngCards.read(png)).card.name)
        val fromJpegBytes = CardFiles.toPng(card, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2))
        assertTrue(PngCards.isPng(fromJpegBytes))
    }

    @Test
    fun placeholderIsDeterministicPerNameAndAValidPng() {
        assertArrayEquals(PngCards.placeholder("a", 16, 16), PngCards.placeholder("a", 16, 16))
        assertFalse(PngCards.placeholder("a", 16, 16).contentEquals(PngCards.placeholder("b", 16, 16)))
        assertEquals(listOf("IHDR", "IDAT", "IEND"), types(PngCards.placeholder("a", 16, 16)))
    }

    @Test
    fun unicodeSurvivesTheBase64Utf8Trip() {
        val c = card.withData("description", kotlinx.serialization.json.JsonPrimitive("港口制图师 🧭 — ünïcode"))
        val back = CardCodec.parse(PngCards.read(CardFiles.toPng(c, image))).card
        assertEquals("港口制图师 🧭 — ünïcode", back.description)
    }

    /** 给 `tavern/oracle/card/st_node.mjs png-read` 用：设了 CARD_INTEROP_DIR 就把本实现写的 PNG 落到那里，让真 ST 读一遍。 */
    @Test
    fun dumpsSamplesForTheTavernToRead() {
        val dir = System.getenv("CARD_INTEROP_DIR") ?: return
        java.io.File(dir).mkdirs()
        for (name in listOf("v1-lantern-keeper.json", "v2-harbor-cartographer.json", "v3-clockwork-orchard.json")) {
            val c = CardSupport.parse(name)
            java.io.File(dir, name.removeSuffix(".json") + ".png").writeBytes(CardFiles.toPng(c, image))
            java.io.File(dir, name.removeSuffix(".json") + ".v3.json").writeText(CardCodec.encodeV3(c).toString())
        }
    }

    // --- helpers: build PNGs with arbitrary text blocks ---
    private fun injectText(png: ByteArray, vararg pairs: Pair<String, String>, rawText: Boolean = false): ByteArray {
        var out = png
        for ((k, v) in pairs.reversed()) {
            val text = if (rawText) v else Base64.getEncoder().encodeToString(v.toByteArray())
            out = insertBeforeEnd(out, chunk("tEXt", k.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)))
        }
        return out
    }

    private fun insertBeforeEnd(png: ByteArray, extra: ByteArray): ByteArray {
        val end = png.size - 12
        return png.copyOfRange(0, end) + extra + png.copyOfRange(end, png.size)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        out.write(i32(data.size)); out.write(type.toByteArray()); out.write(data)
        out.write(i32(CRC32().apply { update(type.toByteArray()); update(data) }.value.toInt()))
        return out.toByteArray()
    }
}
