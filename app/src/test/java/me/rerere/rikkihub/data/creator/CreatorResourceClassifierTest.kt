package me.rerere.rikkihub.data.creator

import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.definition.CreatorResourceClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * F6 隐藏/拒绝钩子的负向断言：保留 assistantId、creator_* wire 名、
 * creator:// scheme 命中；普通 id/名不命中。
 */
class CreatorResourceClassifierTest {

    @Test
    fun `reserved assistant id detected`() {
        assertTrue(
            CreatorResourceClassifier.isCreatorResource(
                "assistant", CreatorAssistantDefinition.ID.toString(),
            )
        )
        assertTrue(
            CreatorResourceClassifier.isReservedAssistantId(CreatorAssistantDefinition.ID.toString())
        )
    }

    @Test
    fun `ordinary assistant id not flagged`() {
        val id = Uuid.random().toString()
        assertFalse(CreatorResourceClassifier.isCreatorResource("assistant", id))
        assertFalse(CreatorResourceClassifier.isReservedAssistantId(id))
    }

    @Test
    fun `creator wire names flagged`() {
        assertTrue(CreatorResourceClassifier.isCreatorResource("tool", "creator_list_objects"))
        assertTrue(CreatorResourceClassifier.isCreatorWireName("creator_propose_undo"))
    }

    @Test
    fun `creator uri scheme flagged`() {
        assertTrue(CreatorResourceClassifier.isCreatorResource(null, "creator://vault/x"))
    }

    @Test
    fun `plain names and malformed ids not flagged`() {
        assertFalse(CreatorResourceClassifier.isCreatorResource("assistant", "not-a-uuid"))
        assertFalse(CreatorResourceClassifier.isCreatorResource("tool", "workspace_shell"))
        assertFalse(CreatorResourceClassifier.isCreatorResource(null, null))
    }
}
