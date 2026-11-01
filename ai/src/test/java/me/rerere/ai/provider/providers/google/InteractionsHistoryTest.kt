package me.rerere.ai.provider.providers.google

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Step schema/citation/usage examples derived from fixed U, with fork-specific
 * grouped exchange, edits, approval, signatures and strict shared-handler coverage. */
class InteractionsHistoryTest {
    private val api = InteractionsAPI(OkHttpClient())
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun input(vararg messages: UIMessage) = api.buildInput(messages.toList()).map { it.jsonObject }
    private fun types(steps: List<JsonObject>) = steps.map { it["type"]!!.jsonPrimitive.content }
    private fun once(steps: String, id: String = "wire-id") = api.parseInteraction(obj("""{"id":"$id","status":"requires_action","model":"gemini-test","steps":$steps}"""), knownToolNames = setOf("get_weather", "other")).message
    private fun execute(message: UIMessage, output: String = "result") = message.copy(parts = message.parts.map {
        if (it is UIMessagePart.Tool) it.copy(output = listOf(UIMessagePart.Text(output))) else it
    })
    private fun metadata(index: Int, contentIndex: Int? = null, key: String = "exchange") =
        GoogleInteractionsMetadata(stepIndex = index, contentIndex = contentIndex, exchangeKey = key).toMetadata()

