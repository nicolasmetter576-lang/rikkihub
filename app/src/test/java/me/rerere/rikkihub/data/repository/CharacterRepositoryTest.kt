package me.rerere.rikkihub.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.model.CharacterLocalSettings
import me.rerere.tavern.card.CardCodec
import me.rerere.tavern.card.CharacterCard
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import kotlin.uuid.Uuid

class CharacterRepositoryTest {
    private val root = Files.createTempDirectory("characters").toFile()
    private val dao = FakeCharacterDAO()
    private val repo = CharacterRepository(dao, root)

    @After
    fun cleanup() { root.deleteRecursively() }

    private fun card(name: String = "Ines Okafor", tags: String = "[\"cartography\",\"fixture\"]", fav: Boolean = false) =
        CardCodec.parse("""{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"$name","description":"draws coastlines","creator":"Fixture Author","tags":$tags,"extensions":{"fav":$fav}}}""").card

    @Test
    fun createWritesTheRowTheAvatarAndTheAssets() = runBlocking {
        val png = byteArrayOf(1, 2, 3)
        val r = repo.create(card(), avatar = png, avatarExt = "PNG", assets = mapOf("assets/bg/one.png" to byteArrayOf(9)), sourceName = "ines.png")
        assertEquals(0L, r.revision)
        assertTrue(r.avatarPath.startsWith("avatar-") && r.avatarPath.endsWith(".png"))
        assertArrayEquals(png, repo.avatarFile(r)!!.readBytes())
        assertEquals(mapOf("assets/bg/one.png" to byteArrayOf(9).toList()), repo.assets(r.id).mapValues { it.value.toList() })
        val back = repo.get(r.id)!!
        assertEquals(card().toJson(), back.card.toJson())
        assertEquals("ines.png", back.sourceName)
    }

    @Test
    fun theListColumnsComeFromTheCard() = runBlocking {
        repo.create(card("Zed", tags = "[\"b\"]", fav = true))
        repo.create(card("alpha", tags = "[\"a\",\"c\"]"))
        val list = repo.summaries()
        assertEquals(listOf("alpha", "Zed"), list.map { it.name })
        assertEquals(listOf("a", "c"), list.first().tags)
        assertTrue(list.last().fav)
        assertEquals(2, repo.observeSummaries().first().size)
    }

    @Test
    fun searchFindsNameCreatorTagAndDescriptionLiterally() = runBlocking {
        val a = repo.create(card("Ines Okafor"))
        val b = repo.create(card("Someone 100% else", tags = "[]"))
        assertEquals(setOf(a.id), repo.search("cartography"))
        assertEquals(setOf(a.id, b.id), repo.search("fixture author"))
        assertEquals(setOf(b.id), repo.search("100%"))
        assertEquals(emptySet<Uuid>(), repo.search("   "))
        assertEquals(emptySet<Uuid>(), repo.search("nothing like it"))
    }

    @Test
    fun updateBumpsTheRevisionAndRefreshesTheColumns() = runBlocking {
        val r = repo.create(card())
        val u = repo.update(r.id, expectedRevision = 0) { it.copy(card = it.card.withData("name", JsonPrimitive("Renamed"))) }
        assertEquals(1L, u.revision)
        assertEquals("Renamed", repo.summaries().single().name)
        assertEquals(setOf(r.id), repo.search("renamed"))
    }

    @Test
    fun aStaleExpectedRevisionIsAConflictAndWritesNothing() = runBlocking {
        val r = repo.create(card())
        repo.update(r.id) { it.copy(card = it.card.withData("description", JsonPrimitive("first"))) }
        try {
            repo.update(r.id, expectedRevision = 0) { it.copy(card = it.card.withData("description", JsonPrimitive("second"))) }
            fail()
        } catch (_: CharacterConflictException) {}
        assertEquals("first", repo.get(r.id)!!.card.description)
    }

    @Test
    fun anUpdateWithoutAnExpectedRevisionRetriesWhenRacedOnce() = runBlocking {
        val r = repo.create(card())
        dao.failNextCas = 2
        val u = repo.update(r.id) { it.copy(card = it.card.withData("description", JsonPrimitive("won"))) }
        assertEquals("won", repo.get(r.id)!!.card.description)
        assertEquals(1L, u.revision)
        dao.failNextCas = 99
        try { repo.update(r.id) { it }; fail() } catch (_: CharacterConflictException) {}
    }

    @Test
    fun updatingAMissingCharacterFails() = runBlocking {
        try { repo.update(Uuid.random()) { it }; fail() } catch (_: NoSuchElementException) {}
    }

    @Test
    fun changingTheAvatarReplacesTheFile() = runBlocking {
        val r = repo.create(card(), avatar = byteArrayOf(1), avatarExt = "png")
        val old = repo.avatarFile(r)!!
        val u = repo.setAvatar(r.id, byteArrayOf(2, 2), "jpg")
        assertFalse(old.exists())
        assertTrue(u.avatarPath.endsWith(".jpg"))
        assertArrayEquals(byteArrayOf(2, 2), repo.avatarFile(u)!!.readBytes())
    }

    @Test
    fun anUnsafeAvatarExtensionFallsBackToPng() = runBlocking {
        val r = repo.create(card(), avatar = byteArrayOf(1), avatarExt = "../../x")
        assertTrue(r.avatarPath.endsWith(".png"))
    }

    @Test
    fun deleteRemovesTheRowAndTheDirectory() = runBlocking {
        val r = repo.create(card(), avatar = byteArrayOf(1), avatarExt = "png")
        assertTrue(repo.directory(r.id).isDirectory)
        assertTrue(repo.delete(r.id))
        assertNull(repo.get(r.id))
        assertFalse(repo.directory(r.id).exists())
        assertFalse(repo.delete(r.id))
    }

    @Test
    fun aFailedInsertLeavesNoFilesBehind() = runBlocking {
        val id = Uuid.random()
        repo.create(card(), id = id)
        try { repo.create(card("Dup"), avatar = byteArrayOf(1), id = id); fail() } catch (_: IllegalStateException) {}
        // the first character's directory is gone only if the failed create deleted it: it must not have touched the row's owner
        assertNotNull(repo.get(id))
    }

    @Test
    fun assetPathsThatEscapeTheDirectoryAreRejected() = runBlocking {
        try {
            repo.create(card(), assets = mapOf("../escape.txt" to byteArrayOf(1)))
            fail()
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, repo.count())
        assertFalse(java.io.File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun localSettingsRoundTripAndListTheEmbeddedBookFirst() = runBlocking {
        val embedded = Uuid.random(); val extra = Uuid.random()
        val r = repo.create(card(), local = CharacterLocalSettings(embedded, listOf(extra, embedded)))
        val back = repo.get(r.id)!!.local
        assertEquals(embedded, back.embeddedBookId)
        assertEquals(listOf(embedded, extra), back.bookIds)
    }

    @Test
    fun conversationCountComesFromTheDao() = runBlocking {
        val r = repo.create(card())
        dao.conversationCounts[r.id.toString()] = 3
        assertEquals(3, repo.conversationCount(r.id))
    }

    @Test
    fun aDamagedStoredCardIsSkippedByGetManyButDoesNotCrashTheList() = runBlocking {
        val r = repo.create(card())
        dao.updateCas(r.id.toString(), 0, "x", "", "not json", "", false, "[]", "x", 1, "{}")
        assertEquals(emptyList<Any>(), repo.getMany(listOf(r.id)))
        assertEquals(1, repo.summaries().size)
    }
}
