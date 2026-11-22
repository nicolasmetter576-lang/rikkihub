package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.UsageUpdate
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UsageRecorderTest {
    private val model = testChatModel()
    private val setting = testProviderSetting(model)
    private val params = TextGenerationParams(model = model)
    private val tag = UsageTag(UsageSource.CHAT)

    @Test fun `each collection and retry records one independent attempt retaining explicit zero`() = runBlocking {
        val dao = UsageTestDAO()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val script = listOf(StreamChunk.Usage(UsageUpdate(promptTokens = 10, completionTokens = 2, totalTokens = 0)))
            val upstream = ScriptedProvider(ArrayDeque(listOf(
                ScriptedAttempt(script, IOException("offline retry")), ScriptedAttempt(script))))
            val flow = UsageRecorder(dao, scope, clock = { 1234L }).wrap(upstream, tag)
                .streamText(setting, emptyList(), params)
            try { flow.toList(); fail("expected failed attempt") } catch (_: IOException) { }
            flow.toList()
            assertEquals(2, dao.records.size)
            dao.records.forEach {
                assertEquals(0L, it.totalTokens)
                assertEquals(1234L, it.startedAt)
                assertTrue(it.usageComplete)
            }
        } finally { scope.cancel() }
    }

    @Test fun `partial usage is retained but marked incomplete and missing usage is not invented`() = runBlocking {
        val dao = UsageTestDAO()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val upstream = ScriptedProvider(ArrayDeque(listOf(
                ScriptedAttempt(listOf(StreamChunk.Usage(UsageUpdate(promptTokens = 10)))),
                ScriptedAttempt(emptyList()))))
            val wrapped = UsageRecorder(dao, scope).wrap(upstream, UsageTag(UsageSource.SUBAGENT))
            repeat(2) { wrapped.streamText(setting, emptyList(), params).toList() }
            assertEquals(1, dao.records.size)
            assertFalse(dao.records.single().usageComplete)
            assertEquals("SUBAGENT", dao.records.single().source)
        } finally { scope.cancel() }
    }

    @Test fun `explicit all-zero usage still represents a recorded request`() = runBlocking {
        val dao = UsageTestDAO()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val upstream = ScriptedProvider(ArrayDeque(listOf(ScriptedAttempt(listOf(
                StreamChunk.Usage(UsageUpdate(promptTokens = 0, completionTokens = 0, totalTokens = 0)))))))
            UsageRecorder(dao, scope).wrap(upstream, tag).streamText(setting, emptyList(), params).toList()
            assertEquals(1, dao.records.size)
            assertTrue(dao.records.single().usageComplete)
        } finally { scope.cancel() }
    }

    @Test fun `cancellation retains already reported partial usage`() = runBlocking {
        val dao = UsageTestDAO()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val upstream = ScriptedProvider(ArrayDeque(listOf(ScriptedAttempt(
                listOf(StreamChunk.Usage(UsageUpdate(promptTokens = 10))),
                CancellationException("offline cancellation")))))
            try {
                UsageRecorder(dao, scope).wrap(upstream, tag).streamText(setting, emptyList(), params).toList()
                fail("expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(1, dao.records.size)
            assertEquals(10L, dao.records.single().promptTokens)
            assertFalse(dao.records.single().usageComplete)
        } finally { scope.cancel() }
    }
}
