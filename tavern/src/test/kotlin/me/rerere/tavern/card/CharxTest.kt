package me.rerere.tavern.card

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CharxTest {
    private val card = CardSupport.parse("v3-clockwork-orchard.json")
    private val icon = PngCards.placeholder("icon", 8, 8)
    private val background = PngCards.placeholder("bg", 8, 8)

    private fun zip(entries: Map<String, ByteArray>, prefix: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(prefix)
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return out.toByteArray()
    }

    @Test
    fun readsTheCardTheMainIconAndTheReferencedAssets() {
        val bytes = zip(mapOf(
            "card.json" to CardSupport.text("v3-clockwork-orchard.json").toByteArray(),
            "assets/icon/images/main.png" to icon,
            "assets/background/images/orchard.png" to background,
            "unreferenced.txt" to "x".toByteArray(),
        ))
        val c = Charx.read(bytes)
        assertEquals("Tobias Rennick", c.card.name)
        assertArrayEquals(icon, c.avatar)
        assertEquals("png", c.avatarExt)
        assertEquals(setOf("assets/icon/images/main.png", "assets/background/images/orchard.png"), c.assets.keys)
    }

    @Test
    fun writeThenReadKeepsTheCardAndAddsTheAvatarAsTheMainIcon() {
        val plain = card.withData("assets", null)
        val bytes = Charx.write(plain, icon, "png", mapOf("assets/misc/notes.txt" to "hi".toByteArray()))
        val back = Charx.read(bytes)
        assertEquals(plain.name, back.card.name)
        assertArrayEquals(icon, back.avatar)
        val asset = back.card.assets.single()
        assertEquals("icon", (asset["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("embeded://assets/icon/images/main.png", (asset["uri"] as kotlinx.serialization.json.JsonPrimitive).content)
        // a second round does not stack another icon
        val again = Charx.read(Charx.write(back.card, back.avatar, back.avatarExt, back.assets))
        assertEquals(1, again.card.assets.size)
    }

    @Test
    fun alternateUriSpellingsAndWindowsSeparatorsAreUnderstood() {
        val c = card.withData("assets", kotlinx.serialization.json.JsonArray(listOf(
            asset("icon", "embedded://assets\\icon\\main.PNG", "main", "PNG"),
            asset("background", "__asset:assets/bg/one.jpg", "one", "jpg"),
        )))
        val bytes = zip(mapOf(
            "card.json" to CardCodec.encodeV3(c).toString().toByteArray(),
            "assets/icon/main.PNG" to icon,
            "assets/bg/one.jpg" to background,
        ))
        val read = Charx.read(bytes)
        assertArrayEquals(icon, read.avatar)
        assertEquals("png", read.avatarExt)
        assertTrue("assets/bg/one.jpg" in read.assets)
    }

    @Test
    fun selfExtractingPrefixIsSkipped() {
        val bytes = zip(mapOf("card.json" to CardSupport.text("v3-clockwork-orchard.json").toByteArray()), prefix = "MZ-fake-exe".toByteArray())
        assertEquals("Tobias Rennick", CardFiles.read(bytes).card.name)
    }

    @Test
    fun pathsThatEscapeTheArchiveAreIgnored() {
        val c = card.withData("assets", kotlinx.serialization.json.JsonArray(listOf(asset("icon", "embeded://../../etc/passwd", "main", "png"))))
        val bytes = zip(mapOf("card.json" to CardCodec.encodeV3(c).toString().toByteArray(), "../../etc/passwd" to "root".toByteArray()))
        val read = Charx.read(bytes)
        assertNull(read.avatar)
        assertTrue(read.assets.isEmpty())
    }

    @Test
    fun missingCardJsonAndPlainBytesAreRejected() {
        try { Charx.read(zip(mapOf("other.txt" to "x".toByteArray()))); fail() } catch (e: CardFormatException) { assertTrue(e.message!!.contains("card.json")) }
        try { Charx.read("not a zip".toByteArray()); fail() } catch (_: CardFormatException) {}
    }

    @Test
    fun theFileFacadeRecognisesAllThreeContainersByContent() {
        val png = CardFiles.read(CardFiles.toPng(card, icon))
        assertEquals("png", png.container)
        assertNotNull(png.avatar)
        val json = CardFiles.read(CardFiles.toJson(card))
        assertEquals("json", json.container)
        assertNull(json.avatar)
        val charx = CardFiles.read(CardFiles.toCharx(card.withData("assets", null), icon, "png", emptyMap()))
        assertEquals("charx", charx.container)
        assertEquals(card.name, charx.card.name)
        try { CardFiles.read(ByteArray(0)); fail() } catch (_: CardFormatException) {}
    }

    private fun asset(type: String, uri: String, name: String, ext: String) = kotlinx.serialization.json.JsonObject(mapOf(
        "type" to kotlinx.serialization.json.JsonPrimitive(type), "uri" to kotlinx.serialization.json.JsonPrimitive(uri),
        "name" to kotlinx.serialization.json.JsonPrimitive(name), "ext" to kotlinx.serialization.json.JsonPrimitive(ext),
    ))
}