    @Test fun `metadata codec defaults are nullable and protocol signatures are isolated`() {
        val old = UIMessagePart.Reasoning("", metadata = obj("""{"thoughtSignature":"generate-sig","signature":"claude-sig"}"""))
        val interactions = old.metadataAs<GoogleInteractionsMetadata>()!!
        assertNull(interactions.signature); assertNull(interactions.stepIndex); assertNull(interactions.exchangeKey)
        val part = old.copy(metadata = GoogleInteractionsMetadata("interactions-sig", 3, null, "exchange").toMetadata())
        val roundtrip = json.decodeFromString<UIMessagePart>(json.encodeToString<UIMessagePart>(part))
        assertEquals(part, roundtrip)
        assertEquals("interactions-sig", roundtrip.metadataAs<GoogleInteractionsMetadata>()?.signature)
        assertNull(roundtrip.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertNull(roundtrip.metadataAs<ClaudeReasoningMetadata>()?.signature)
        assertTrue(part.metadata!!.containsKey("interactions_signature"))
        assertFalse(part.metadata!!.containsKey("signature"))
    }

    @Test fun `signature-only thought remains valid only in local interactions upload`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("",
            metadata = GoogleInteractionsMetadata("thought-sig", 0, null, "exchange").toMetadata())))
        assertFalse(message.isValidToUpload()) // Do not globally loosen other protocols.
        val steps = input(message)
        assertEquals(listOf("thought"), types(steps))
        assertEquals("thought-sig", steps.single()["signature"]!!.jsonPrimitive.content)
        assertFalse(steps.single().containsKey("summary"))
    }

    @Test fun `foreign signature-only thoughts are excluded`() {
        for (meta in listOf(GoogleThoughtMetadata("generate-content").toMetadata(), ClaudeReasoningMetadata("claude").toMetadata())) {
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("", metadata = meta)))
            assertTrue(input(message).isEmpty())
        }
    }

    @Test fun `indexed server call and result retain original order around thought and text`() {
        val message = once("""[
            {"type":"google_search_call","id":"gs","arguments":{"queries":["mountain"]},"signature":"call-sig"},
            {"type":"thought","signature":"thought-sig","summary":[{"type":"text","text":"think"}]},
            {"type":"model_output","content":[{"type":"text","text":"before result"}]},
            {"type":"google_search_result","call_id":"gs","signature":"result-sig","result":[{"opaque":true}]},
            {"type":"model_output","content":[{"type":"text","text":"after result"}]}
        ]""")
        // UI has a paired ServerTool at the call position, not a second result part.
        assertEquals(4, message.parts.size)
        val replay = input(message)
        assertEquals(listOf("google_search_call", "thought", "model_output", "google_search_result", "model_output"), types(replay))
        assertEquals("call-sig", replay[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("result-sig", replay[3]["signature"]!!.jsonPrimitive.content)
    }

    @Test fun `two rounds merged into one assistant preserve response groups with resetting indices`() {
        val first = execute(once("""[
            {"type":"google_search_call","id":"gs1","signature":"c1"},
            {"type":"thought","signature":"t1","summary":[{"type":"text","text":"round one"}]},
            {"type":"model_output","content":[{"type":"text","text":"round one text"}]},
            {"type":"function_call","name":"get_weather","id":"fc1","arguments":{"round":1}},
            {"type":"google_search_result","call_id":"gs1","signature":"r1","result":[{"round":1}]}
        ]"""), "client result one")
        val second = execute(once("""[
            {"type":"thought","signature":"t2","summary":[{"type":"text","text":"round two"}]},
            {"type":"url_context_call","id":"url2","signature":"c2"},
            {"type":"model_output","content":[{"type":"text","text":"round two text"}]},
            {"type":"url_context_result","call_id":"url2","signature":"r2","result":[{"round":2}]},
            {"type":"function_call","name":"other","id":"fc2","arguments":{"round":2}}
        ]"""), "client result two")
        val key1 = first.parts.first().metadataAs<GoogleInteractionsMetadata>()!!.exchangeKey
        val key2 = second.parts.first().metadataAs<GoogleInteractionsMetadata>()!!.exchangeKey
        assertNotEquals(key1, key2) // even same wire interaction id must not group rounds
        val merged = first.copy(parts = first.parts + second.parts)
        val reopened = json.decodeFromString<UIMessage>(json.encodeToString(merged))
        val replay = input(UIMessage.user("branch prompt"), reopened)
        assertEquals(listOf("user_input", "google_search_call", "thought", "model_output", "function_call",
            "google_search_result", "function_result", "thought", "url_context_call", "model_output",
            "url_context_result", "function_call", "function_result"), types(replay))
        assertEquals("fc1", replay[6]["call_id"]!!.jsonPrimitive.content)
        assertEquals("fc2", replay[12]["call_id"]!!.jsonPrimitive.content)
        assertEquals("client result one", replay[6]["result"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("client result two", replay[12]["result"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun `latest edited text thought client input and result override prior visible content`() {
        val original = execute(once("""[
            {"type":"thought","signature":"sig","summary":[{"type":"text","text":"old thought"}]},
            {"type":"model_output","content":[{"type":"text","text":"old text"}]},
            {"type":"function_call","name":"get_weather","id":"fc","arguments":{"city":"old"},"signature":"call-sig"}
        ]"""), "old result")
        val edited = original.copy(parts = original.parts.map { part -> when (part) {
            is UIMessagePart.Text -> part.copy(text = "edited text")
            is UIMessagePart.Reasoning -> part.copy(reasoning = "edited thought")
            is UIMessagePart.Tool -> part.copy(input = "{\"city\":\"edited\"}", output = listOf(UIMessagePart.Text("edited result")))
            else -> part
        } })
        val replay = input(edited)
        assertEquals("edited thought", replay[0]["summary"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("edited text", replay[1]["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("edited", replay[2]["arguments"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals("edited result", replay[3]["result"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("call-sig", replay[2]["signature"]!!.jsonPrimitive.content)
    }

    @Test fun `latest server input output edits retain opaque raw signatures and indices`() {
        val original = once("""[{"type":"google_search_call","id":"gs","arguments":{"old":true},"signature":"call"},{"type":"google_search_result","call_id":"gs","result":[{"old":true}],"signature":"result","opaque":"retained"}]""")
        val server = original.parts.single() as UIMessagePart.ServerTool
        val edited = original.copy(parts = listOf(server.copy(input = obj("""{"edited":true}"""), output = json.parseToJsonElement("""[{"edited":true}]"""))))
        val replay = input(edited)
        assertEquals(obj("""{"edited":true}"""), replay[0]["arguments"])
        assertEquals(json.parseToJsonElement("""[{"edited":true}]"""), replay[1]["result"])
        assertEquals("result", replay[1]["signature"]!!.jsonPrimitive.content)
        assertEquals("retained", replay[1]["opaque"]!!.jsonPrimitive.content)
    }

    @Test fun `clearing visible server input and result cannot resurrect stale raw snapshots`() {
        val original = once("""[{"type":"google_search_call","id":"gs","arguments":{"old":true},"signature":"call"},{"type":"google_search_result","call_id":"gs","result":[{"old":true}],"signature":"result"}]""")
        val server = original.parts.single() as UIMessagePart.ServerTool
        val edited = original.copy(parts = listOf(server.copy(input = null, output = null)))
        val replay = input(edited)
        assertFalse(replay[0].containsKey("arguments"))
        assertFalse(replay[1].containsKey("result"))
        assertEquals("result", replay[1]["signature"]!!.jsonPrimitive.content)
    }

    @Test fun `executed client batch emits every call before any result via existing boundary grouping`() {
        val message = execute(once("""[{"type":"function_call","name":"get_weather","id":"a","arguments":{}},{"type":"function_call","name":"other","id":"b","arguments":{}}]"""))
        val replay = input(message)
        assertEquals(listOf("function_call", "function_call", "function_result", "function_result"), types(replay))
        assertEquals(listOf("a", "b"), replay.take(2).map { it["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("a", "b"), replay.drop(2).map { it["call_id"]!!.jsonPrimitive.content })
    }

    @Test fun `pending and unexecuted tools never manufacture fake function results`() {
        val message = once("""[{"type":"model_output","content":[{"type":"text","text":"answer"}]},{"type":"function_call","name":"get_weather","id":"a","arguments":{}}]""")
        val pending = message.copy(parts = message.parts.map { if (it is UIMessagePart.Tool) it.copy(approvalState = ToolApprovalState.Pending) else it })
        assertEquals(listOf("model_output"), types(input(message)))
        assertEquals(listOf("model_output"), types(input(pending)))
    }

    @Test fun `current selected branch only uploads provided edited user and assistant history`() {
        val branch = execute(once("""[{"type":"model_output","content":[{"type":"text","text":"selected answer"}]},{"type":"function_call","name":"get_weather","id":"selected","arguments":{}}]"""))
        val replay = input(UIMessage.system("system"), UIMessage.user("edited selected question"), branch, UIMessage.user("next"))
        assertEquals("edited selected question", replay.first()["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("selected", replay[2]["id"]!!.jsonPrimitive.content)
        assertFalse(replay.toString().contains("unselected"))
    }

    @Test fun `legacy missing exchange provenance uses conservative paired server UI order`() {
        val server = UIMessagePart.ServerTool("gs", "google_search", status = ServerToolStatus.COMPLETED,
            metadata = ServerToolMetadata(ServerToolProtocol.GOOGLE_INTERACTIONS,
                obj("""{"type":"google_search_call","id":"gs"}"""), 0,
                obj("""{"type":"google_search_result","call_id":"gs"}"""), 2).toMetadata())
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(server,
            UIMessagePart.Reasoning("legacy", metadata = GoogleInteractionsMetadata("sig").toMetadata())))
        // Missing response identity makes numeric indices insufficient. This fallback
        // intentionally keeps adjacent call/result, and does not claim original order.
        assertEquals(listOf("google_search_call", "google_search_result", "thought"), types(input(message)))
    }

    @Test fun `legacy missing indices uses same conservative fallback even when key exists`() {
        val thought = UIMessagePart.Reasoning("thought", metadata = GoogleInteractionsMetadata("sig", exchangeKey = "group").toMetadata())
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(thought, UIMessagePart.Text("text", metadata(0, 1, "group"))))
        assertEquals(listOf("thought", "model_output"), types(input(message)))
    }

    @Test fun `foreign incomplete and mismatched server pairs are never replayed`() {
        val pair = ServerToolMetadata(ServerToolProtocol.GOOGLE_INTERACTIONS,
            obj("""{"type":"google_search_call","id":"a"}"""), 0,
            obj("""{"type":"google_search_result","call_id":"a"}"""), 1)
        for (meta in listOf(pair.copy(protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT), pair.copy(result = null),
            pair.copy(result = obj("""{"type":"google_search_result","call_id":"different"}""")),
            pair.copy(result = obj("""{"type":"url_context_result","call_id":"a"}""")))) {
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.ServerTool("a", "google_search", status = ServerToolStatus.COMPLETED, metadata = meta.toMetadata())))
            assertTrue(input(message).isEmpty())
        }
    }

    @Test fun `images within model step replay between text fragments with codec stable metadata`() {
        val message = once("""[{"type":"model_output","content":[{"type":"text","text":"before"},{"type":"image","mime_type":"image/png","data":"QUJD"},{"type":"text","text":"after"}]}]""")
        val reopened = json.decodeFromString<UIMessage>(json.encodeToString(message))
        val replay = input(reopened).single()
        assertEquals(listOf("text", "image", "text"), replay["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("QUJD", replay["content"]!!.jsonArray[1].jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test fun `nonstream parser uses strict shared decoder and keeps usage citations signatures`() {
        val result = api.parseInteraction(obj("""{"id":"int_456","status":"requires_action","model":"gemini-test","steps":[
            {"type":"google_search_call","id":"gs","signature":"call-sig"},
            {"type":"google_search_result","call_id":"gs","signature":"result-sig"},
            {"type":"thought","summary":[{"type":"text","text":"Need weather"}],"signature":"thought-sig"},
            {"type":"model_output","content":[{"type":"text","text":"Answer","annotations":[{"type":"url_citation","url":"https://www.nfl.com","title":"NFL.com"}]},{"type":"image","mime_type":"image/png","data":"AAAA"}]},
            {"type":"function_call","id":"fc","name":"get_weather","arguments":{},"signature":"fc-sig"}
        ],"usage":{"total_input_tokens":10,"total_output_tokens":5,"total_thought_tokens":3,"total_tokens":18}}"""), knownToolNames = setOf("get_weather"))
        assertEquals("int_456", result.id)
        assertEquals("requires_action", result.finishReason)
        assertEquals(TokenUsage(promptTokens = 10, completionTokens = 8, totalTokens = 18), result.usage)
        assertEquals(listOf(UIMessageAnnotation.UrlCitation("NFL.com", "https://www.nfl.com")), result.message.annotations)
        assertEquals("fc-sig", result.message.getTools().single().metadataAs<GoogleInteractionsMetadata>()?.signature)
        assertEquals("{}", result.message.getTools().single().input)
        assertTrue(result.message.parts.all { !it.metadataAs<GoogleInteractionsMetadata>()?.exchangeKey.isNullOrEmpty() })
    }

    @Test fun `nonstream parser rejects duplicate call ids and names not declared on final wire`() {
        assertThrows(ToolStreamProtocolException::class.java) { once("""[{"type":"function_call","name":"get_weather","id":"a","arguments":{}},{"type":"function_call","name":"other","id":"a","arguments":{}}]""") }
        assertThrows(ToolStreamProtocolException::class.java) { once("""[{"type":"function_call","name":"unregistered","id":"a","arguments":{}}]""") }
    }

    @Test fun `shared handler preserves reused server wire id across two merged response groups`() {
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("prompt"))
        for (round in 1..2) {
            val decoder = InteractionsStreamDecoder(exchangeKey = "round-$round")
            val chunks = mutableListOf<StreamChunk>()
            listOf(
                "step.start" to obj("""{"index":0,"step":{"type":"google_search_call","id":"reused","arguments":{"round":$round}}}"""),
                "step.stop" to obj("""{"index":0}"""),
                "step.start" to obj("""{"index":1,"step":{"type":"thought","signature":"sig-$round"}}"""),
                "step.stop" to obj("""{"index":1}"""),
                "step.start" to obj("""{"index":2,"step":{"type":"google_search_result","call_id":"reused","result":{"round":$round}}}"""),
                "step.stop" to obj("""{"index":2}"""),
            ).forEach { (type, payload) -> chunks += decoder.accept(me.rerere.ai.provider.stream.SseEvent(event = type, data = payload.toString())).chunks }
            chunks += decoder.accept(me.rerere.ai.provider.stream.SseEvent(data = "[DONE]")).chunks
            chunks.forEach { messages = handler.handle(messages, it) }
        }
        val merged = messages.last()
        val serverParts = merged.parts.filterIsInstance<UIMessagePart.ServerTool>()
        assertEquals(2, serverParts.size)
        assertNotEquals(serverParts[0].toolCallId, serverParts[1].toolCallId)
        assertEquals(listOf("reused", "reused"), serverParts.map { it.metadataAs<ServerToolMetadata>()!!.call!!["id"]!!.jsonPrimitive.content })
        val replay = input(merged)
        assertEquals(listOf("google_search_call", "thought", "google_search_result", "google_search_call", "thought", "google_search_result"), types(replay))
        assertEquals(1, replay[0]["arguments"]!!.jsonObject["round"]!!.jsonPrimitive.int)
        assertEquals(2, replay[3]["arguments"]!!.jsonObject["round"]!!.jsonPrimitive.int)
    }

    @Test fun `opaque metadata including raw server steps survives UIMessage serialization`() {
        val message = once("""[{"type":"google_search_call","id":"gs","opaque":{"name":"literal"},"signature":"call"},{"type":"thought","signature":"sig"},{"type":"google_search_result","call_id":"gs","opaque":[1,2],"signature":"result"}]""")
        val reopened = json.decodeFromString<UIMessage>(json.encodeToString(message))
        assertEquals(message, reopened)
        assertEquals(input(message), input(reopened))
    }
}
