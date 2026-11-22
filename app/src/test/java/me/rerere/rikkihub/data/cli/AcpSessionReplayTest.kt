package me.rerere.rikkihub.data.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [AcpSession] against agy 1.5.8 transcripts recorded with docs/e2e-helpers/acp-probe
 * (`PROBE_RAW`, mock Gemini backend). The fixture agent answers each request we send with
 * the frames the real agent sent at that point.
 */
class AcpSessionReplayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun cleanup() { scope.cancel() }

    @Test fun approveRejectQuestionAndPlainTurns() = runBlocking {
        withTimeout(TIMEOUT) {
            val agent = FixtureAgent(fixture("agy-turns"))
            val session = agent.session()
            val info = session.initialize()
            assertTrue(info.resume && info.loadSession && info.agySystemPromptAppend)
            session.authenticate("gemini-api-key")
            val opened = session.open(null, "/workspace", buildJsonObject { put("x", 1) })
            assertEquals(AcpOpenResult.Created("5778289a-5c4c-4ffd-b555-0f57506ab08c", previousLost = false), opened)
            assertEquals(listOf("model", "thought_level", "mode"), session.runtime.value.configOptions.map { it.id })

            // Approve: thought, tool card completed with the command output, then the answer.
            val permissions = mutableListOf<AcpPermissionRequest>()
            val first = agent.turn(session, "write hi") { request ->
                permissions += request
                AcpPermissionOutcome.Selected(request.options.first { it.kind == "allow_once" }.optionId)
            }
            assertEquals("end_turn", first.second.stopReason)
            assertEquals(listOf("allow_once", "reject_once"), permissions.single().options.map { it.kind })
            val parts = first.first.parts
            assertEquals("Thinking about writing the file.", (parts[0] as UIMessagePart.Reasoning).reasoning)
            assertTrue("reasoning closed by the tool call", (parts[0] as UIMessagePart.Reasoning).finishedAt != null)
            val tool = parts[1] as UIMessagePart.Tool
            assertEquals("acp_run_command", tool.toolName)
            assertEquals(permissions.single().toolCallId, tool.toolCallId)
            assertEquals("completed", AcpParts.status(tool))
            assertTrue(tool.output.single().let { (it as UIMessagePart.Text).text }.contains("Command exited with code 0"))
            assertTrue(tool.inputAsJson().jsonObject.containsKey("CommandLine"))
            assertEquals("Wrote **hi.txt**.", (parts[2] as UIMessagePart.Text).text)
            assertEquals(3, parts.size)
            assertTrue("the echo of our own message is dropped", parts.none { it is UIMessagePart.Text && it.text == "write hi" })
            assertTrue(agent.sent.any { it["result"]?.jsonObject?.get("outcome").toString().contains("\"allow\"") })

            // Reject: the call fails and agy ends the turn without asking the model again.
            val second = agent.turn(session, "remove it") { request ->
                AcpPermissionOutcome.Selected(request.options.first { it.kind == "reject_once" }.optionId)
            }
            val rejected = second.first.parts.single() as UIMessagePart.Tool
            assertEquals("failed", AcpParts.status(rejected))
            assertEquals("[failed]", (rejected.output.single() as UIMessagePart.Text).text)

            // ask_question: a pending card plus the question as text; the turn just ends.
            val third = agent.turn(session, "ask me") { fail("no permission expected"); AcpPermissionOutcome.Cancelled }
            val question = third.first.parts[0] as UIMessagePart.Tool
            assertEquals(AcpParts.ASK_QUESTION, question.toolName)
            assertTrue(question.output.isEmpty())
            assertTrue((third.first.parts[1] as UIMessagePart.Text).text.contains("Pick a color"))
            val finished = AcpParts.finishTools(third.first, "cancelled")
            assertEquals("", ((finished.parts[0] as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text)

            // The answer turn: agy completes the old question first; that must not leak into this message.
            val fourth = agent.turn(session, "plain") { fail("no permission expected"); AcpPermissionOutcome.Cancelled }
            assertEquals(listOf("Plain answer."), fourth.first.parts.map { (it as UIMessagePart.Text).text })
            assertTrue(session.runtime.value.commands.any { it.name == "compact" })
            assertEquals(1_048_576L, session.runtime.value.contextTokens)

            agent.assertClean()
        }
    }

    @Test fun loadIgnoresTheReplayAndCancelWaitsForTheAgent() = runBlocking {
        withTimeout(TIMEOUT) {
            // A load-only agent: same transcript without the resume capability.
            val agent = FixtureAgent(withoutResume(fixture("agy-load-cancel")))
            val session = agent.session()
            assertFalse(session.initialize().resume)
            session.authenticate("gemini-api-key")
            val opened = session.open("5778289a-5c4c-4ffd-b555-0f57506ab08c", "/workspace", null)
            assertEquals(AcpOpenResult.Resumed("5778289a-5c4c-4ffd-b555-0f57506ab08c"), opened)

            val message = AtomicReference(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
            val asked = CompletableDeferred<Unit>()
            val turn = scope.async {
                session.prompt(
                    blocks = listOf(textBlock("slow one")),
                    onUpdate = { update -> message.updateAndGet { AcpParts.fold(it, update) } },
                    onPermission = { asked.complete(Unit); awaitCancellation() },
                )
            }
            asked.await()
            turn.cancel()
            try {
                turn.await()
                fail("cancelled turn must not complete normally")
            } catch (_: CancellationException) {
            }
            assertTrue("cancelled outcome sent", agent.sent.any { it["result"].toString().contains("cancelled") })
            assertTrue("session/cancel sent", agent.sent.any { it.method() == "session/cancel" })
            assertTrue("process kept: agent ended the turn in time", !session.isClosed)
            val tool = message.get().parts.single() as UIMessagePart.Tool
            assertEquals("failed", AcpParts.status(tool))

            // The next turn works and contains nothing from the replayed history.
            val after = agent.turn(session, "after") { AcpPermissionOutcome.Cancelled }
            assertEquals(listOf("After cancel."), after.first.parts.map { (it as UIMessagePart.Text).text })
            agent.assertClean()
        }
    }

    @Test fun resumeOfAnUnknownSessionFallsBackToANewOne() = runBlocking {
        withTimeout(TIMEOUT) {
            val frames = fixture("agy-resume")
            // init, auth, resume(known) ok, resume(unknown) error … then the new session we expect.
            val upToUnknownResume = frames.take(8)
            val agent = FixtureAgent(
                upToUnknownResume + listOf(
                    "send" to request(90, "session/new"),
                    "recv" to buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 90)
                        put("result", buildJsonObject { put("sessionId", "fresh") })
                    },
                )
            )
            val session = agent.session()
            session.initialize()
            session.authenticate("gemini-api-key")
            assertEquals(
                AcpOpenResult.Resumed("5778289a-5c4c-4ffd-b555-0f57506ab08c"),
                session.open("5778289a-5c4c-4ffd-b555-0f57506ab08c", "/workspace", null),
            )
            assertEquals(
                AcpOpenResult.Created("fresh", previousLost = true),
                session.open("00000000-0000-0000-0000-000000000000", "/workspace", null),
            )
            assertEquals("fresh", session.sessionId)
            agent.assertClean()
        }
    }

    @Test fun loadOfAnUnknownSessionCountsAsLost() = runBlocking {
        withTimeout(TIMEOUT) {
            val frames = withoutResume(fixture("agy-resume"))
            // init, auth, then the load of an unknown id: agy answers with an empty session.
            val agent = FixtureAgent(
                frames.take(4) + frames.drop(8) + listOf(
                    "send" to request(91, "session/new"),
                    "recv" to buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 91)
                        put("result", buildJsonObject { put("sessionId", "fresh") })
                    },
                )
            )
            val session = agent.session()
            session.initialize()
            session.authenticate("gemini-api-key")
            assertEquals(
                AcpOpenResult.Created("fresh", previousLost = true),
                session.open("00000000-0000-0000-0000-000000000001", "/workspace", null),
            )
            agent.assertClean()
        }
    }

    @Test fun authRequiredIsNotMistakenForALostSession() = runBlocking {
        withTimeout(TIMEOUT) {
            val frames = fixture("agy-resume").take(4)
            val agent = FixtureAgent(
                frames + listOf(
                    "send" to request(92, "session/resume"),
                    "recv" to buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 92)
                        put("error", buildJsonObject {
                            put("code", AcpException.AUTH_REQUIRED)
                            put("message", "Authentication required")
                        })
                    },
                )
            )
            val session = agent.session()
            session.initialize()
            session.authenticate("gemini-api-key")
            try {
                session.open("5778289a-5c4c-4ffd-b555-0f57506ab08c", "/workspace", null)
                fail("auth errors must propagate")
            } catch (error: AcpException) {
                assertEquals(AcpException.AUTH_REQUIRED, error.code)
            }
        }
    }

    @Test fun pendingRequestsFailWhenTheAgentExits() = runBlocking {
        withTimeout(TIMEOUT) {
            val agent = FixtureAgent(fixture("agy-resume").take(2) + listOf("send" to request(93, "authenticate")))
            val session = agent.session()
            session.initialize()
            agent.exitAfterScript = true
            try {
                session.authenticate("gemini-api-key")
                fail("agent exit must fail the pending request")
            } catch (_: AcpClosedException) {
            }
            assertTrue(session.isClosed)
        }
    }

    // ------------------------------------------------------------------ fixture agent

    private inner class FixtureAgent(private val frames: List<Pair<String, JsonObject>>) {
        private val toAgent = Pipe.open()
        private val toClient = Pipe.open()
        private val errors = Pipe.open()
        private val idMap = HashMap<String, JsonElement>()
        private val mismatches = CopyOnWriteArrayList<String>()
        val sent = CopyOnWriteArrayList<JsonObject>()
        @Volatile var exitAfterScript = false

        private val clientStdin: OutputStream = Channels.newOutputStream(toAgent.sink())
        private val clientStdout: InputStream = Channels.newInputStream(toClient.source())

        fun session(): AcpSession {
            thread(isDaemon = true) { run() }
            return AcpSession(
                stdout = clientStdout,
                stdin = clientStdin,
                stderr = Channels.newInputStream(errors.source()),
                scope = scope,
                closeProcess = { runCatching { clientStdin.close() } },
            )
        }

        suspend fun turn(
            session: AcpSession,
            text: String,
            onPermission: suspend (AcpPermissionRequest) -> AcpPermissionOutcome,
        ): Pair<UIMessage, AcpPromptResult> {
            val message = AtomicReference(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
            val result = session.prompt(
                blocks = listOf(textBlock(text)),
                onUpdate = { update -> message.updateAndGet { AcpParts.fold(it, update) } },
                onPermission = onPermission,
            )
            return message.get() to result
        }

        fun assertClean() = assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())

        private fun run() {
            val reader = Channels.newInputStream(toAgent.source()).bufferedReader()
            val out = Channels.newOutputStream(toClient.sink())
            var i = 0
            script@ while (i < frames.size) {
                val (direction, frame) = frames[i]
                if (direction == "recv") {
                    out.write((JsonInstant.encodeToString(JsonObject.serializer(), remap(frame)) + "\n").toByteArray())
                    out.flush()
                    i++
                    continue
                }
                var end = i
                while (end < frames.size && frames[end].first == "send") end++
                val expected = frames.subList(i, end).map { it.second }.toMutableList()
                while (expected.isNotEmpty()) {
                    val line = reader.readLine() ?: break@script
                    val got = JsonInstant.parseToJsonElement(line).jsonObject
                    sent += got
                    val match = expected.indexOfFirst { it.method() == got.method() }
                    if (match < 0) {
                        mismatches += "unexpected ${got.method() ?: "response"}; expected ${expected.map { it.method() ?: "response" }}"
                        continue
                    }
                    val wanted = expected.removeAt(match)
                    if (wanted.method() != null) wanted["id"]?.let { id -> got["id"]?.let { idMap[id.toString()] = it } }
                }
                i = end
            }
            if (exitAfterScript) {
                out.close()
                return
            }
            while (true) {
                val line = reader.readLine() ?: break
                sent += JsonInstant.parseToJsonElement(line).jsonObject
            }
            out.close()
        }

        // Responses carry the fixture's request id; give them the id our client used.
        private fun remap(frame: JsonObject): JsonObject {
            if (frame.method() != null) return frame
            val id = frame["id"] ?: return frame
            val mapped = idMap[id.toString()] ?: return frame
            return JsonObject(frame + ("id" to mapped))
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L

        fun JsonObject.method(): String? = (this["method"] as? JsonPrimitive)?.content

        fun fixture(name: String): List<Pair<String, JsonObject>> =
            checkNotNull(AcpSessionReplayTest::class.java.getResource("/acp/$name.jsonl")).readText()
                .lines().filter { it.isNotBlank() }.map { line ->
                    val obj = JsonInstant.parseToJsonElement(line).jsonObject
                    (obj["dir"] as JsonPrimitive).content to obj["frame"]!!.jsonObject
                }

        fun withoutResume(frames: List<Pair<String, JsonObject>>) = frames.map { (direction, frame) ->
            val result = frame["result"] as? JsonObject
            val capabilities = result?.get("agentCapabilities") as? JsonObject
            if (capabilities == null) return@map direction to frame
            val sessionCapabilities = (capabilities["sessionCapabilities"] as JsonObject) - "resume"
            val patched = JsonObject(capabilities + ("sessionCapabilities" to JsonObject(sessionCapabilities)))
            direction to JsonObject(frame + ("result" to JsonObject(result + ("agentCapabilities" to patched))))
        }

        fun request(id: Int, method: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
        }

        fun textBlock(text: String) = buildJsonObject {
            put("type", "text")
            put("text", text)
        }
    }
}
