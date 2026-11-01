package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.entity.FavoriteEntity
import me.rerere.rikkihub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkihub.data.model.FavoriteMeta
import me.rerere.rikkihub.data.model.NodeFavoriteRef
import me.rerere.rikkihub.data.repository.LightConversationEntity
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.dto.FavoriteRecordDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FavoriteRoutesTest {
    private val convA = Uuid.random()
    private val convB = Uuid.random()

    private fun favorite(
        conversationId: Uuid,
        nodeId: Uuid = Uuid.random(),
        createdAt: Long,
        title: String? = "Saved title",
        type: String = "node",
    ): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString())
        return FavoriteEntity(
            id = refKey,
            type = type,
            refKey = refKey,
            refJson = JsonInstant.encodeToString(NodeFavoriteRef.serializer(), NodeFavoriteRef(conversationId, nodeId)),
            snapshotJson = "",
            metaJson = JsonInstant.encodeToString(FavoriteMeta.serializer(), FavoriteMeta(title = title, previewText = "preview $createdAt")),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private fun light(id: Uuid, title: String) = LightConversationEntity(
        id = id.toString(), assistantId = "assistant-1", title = title,
        isPinned = false, createAt = 0, updateAt = 0,
    )

    private fun FavoriteEntity.nodeId() = NodeFavoriteAdapter.decodeRef(this)!!.nodeId.toString()

    @Test
    fun `list reports ok, missing node and deleted conversation without loading conversations`() = runBlocking {
        val ok = favorite(convA, createdAt = 3)
        val gone = favorite(convA, createdAt = 2)
        val deleted = favorite(convB, createdAt = 1, title = "Old title")
        val queried = mutableListOf<Pair<String, Set<String>>>()

        val result = assembleFavoriteList(
            entities = listOf(deleted, ok, gone),
            conversations = { ids ->
                assertEquals(setOf(convA.toString(), convB.toString()), ids.toSet())
                mapOf(convA.toString() to light(convA, "Current title"))
            },
            existingNodes = { conversationId, nodeIds ->
                queried += conversationId to nodeIds.toSet()
                setOf(ok.nodeId())
            },
        )

        // Newest first; node existence is only asked for live conversations, in one batch each.
        assertEquals(listOf(ok.id, gone.id, deleted.id), result.items.map { it.id })
        assertEquals(listOf(convA.toString() to setOf(ok.nodeId(), gone.nodeId())), queried)
        assertEquals(listOf("ok", "node_missing", "conversation_deleted"), result.items.map { it.status })
        assertEquals("Current title", result.items[0].title)
        assertEquals("Old title", result.items[2].title)
        assertEquals("assistant-1", result.items[0].assistantId)
        assertNull(result.items[2].assistantId)
        assertEquals("preview 3", result.items[0].preview)
        assertFalse(result.truncated)
    }

    @Test
    fun `legacy message favorites and undecodable refs are skipped`() = runBlocking {
        val legacy = favorite(convA, createdAt = 2, type = "message")
        val broken = favorite(convA, createdAt = 1).copy(refJson = "{not json")
        val result = assembleFavoriteList(listOf(legacy, broken), { emptyMap() }, { _, _ -> emptySet() })
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `list is capped and keeps the newest`() = runBlocking {
        val entities = (0 until FAVORITE_LIST_LIMIT + 5).map { favorite(convA, createdAt = it.toLong()) }
        val result = assembleFavoriteList(entities, { emptyMap() }, { _, _ -> emptySet() })
        assertEquals(FAVORITE_LIST_LIMIT, result.items.size)
        assertEquals((FAVORITE_LIST_LIMIT + 4).toLong(), result.items.first().createdAt)
        assertTrue(result.truncated)
    }

    private fun FavoriteEntity.record() = FavoriteRecordDto(id, type, refKey, refJson, snapshotJson, metaJson, createdAt, updatedAt)

    @Test
    fun `restore accepts the exact record returned by delete`() {
        val original = favorite(convA, createdAt = 42)
        assertEquals(original, validateFavoriteRestore(original.record()))
    }

    @Test
    fun `restore rejects foreign, mismatched or oversized records`() {
        val base = favorite(convA, createdAt = 1)
        val other = favorite(convB, createdAt = 1)
        val bad = listOf(
            base.record().copy(type = "message"),
            base.record().copy(refKey = other.refKey),
            base.record().copy(refJson = "{}"),
            base.record().copy(id = ""),
            base.record().copy(metaJson = "not json"),
            base.record().copy(metaJson = "x".repeat(5000)),
            base.record().copy(snapshotJson = "x".repeat(70_000)),
            base.record().copy(createdAt = -1),
        )
        bad.forEach { record ->
            assertThrows(BadRequestException::class.java) { validateFavoriteRestore(record) }
        }
    }
}
