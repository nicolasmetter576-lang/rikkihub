package me.rerere.rikkihub.data.character

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.repository.FakeCharacterDAO
import me.rerere.tavern.card.CardCodec
import me.rerere.tavern.card.CardFiles
import me.rerere.tavern.card.PngCards
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.uuid.Uuid

class CharacterServiceTest {
    private val root = Files.createTempDirectory("character-service").toFile()
    private val repository = CharacterRepository(FakeCharacterDAO(), root)
    private var settings = Settings()
    private val service = CharacterService(repository, { settings }, { fn -> settings = fn(settings) })

    @After
    fun cleanup() { root.deleteRecursively() }

    private fun fixture(name: String) = CharacterServiceTest::class.java.getResource("/cards/$name")!!.readBytes()

    @Test
    fun importingTheV2CardMakesTheCharacterAndItsBook() = runBlocking {
        val before = settings.lorebooks.size
        val result = service.import(fixture("v2-harbor-cartographer.json"), "ines.json")
        val record = result.record
        assertEquals("Ines Okafor", record.name)
        assertEquals("ines.json", record.sourceName)
        assertEquals("v2", result.report.cardFormat)
        assertEquals("json", result.report.container)
        assertEquals(3, result.report.greetings)
        assertEquals(1, result.report.regexScripts)
        assertTrue(result.report.hasDepthPrompt)
        val created = result.report.book as BookOutcome.Created
        assertEquals("Coastline Notes", created.name)
        assertEquals(3, created.entries)
        assertEquals(before + 1, settings.lorebooks.size)
        val book = settings.lorebooks.last()
        assertEquals(created.bookId, book.id)
        assertEquals(listOf(0, 2, 3), book.entries.map { it.uid })
        assertEquals(created.bookId, record.local.embeddedBookId)
        // the favourite mark of someone else's file is dropped
        assertFalse(record.card.fav)
    }

    @Test
    fun importingTheSameCardAgainReusesTheIdenticalBook() = runBlocking {
        val first = service.import(fixture("v2-harbor-cartographer.json"), "a.json")
        val second = service.import(fixture("v2-harbor-cartographer.json"), "b.json")
        assertTrue(second.report.book is BookOutcome.Reused)
        assertEquals(first.record.local.embeddedBookId, second.record.local.embeddedBookId)
        assertEquals(1, settings.lorebooks.count { it.name == "Coastline Notes" })
        assertEquals(2, repository.count())
    }

    @Test
    fun aDifferentBookWithTheSameNameGetsANumber() = runBlocking {
        service.import(fixture("v2-harbor-cartographer.json"), "a.json")
        // same book name, different content
        val altered = String(fixture("v2-harbor-cartographer.json")).replace("floods twice a year", "floods three times a year")
        val second = service.import(altered.toByteArray(), "b.json")
        assertEquals("Coastline Notes (2)", (second.report.book as BookOutcome.Created).name)
        val third = service.import(altered.replace("three times", "once").toByteArray(), "c.json")
        assertEquals("Coastline Notes (3)", (third.report.book as BookOutcome.Created).name)
        // and an importing the numbered one again is a reuse of that exact book
        val again = service.import(altered.toByteArray(), "d.json")
        assertTrue(again.report.book is BookOutcome.Reused)
        assertEquals("Coastline Notes (2)", again.report.book!!.name)
    }

    @Test
    fun aCardWithoutABookCreatesNone() = runBlocking {
        val before = settings.lorebooks.size
        val r = service.import(fixture("v1-lantern-keeper.json"), "marin.json")
        assertNull(r.report.book)
        assertNull(r.record.local.embeddedBookId)
        assertEquals(before, settings.lorebooks.size)
        assertEquals("v1", r.report.cardFormat)
    }

    @Test
    fun aPngCardKeepsItsImageAndAnythingElseIsRejectedCleanly() = runBlocking {
        val png = CardFiles.toPng(CardCodec.parse(String(fixture("v3-clockwork-orchard.json"))).card, PngCards.placeholder("x", 8, 8))
        val r = service.import(png, "toby.png")
        assertEquals("png", r.report.container)
        assertNotNull(repository.avatarFile(r.record))
        assertTrue(PngCards.isPng(repository.avatarFile(r.record)!!.readBytes()))
        try { service.import("<html>".toByteArray(), "x.html"); throw AssertionError("should fail") } catch (_: me.rerere.tavern.card.CardFormatException) {}
        assertEquals(1, repository.count())
    }

    @Test
    fun exportWritesTheCurrentBookBackIntoTheCard() = runBlocking {
        val r = service.import(fixture("v2-harbor-cartographer.json"), "ines.json")
        val bookId = r.record.local.embeddedBookId!!
        // the user edits the linked book: drop the first entry and rename the book
        settings = settings.copy(lorebooks = settings.lorebooks.map { if (it.id == bookId) it.copy(name = "Renamed book", entries = it.entries.drop(1)) else it })
        val exported = service.export(r.record.id, CharacterExportFormat.JSON)
        assertEquals("Ines Okafor.json", exported.fileName)
        val card = CardCodec.parse(String(exported.bytes)).card
        val book = card.characterBook!!
        assertEquals(JsonPrimitive("Renamed book"), book["name"])
        assertEquals(2, (book["entries"] as JsonArray).size)
        assertEquals("Renamed book", card.world)
        // book-level fields of the original are kept
        assertEquals(JsonPrimitive(6), book["scan_depth"])
        assertFalse(card.fav)
    }

