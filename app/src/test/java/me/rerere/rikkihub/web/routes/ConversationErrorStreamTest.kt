package me.rerere.rikkihub.web.routes

import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.rerere.rikkihub.service.ChatError
import me.rerere.rikkihub.service.ChatErrorSolution
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.installWebApiStatusPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationErrorStreamTest {

    @Test
    fun `conversationErrorsFlow replays pre-existing offline errors on subscribe, isolates conversations, and emits empty on clear`() = runTest {
        val convA = Uuid.random()
        val convB = Uuid.random()
        val offlineError = ChatError(
            title = "Generation failed",
            error = IllegalStateException("context_length_exceeded"),
            conversationId = convA,
            timestamp = 1_700_000_000_000L,
            solution = ChatErrorSolution.CompressAndRetry,
            affectsReply = true,
        )
        val otherConvError = ChatError(
            title = "Other",
            error = RuntimeException("other error"),
            conversationId = convB,
            timestamp = 1_700_000_000_001L,
        )
        val errorsState = MutableStateFlow(listOf(offlineError, otherConvError))

        // First subscription (simulating reconnect after offline failure) immediately gets convA's error only.
        val initial = errorsState.conversationErrorsFlow(convA).first()
        assertEquals("errors", initial.type)
        assertEquals(convA.toString(), initial.conversationId)
        assertEquals(1, initial.errors.size)
        val dto = initial.errors.single()
        assertEquals(offlineError.id.toString(), dto.id)
        assertEquals("Generation failed", dto.title)
        assertEquals("context_length_exceeded", dto.message)
        assertEquals("compress_and_retry", dto.solution)
        assertEquals(1_700_000_000_000L, dto.timestamp)
        assertTrue(dto.affectsReply)

        // Collect 2 distinct emissions: initial [offlineError], then [] after convA clears.
        // Mutating convB in between must NOT emit a duplicate event for convA.
        val emitted = mutableListOf<List<String>>()
        val collector = launch {
            errorsState.conversationErrorsFlow(convA).take(2).collect { evt ->
                emitted += evt.errors.map { it.id }
            }
        }
        testScheduler.runCurrent()
        // Unrelated convB change -> filtered out by distinctUntilChanged.
        errorsState.value = listOf(offlineError)
        testScheduler.runCurrent()
        assertEquals(1, emitted.size)
        // Clear convA errors -> emits empty list.
        errorsState.value = emptyList()
        collector.join()
        assertEquals(listOf(listOf(offlineError.id.toString()), emptyList()), emitted)
    }

    @Test
    fun `toConversationErrorDto handles blank message fallback, non-compress solutions, and warning flag`() {
        val err = ChatError(
            title = "   ",
            error = IllegalStateException(""),
            conversationId = Uuid.random(),
            timestamp = 42L,
            solution = ChatErrorSolution.CheckFastModelSettings,
            affectsReply = false,
        )
        val dto = err.toConversationErrorDto()
        assertNull(dto.title)
        assertNull(dto.solution)
        assertEquals(false, dto.affectsReply)
        assertTrue(dto.message.contains("IllegalStateException"))
    }

    @Test
    fun `DELETE conversation error route validates UUIDs and enforces conversation ownership`() = testApplication {
        val convA = Uuid.random()
        val convB = Uuid.random()
        val errA = ChatError(error = RuntimeException("boom"), conversationId = convA)
        val stored = MutableStateFlow(listOf(errA))

        application {
            install(ContentNegotiation) { json(JsonInstant) }
            installWebApiStatusPages()
            routing {
                route("/api/conversations") {
                    conversationErrorDismissRoute { conversationId, errorId ->
                        stored.value = stored.value.filterNot {
                            it.id == errorId && it.conversationId == conversationId
                        }
                    }
                }
            }
        }

        // Wrong conversationId cannot dismiss convA's error.
        val wrongConv = client.delete("/api/conversations/$convB/errors/${errA.id}")
        assertEquals(HttpStatusCode.NoContent, wrongConv.status)
        assertEquals(1, stored.value.size)

        // Matching conversationId + errorId dismisses it.
        val ok = client.delete("/api/conversations/$convA/errors/${errA.id}")
        assertEquals(HttpStatusCode.NoContent, ok.status)
        assertTrue(stored.value.isEmpty())

        // Invalid UUID returns 400 BadRequest.
        val badConv = client.delete("/api/conversations/not-a-uuid/errors/${errA.id}")
        assertEquals(HttpStatusCode.BadRequest, badConv.status)
        val badErr = client.delete("/api/conversations/$convA/errors/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, badErr.status)
    }
}
