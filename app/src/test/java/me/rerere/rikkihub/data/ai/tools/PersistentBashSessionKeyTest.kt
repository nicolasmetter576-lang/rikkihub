package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistentBashSessionKeyTest {
    @Test
    fun `no owner keeps the legacy per workspace key`() {
        assertEquals("ws-1", persistentBashSessionKey("ws-1", null))
    }

    @Test
    fun `owners get distinct keys per workspace`() {
        val a = persistentBashSessionKey("ws-1", "subagent:a")
        val b = persistentBashSessionKey("ws-1", "subagent:b")
        assertNotEquals(a, b)
        assertNotEquals("ws-1", a)
        assertNotEquals(persistentBashSessionKey("ws-2", "subagent:a"), a)
    }

    @Test
    fun `owner travels in the coroutine context`() = runBlocking {
        assertNull(currentCoroutineContext()[PersistentBashOwner])
        val seen = withContext(PersistentBashOwner("subagent:x")) {
            currentCoroutineContext()[PersistentBashOwner]?.owner
        }
        assertEquals("subagent:x", seen)
    }
}
