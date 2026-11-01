package me.rerere.ai.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionContextTest {
    @Test
    fun `nested generation publishes progress in the collecting flow context`() = runBlocking {
        val progress = buildJsonObject { put("status", "running") }
        val result = flow {
            val context = ToolExecutionContext("call") { emit(it) }
            context.publishMetadata(progress) // Initial progress before the provider responds.
            flow { emit(progress) }.flowOn(Dispatchers.IO).collect {
                context.publishMetadata(it)
            }
        }.toList()
        assertEquals(listOf(progress, progress), result)
    }

    @Test
    fun `failed progress consumer is distinguished from tool execution failure`() = runBlocking {
        val consumerFailure = IllegalStateException("consumer failed")
        var publishes = 0
        val result = runCatching {
            flow<JsonObject> {
                val context = ToolExecutionContext("call") {
                    publishes++
                    emit(it)
                }
                try {
                    context.publishMetadata(buildJsonObject { put("status", "running") })
                } catch (failure: Throwable) {
                    // A runner must not attempt a terminal/error publish to this collector.
                    if (failure is ToolProgressException) throw failure
                    context.publishMetadata(buildJsonObject { put("status", "error") })
                }
            }.collect { throw consumerFailure }
        }
        assertTrue(result.exceptionOrNull() is ToolProgressException)
        assertSame(consumerFailure, result.exceptionOrNull()?.cause)
        assertEquals(1, publishes)
    }

    @Test
    fun `cancellation is never wrapped as a progress failure`() = runBlocking {
        val cancelled = CancellationException("stop")
        val context = ToolExecutionContext("call") { throw cancelled }
        val result = runCatching { context.publishMetadata(buildJsonObject {}) }
        assertSame(cancelled, result.exceptionOrNull())
    }
}