    @Test
    fun exportedPngAndCharxReadBackAsTheSameCharacter() = runBlocking {
        val r = service.import(fixture("v3-clockwork-orchard.json"), "toby.json")
        val png = service.export(r.record.id, CharacterExportFormat.PNG)
        assertEquals("Tobias Rennick.png", png.fileName)
        assertEquals("image/png", png.mimeType)
        assertTrue(PngCards.isPng(png.bytes))
        assertEquals("Tobias Rennick", CardFiles.read(png.bytes).card.name)
        val charx = service.export(r.record.id, CharacterExportFormat.CHARX)
        assertEquals("Tobias Rennick.charx", charx.fileName)
        val back = CardFiles.read(charx.bytes)
        assertEquals("charx", back.container)
        assertEquals("Tobias Rennick", back.card.name)
        assertEquals(listOf("Toby"), listOf(back.card.nickname))
    }

    @Test
    fun theFileNameOfAnExportHasNoPathCharacters() = runBlocking {
        val r = repository.create(me.rerere.tavern.card.CharacterCard.blank("../Bad:Name*?"))
        val e = service.export(r.id, CharacterExportFormat.JSON)
        assertFalse(e.fileName.contains('/') || e.fileName.contains(':') || e.fileName.contains('*') || e.fileName.contains('?'))
        assertTrue(e.fileName.endsWith(".json"))
    }

    @Test
    fun theActiveBooksListsTheEmbeddedBookFirstAndSkipsDisabledOnes() = runBlocking {
        val r = service.import(fixture("v2-harbor-cartographer.json"), "ines.json")
        val extra = Lorebook(name = "Extra", entries = emptyList())
        val off = Lorebook(name = "Off", enabled = false)
        settings = settings.copy(lorebooks = settings.lorebooks + extra + off)
        val linked = repository.update(r.record.id) { it.copy(local = it.local.copy(extraBookIds = listOf(off.id, extra.id, Uuid.random()))) }
        assertEquals(listOf("Coastline Notes", "Extra"), service.activeBooks(linked).map { it.name })
    }

    @Test
    fun deletingACharacterCanTakeItsEmbeddedBookAlongOnlyWhenNoOtherCharacterUsesIt() = runBlocking {
        val a = service.import(fixture("v2-harbor-cartographer.json"), "a.json")
        val b = service.import(fixture("v2-harbor-cartographer.json"), "b.json") // reuses the same book
        val bookId = a.record.local.embeddedBookId!!
        assertTrue(service.delete(a.record.id, deleteEmbeddedBook = true))
        assertTrue(settings.lorebooks.any { it.id == bookId }) // b still uses it
        assertTrue(service.delete(b.record.id, deleteEmbeddedBook = true))
        assertFalse(settings.lorebooks.any { it.id == bookId })
        assertEquals(0, repository.count())
    }

    @Test
    fun deletingKeepsTheBookByDefault() = runBlocking {
        val a = service.import(fixture("v2-harbor-cartographer.json"), "a.json")
        service.delete(a.record.id)
        assertTrue(settings.lorebooks.any { it.id == a.record.local.embeddedBookId })
    }

    @Test
    fun regexScriptsRunOnlyAfterTheUserAllowsThem() = runBlocking {
        val a = service.import(fixture("v2-harbor-cartographer.json"), "a.json")
        val key = "character:${a.record.id}"
        assertFalse(key in settings.tavern.regexAllowed)
        service.setRegexAllowed(a.record.id, true)
        assertTrue(key in settings.tavern.regexAllowed)
        service.setRegexAllowed(a.record.id, true) // idempotent
        assertEquals(1, settings.tavern.regexAllowed.count { it == key })
        service.setRegexAllowed(a.record.id, false)
        assertFalse(key in settings.tavern.regexAllowed)
    }

    @Test
    fun deletingACharacterDropsItsRegexAllowance() = runBlocking {
        val a = service.import(fixture("v2-harbor-cartographer.json"), "a.json")
        val b = service.import(fixture("v2-harbor-cartographer.json"), "b.json")
        service.setRegexAllowed(a.record.id, true)
        service.setRegexAllowed(b.record.id, true)
        service.delete(a.record.id)
        assertEquals(setOf("character:${b.record.id}"), settings.tavern.regexAllowed)
    }

    @Test
    fun aBlankCharacterCanBeCreated() = runBlocking {
        val r = service.createBlank("  Nova  ")
        assertEquals("Nova", r.name)
        assertEquals("New character", service.createBlank("   ").name)
        assertTrue(JsonObject(emptyMap()).isEmpty())
    }
}
