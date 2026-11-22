package me.rerere.rikkihub.data.character

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.repository.FakeCharacterDAO
import me.rerere.tavern.card.CardCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.uuid.Uuid

class CharacterListingTest {
    private fun summary(name: String, fav: Boolean = false) = CharacterSummary(
        id = Uuid.random(), name = name, avatarPath = "", fav = fav, tags = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, revision = 0,
    )

    @Test
    fun `favourites come first then names without regard to case`() {
        val list = listOf(summary("beta"), summary("Zed", fav = true), summary("Alpha"), summary("alpha two"), summary("Mia", fav = true))
        assertEquals(listOf("Mia", "Zed", "Alpha", "alpha two", "beta"), CharacterListing.ordered(list).map { it.name })
    }

    @Test
    fun `matching keeps only the ids named and an empty set matches nothing`() {
        val a = summary("A")
        val b = summary("B", fav = true)
        val c = summary("C")
        assertEquals(listOf("B", "C"), CharacterListing.ordered(listOf(a, b, c), setOf(b.id, c.id)).map { it.name })
        assertTrue(CharacterListing.ordered(listOf(a, b, c), emptySet()).isEmpty())
        assertEquals(3, CharacterListing.ordered(listOf(a, b, c), null).size)
    }

    @Test
    fun `the strip holds favourites only and stops at the limit`() {
        val many = (1..300).map { summary("c%03d".format(it), fav = it % 5 == 0) }
        val strip = CharacterListing.favourites(many)
        assertEquals(CharacterListing.QUICK_LIMIT, strip.size)
        assertTrue(strip.all { it.fav })
        assertEquals(listOf("c005", "c010", "c015"), strip.take(3).map { it.name })
        assertEquals(2, CharacterListing.favourites(many, limit = 2).size)
    }

    @Test
    fun `no favourite means an empty strip`() {
        assertTrue(CharacterListing.favourites(listOf(summary("A"), summary("B"))).isEmpty())
        assertTrue(CharacterListing.favourites(emptyList()).isEmpty())
    }

    @Test
    fun `rows keep their order when only a star changes`() {
        val a = summary("A")
        val b = summary("B")
        val c = summary("C")
        val order = CharacterListing.ordered(listOf(a, b, c)).map { it.id }
        // C 被星标：按新顺序它会排到最前，但按固定下来的顺序它仍在最后，星标是新的
        val latest = CharacterListing.ordered(listOf(a, b, c.copy(fav = true)))
        assertEquals(listOf("C", "A", "B"), latest.map { it.name })
        val shown = CharacterListing.inOrder(order, latest)
        assertEquals(listOf("A", "B", "C"), shown.map { it.name })
        assertTrue(shown.last().fav)
    }

    @Test
    fun `a character that is gone is skipped and a new one goes last`() {
        val a = summary("A")
        val b = summary("B")
        val order = listOf(a.id, b.id)
        val fresh = summary("Aaa")
        assertEquals(listOf("B", "Aaa"), CharacterListing.inOrder(order, listOf(b, fresh)).map { it.name })
        assertTrue(CharacterListing.inOrder(order, emptyList()).isEmpty())
    }

    // ---- 仓库上的列表流 ----

    private val root = Files.createTempDirectory("characters").toFile()
    private val repo = CharacterRepository(FakeCharacterDAO(), root)

    @After
    fun cleanup() { root.deleteRecursively() }

    private fun card(name: String, tags: String = "[]") =
        CardCodec.parse("""{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"$name","description":"","tags":$tags}}""").card

    @Test
    fun `the listing follows the search word and the library`() = runBlocking {
        val query = MutableStateFlow("")
        repo.create(card("Ines", tags = "[\"cartography\"]"))
        repo.create(card("Bruno"))
        assertEquals(listOf("Bruno", "Ines"), repo.observeListing(query).first().map { it.name })

        query.value = "cartography"
        assertEquals(listOf("Ines"), repo.observeListing(query).first().map { it.name })

        query.value = "nobody by that name"
        assertTrue(repo.observeListing(query).first().isEmpty())

        query.value = "   "
        assertEquals(2, repo.observeListing(query).first().size)
    }

    @Test
    fun `starring a character moves it to the front and into the strip`() = runBlocking {
        val query = MutableStateFlow("")
        repo.create(card("Anna"))
        val zed = repo.create(card("Zed"))
        assertTrue(CharacterListing.favourites(repo.observeListing(query).first()).isEmpty())

        repo.setFavorite(zed.id, true)
        val listed = repo.observeListing(query).first()
        assertEquals(listOf("Zed", "Anna"), listed.map { it.name })
        assertEquals(listOf("Zed"), CharacterListing.favourites(listed).map { it.name })

        repo.setFavorite(zed.id, false)
        assertFalse(repo.summaries().first { it.id == zed.id }.fav)
        assertEquals(listOf("Anna", "Zed"), repo.observeListing(query).first().map { it.name })
    }
}
