package me.rerere.rikkihub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatUtilTest {
    @Test
    fun `allows agc camera configs as text documents`() {
        assertTrue(isAllowedFileType("camera-config.agc", "application/octet-stream"))
        assertTrue(isAllowedFileType("CAMERA-CONFIG.AGC", "application/octet-stream"))
    }

    @Test
    fun `still rejects unknown binary file types`() {
        assertFalse(isAllowedFileType("archive.unknown", "application/octet-stream"))
    }

    @Test
    fun `a chat route with a character carries the character id as a string`() {
        val chat = Uuid.random()
        val character = Uuid.random()
        val screen = chatScreen(chatId = chat, characterId = character)
        assertEquals(chat.toString(), screen.id)
        assertEquals(character.toString(), screen.characterId)
        assertNull(screen.text)
        assertNull(screen.nodeId)
        assertEquals(emptyList<String>(), screen.files)
    }

    @Test
    fun `a plain chat route has no character`() {
        assertNull(chatScreen(chatId = Uuid.random()).characterId)
    }
}
