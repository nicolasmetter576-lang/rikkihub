package me.rerere.rikkihub.ui.pages.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputPolisherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var text = ""
    private val errors = mutableListOf<Throwable>()
    private val polishedInputs = mutableListOf<String>()

    @After
    fun tearDown() = scope.cancel()

    private fun polisher(polish: (String) -> Flow<String>) = ChatInputPolisher(
        scope = scope,
        polish = { polishedInputs += it; polish(it) },
        read = { text },
        write = { text = it },
        onError = { errors += it },
    )

    private fun ChatInputPolisher.awaitState(predicate: (PromptPolishState) -> Boolean) = runBlocking {
        withTimeout(2_000) { state.first(predicate) }
    }

    @Test
    fun `polish overwrites the input, undo restores it`() {
        text = "原文"
        val polisher = polisher { flowOf("润", "润色后") }
        polisher.start()
        polisher.awaitState { it is PromptPolishState.Done }
        assertEquals("润色后", text)
        assertEquals(PromptPolishState.Done("原文", "润色后"), polisher.state.value)
        polisher.onTextChanged("润色后") // the field echoing our own write keeps undo available
        polisher.undo()
        assertEquals("原文", text)
        assertEquals(PromptPolishState.Idle, polisher.state.value)
    }

    @Test
    fun `editing or sending after polishing drops undo`() {
        text = "原文"
        val polisher = polisher { flowOf("结果") }
        polisher.start()
        polisher.awaitState { it is PromptPolishState.Done }
        polisher.onTextChanged("结果!")
        assertEquals(PromptPolishState.Idle, polisher.state.value)
        polisher.undo()
        assertEquals("结果", text) // nothing to undo any more
    }

    @Test
    fun `blank input does nothing, a second start while running is ignored`() {
        text = "  "
        val idle = polisher { flowOf("x") }
        idle.start()
        assertEquals(PromptPolishState.Idle, idle.state.value)
        assertTrue(polishedInputs.isEmpty())

        text = "原文"
        val gate = Channel<String>(Channel.UNLIMITED)
        val running = polisher { gate.consumeAsFlow() }
        running.start()
        running.start()
        assertEquals(1, polishedInputs.size)
        assertEquals(PromptPolishState.Running("原文"), running.state.value)
        gate.trySend("半")
        assertEquals("半", text)
        gate.close()
        running.awaitState { it is PromptPolishState.Done }
        assertEquals("半", text)
    }

    @Test
    fun `cancel mid-stream restores the original`() {
        text = "原文"
        val gate = Channel<String>(Channel.UNLIMITED)
        val polisher = polisher { gate.consumeAsFlow() }
        polisher.start()
        gate.trySend("写到一半")
        assertEquals("写到一半", text)
        polisher.cancel()
        polisher.awaitState { it == PromptPolishState.Idle }
        assertEquals("原文", text)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `failure restores the original and reports the error`() {
        text = "原文"
        val polisher = polisher {
            flow {
                emit("写到一半")
                throw IllegalStateException("boom")
            }
        }
        polisher.start()
        polisher.awaitState { it == PromptPolishState.Idle }
        assertEquals("原文", text)
        assertEquals("boom", errors.single().message)
    }

    @Test
    fun `an empty stream leaves the input untouched`() {
        text = "原文"
        val polisher = polisher { flow { } }
        polisher.start()
        polisher.awaitState { it == PromptPolishState.Idle }
        assertEquals("原文", text)
    }
}
