package me.rerere.rikkihub.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.entity.CharacterEntity
import me.rerere.rikkihub.data.db.entity.ConversationEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterDaoInstrumentedTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.characterDao()

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun entity(id: String, name: String, search: String = name.lowercase(), tags: String = "[]") = CharacterEntity(
        id = id, name = name, cardJson = "{\"name\":\"$name\"}", tags = tags, searchText = search, createdAt = 1, updatedAt = 2,
    )

    @Test
    fun summariesAreSortedByNameIgnoringCaseAndSkipTheCard() = runBlocking {
        dao.insert(entity("b", "bravo"))
        dao.insert(entity("a", "Alpha"))
        dao.insert(entity("c", "charlie", tags = "[\"x\"]"))
        assertEquals(listOf("Alpha", "bravo", "charlie"), dao.summaries().map { it.name })
        assertEquals("[\"x\"]", dao.summaries().last().tags)
    }

    @Test
    fun searchMatchesLiterallyAndEscapesWildcards() = runBlocking {
        dao.insert(entity("1", "Alpha", search = "alpha\nfixture author\ngreen tea 100% sure"))
        dao.insert(entity("2", "Beta", search = "beta\nsnake_case"))
        assertEquals(listOf("1"), dao.searchIds(escapeLike("tea")))
        assertEquals(listOf("1"), dao.searchIds(escapeLike("100%")))
        assertEquals(emptyList<String>(), dao.searchIds(escapeLike("%")).filter { it == "2" })
        assertEquals(listOf("2"), dao.searchIds(escapeLike("snake_")))
        assertEquals(emptyList<String>(), dao.searchIds(escapeLike("snakeXcase")))
    }

    @Test
    fun updateIsACasOnTheRevision() = runBlocking {
        dao.insert(entity("1", "Alpha"))
        fun write(expected: Long, name: String) = runBlocking {
            dao.updateCas("1", expected, name, "", "{}", "", false, "[]", name.lowercase(), 9, "{}")
        }
        assertEquals(1, write(0, "Alpha 2"))
        assertEquals(0, write(0, "stale"))
        val row = dao.get("1")!!
        assertEquals("Alpha 2", row.name)
        assertEquals(1L, row.revision)
        assertEquals(9L, row.updatedAt)
    }

    @Test
    fun conversationCountIgnoresDeletedOnesAndTheCharacterRowCanBeRemoved() = runBlocking {
        dao.insert(entity("1", "Alpha"))
        val dao2 = db.conversationDao()
        fun conversation(id: String, deletedAt: Long?) = ConversationEntity(
            id = id, assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e", title = id, nodes = "[]", createAt = 1, updateAt = 1,
            chatSuggestions = "[]", isPinned = false, characterId = "1", deletedAt = deletedAt,
        )
        dao2.upsert(conversation("live", null))
        dao2.upsert(conversation("gone", 5))
        assertEquals(1, dao.conversationCount("1"))
        assertEquals(1, dao.delete("1"))
        assertNull(dao.get("1"))
        // the conversation keeps its (now dangling) id; readers treat it as no character
        assertEquals("1", dao2.getConversationById("live")?.characterId)
    }
}
