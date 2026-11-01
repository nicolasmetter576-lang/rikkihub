package me.rerere.rikkihub.ui.pages.debug

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.db.dao.ConversationAssistantCount
import me.rerere.rikkihub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantRecoveryTest {
    private val first = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val second = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private fun scan() = assistantRecoveryScan(listOf(
        ConversationAssistantCount(first.toString(), 2), ConversationAssistantCount(second.toString(), 5),
    ))
    private fun settings(vararg assistants: Assistant) = Settings.dummy().copy(init = false, assistants = assistants.toList())

    @Test fun `scan parses only valid IDs and merges representations without reading messages`() {
        val s = assistantRecoveryScan(listOf(
            ConversationAssistantCount(first.toString(), 2), ConversationAssistantCount(first.toString().uppercase(), 3),
            ConversationAssistantCount("broken", 10), ConversationAssistantCount(second.toString(), 0),
        ))
        assertEquals(mapOf(first to 5L), s.counts)
        assertEquals(2, s.invalidIdGroups)
    }
    @Test fun `missing IDs are ordered by count then stable UUID`() {
        assertEquals(listOf(second, first), missingConversationAssistants(scan(), settings()))
        assertEquals(listOf(first), missingConversationAssistants(scan(), settings(Assistant(id = second))))
        val tied = assistantRecoveryScan(listOf(ConversationAssistantCount(second.toString(), 2), ConversationAssistantCount(first.toString(), 2)))
        assertEquals(listOf(first, second), missingConversationAssistants(tied, settings()))
    }
    @Test fun `invalid IDs never receive replacement identity or overwrite legitimate assistants`() = runBlocking {
        val prior = settings(Assistant(id = first, systemPrompt = "keep", name = "original"))
        var stored = prior
        val count = recoverConversationAssistants(assistantRecoveryScan(listOf(ConversationAssistantCount("invalid", 4))),
            { fn -> stored = fn(stored) }, { "Recovered $it" })
        assertEquals(0, count); assertSame(prior, stored)
    }
    @Test fun `fresh settings are merged after an asynchronous scan and originals remain intact`() = runBlocking {
        val original = Assistant(id = first, name = "edited while scanning", systemPrompt = "secret", enabledSkills = setOf("skill"))
        var stored = settings(original).copy(sponsorAlertDismissedAt = 123)
        val count = recoverConversationAssistants(scan(), { fn -> stored = fn(stored) }, { "Recovered $it" })
        assertEquals(1, count); assertSame(original, stored.assistants[0])
        assertEquals(123, stored.sponsorAlertDismissedAt); assertEquals(second, stored.assistants[1].id)
        assertEquals("Recovered 1", stored.assistants[1].name)
        assertEquals("", stored.assistants[1].systemPrompt)
        assertFalse(stored.assistants[1].enableMemory); assertNull(stored.assistants[1].workspaceId)
    }
    @Test fun `two concurrent rescues under the real update contract do not create duplicate IDs`() = runBlocking {
        val mutex = Mutex(); var stored = settings(); val update: suspend ((Settings) -> Settings) -> Unit = { fn ->
            mutex.withLock { stored = fn(stored) }
        }
        val jobs = listOf(async { recoverConversationAssistants(scan(), update) { "Recovered $it" } },
            async { recoverConversationAssistants(scan(), update) { "Recovered $it" } })
        assertEquals(listOf(2, 0), jobs.map { it.await() })
        assertEquals(listOf(second, first), stored.assistants.map { it.id })
    }
    @Test fun `recovery awaits persistence before claiming success`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var stored = settings()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            recoverConversationAssistants(scan(), { fn -> val next = fn(stored); entered.complete(Unit); release.await(); stored = next }) { "Recovered $it" }
        }
        entered.await(); assertFalse(job.isCompleted); assertTrue(stored.assistants.isEmpty())
        release.complete(Unit); assertEquals(2, job.await()); assertEquals(2, stored.assistants.size)
    }
    @Test fun `persistence failure does not claim recovered assistants`() = runBlocking {
        val failure = IOException("disk failed")
        val actual = runCatching { recoverConversationAssistants(scan(), { fn -> fn(settings()); throw failure }) { "Recovered $it" } }.exceptionOrNull()
        assertSame(failure, actual)
    }
    @Test fun `unloaded settings fail instead of saving dummy defaults`() = runBlocking {
        var changed = false
        val failure = runCatching { recoverConversationAssistants(scan(), { fn -> fn(Settings.dummy()); changed = true }) { "Recovered $it" } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException); assertFalse(changed)
    }
    @Test fun `cancellation propagates without claiming success`() = runBlocking {
        val failure = CancellationException("left page")
        assertSame(failure, runCatching { recoverConversationAssistants(scan(), { throw failure }) { "Recovered $it" } }.exceptionOrNull())
    }
    @Test fun `empty database leaves settings exactly unchanged`() = runBlocking {
        val prior = settings(Assistant(id = first)); var stored = prior
        assertEquals(0, recoverConversationAssistants(assistantRecoveryScan(emptyList()), { fn -> stored = fn(stored) }) { "Recovered $it" })
        assertSame(prior, stored)
    }
}
