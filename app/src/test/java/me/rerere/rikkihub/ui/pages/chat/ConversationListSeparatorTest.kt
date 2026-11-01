package me.rerere.rikkihub.ui.pages.chat

import me.rerere.rikkihub.data.datastore.ConversationSortOrder
import me.rerere.rikkihub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.uuid.Uuid

class ConversationListSeparatorTest {
    private val zone = ZoneOffset.UTC
    private val label: (LocalDate) -> String = { it.toString() }

    private fun conversation(created: String, updated: String, pinned: Boolean = false) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = emptyList(),
        createAt = Instant.parse("${created}T10:00:00Z"),
        updateAt = Instant.parse("${updated}T10:00:00Z"),
        isPinned = pinned,
    )

    private fun separator(before: Conversation?, after: Conversation?, order: ConversationSortOrder) =
        conversationListSeparator(before, after, order, zone, label)

    @Test
    fun `pinned section gets one header at the top`() {
        val pinned = conversation("2026-10-01", "2026-10-02", pinned = true)
        assertEquals(ConversationListItem.PinnedHeader, separator(null, pinned, ConversationSortOrder.UPDATE_TIME))
        assertNull(separator(pinned, pinned.copy(id = Uuid.random()), ConversationSortOrder.UPDATE_TIME))
    }

    @Test
    fun `date header follows the sort field`() {
        val first = conversation(created = "2026-10-01", updated = "2026-10-05")
        val second = conversation(created = "2026-10-01", updated = "2026-10-04")

        // 按更新时间：两天不同，要分组
        val byUpdate = separator(first, second, ConversationSortOrder.UPDATE_TIME) as ConversationListItem.DateHeader
        assertEquals(LocalDate.parse("2026-10-04"), byUpdate.date)
        assertEquals(ConversationSortOrder.UPDATE_TIME, byUpdate.sortOrder)

        // 按创建时间：同一天创建，不分组
        assertNull(separator(first, second, ConversationSortOrder.CREATE_TIME))

        val top = separator(null, first, ConversationSortOrder.CREATE_TIME) as ConversationListItem.DateHeader
        assertEquals(LocalDate.parse("2026-10-01"), top.date)
        assertEquals(ConversationSortOrder.CREATE_TIME, top.sortOrder)
    }

    @Test
    fun `first unpinned item after pinned ones starts a date group`() {
        val pinned = conversation("2026-10-03", "2026-10-03", pinned = true)
        val normal = conversation("2026-10-03", "2026-10-03")
        val header = separator(pinned, normal, ConversationSortOrder.UPDATE_TIME) as ConversationListItem.DateHeader
        assertEquals(LocalDate.parse("2026-10-03"), header.date)
        assertNull(separator(normal, null, ConversationSortOrder.UPDATE_TIME))
    }
}
