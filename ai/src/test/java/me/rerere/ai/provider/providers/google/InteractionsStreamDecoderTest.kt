package me.rerere.ai.provider.providers.google

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.*
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import org.junit.Assert.*
import org.junit.Test

class InteractionsStreamDecoderTest {
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun decoder(names: Set<String> = setOf("get_weather", "other")) =
        InteractionsStreamDecoder("fallback", names, exchangeKey = "offline-exchange")
    private fun event(type: String, payload: JsonObject) = SseEvent(event = type, data = payload.toString())
    private fun start(d: InteractionsStreamDecoder, index: Int, step: String) = d.accept(event("step.start", buildJsonObject {
        put("index", index); put("step", obj(step))
    })).chunks
    private fun delta(d: InteractionsStreamDecoder, index: Int, step: String) = d.accept(event("step.delta", buildJsonObject {
        put("index", index); put("delta", obj(step))
    })).chunks
    private fun args(d: InteractionsStreamDecoder, index: Int, value: String) = d.accept(event("step.delta", buildJsonObject {
        put("index", index); put("delta", buildJsonObject { put("type", "arguments_delta"); put("arguments", value) })
    })).chunks
    private fun stop(d: InteractionsStreamDecoder, index: Int, final: String? = null) = d.accept(event("step.stop", buildJsonObject {
        put("index", index); final?.let { put("step", obj(it)) }
    })).chunks
    private fun done(d: InteractionsStreamDecoder) = d.accept(event("interaction.completed", obj("""{"interaction":{"id":"late-interaction","model":"wire-model","status":"requires_action"}}""")))
    private fun render(chunks: List<StreamChunk>): UIMessage {
        // Replay serializable production chunks through the unmodified shared handler.
        val persisted = json.decodeFromString<List<StreamChunk>>(json.encodeToString(chunks))
        val handler = StreamChunkHandler()
        return persisted.fold(listOf(UIMessage.user("prompt"))) { messages, chunk -> handler.handle(messages, chunk) }.last()
    }
    private fun expectProtocol(action: () -> Unit) = assertThrows(ToolStreamProtocolException::class.java) { action() }

    @Test fun `parallel calls retain stable logical keys independent of late interaction id`() {
        val d = decoder()
        val chunks = buildList {
            addAll(start(d, 3, """{"type":"function_call","name":"get_weather"}"""))
            addAll(start(d, 5, """{"type":"function_call","name":"other","id":"other-id"}"""))
            addAll(args(d, 3, "{\"city\":")); addAll(args(d, 5, "{}"))
            d.accept(event("interaction.created", obj("""{"interaction":{"id":"late-interaction"}}""")))
            addAll(delta(d, 3, """{"type":"function_call","id":"late-call"}"""))
            addAll(args(d, 3, "\"Boston\"}")); addAll(stop(d, 5)); addAll(stop(d, 3)); addAll(done(d).chunks)
        }
        val starts = chunks.filterIsInstance<StreamChunk.ToolCallStart>()
        assertEquals(2, starts.size)
        assertNotEquals(starts[0].id, starts[1].id)
        assertEquals("interactions-tool-3", starts[0].toolCallId)
        val assigned = chunks.filterIsInstance<StreamChunk.ToolCallIdAssigned>().single()
        assertEquals(starts[0].id, assigned.id)
        assertEquals("late-call", assigned.toolCallId)
        val tools = render(chunks).getTools()
        assertEquals(listOf("late-call", "other-id"), tools.map { it.toolCallId })
        assertEquals(listOf("{\"city\":\"Boston\"}", "{}"), tools.map { it.input })
        assertEquals("offline-exchange", tools[0].metadataAs<GoogleInteractionsMetadata>()?.exchangeKey)
    }

    @Test fun `empty arguments snapshot is a complete valid object`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"function_call","name":"get_weather","id":"call","arguments":{}}""") + stop(d, 0) + done(d).chunks
        assertEquals("{}", render(chunks).getTools().single().input)
    }

    @Test fun `empty object deltas are valid and no synthetic empty arguments are added`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"function_call","name":"get_weather"}""") + args(d, 0, "{") + args(d, 0, "}") + stop(d, 0) + done(d).chunks
        assertEquals("{}", render(chunks).getTools().single().input)
    }

    @Test fun `every repeated argument character and fragment is appended`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"function_call","name":"get_weather"}""") +
            args(d, 0, "{\"v\":\"") + args(d, 0, "aa") + args(d, 0, "aa") + args(d, 0, "\"}") + stop(d, 0) + done(d).chunks
        assertEquals("{\"v\":\"aaaa\"}", render(chunks).getTools().single().input)
    }

    @Test fun `initial snapshot plus equivalent delta is validated without duplication`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"function_call","name":"get_weather","arguments":{"a":1}}""") +
            args(d, 0, "{\"a\":1}") + stop(d, 0) + done(d).chunks
        assertEquals("{\"a\":1}", render(chunks).getTools().single().input)
    }

    @Test fun `streaming start empty object yields to populated argument deltas per protocol fixture`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"function_call","name":"get_weather","arguments":{}}""") +
            args(d, 0, "{\"a\":1}") + stop(d, 0) + done(d).chunks
        assertEquals("{\"a\":1}", render(chunks).getTools().single().input)
    }

    @Test fun `populated initial snapshot and conflicting deltas still fail`() {
        val d = decoder()
        start(d, 0, """{"type":"function_call","name":"get_weather","arguments":{"a":2}}""")
        args(d, 0, "{\"a\":1}")
        expectProtocol { stop(d, 0) }
        assertTrue(d.onClosed().isEmpty())
    }

    @Test fun `final arguments snapshot validates against deltas and is not appended`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"function_call","name":"get_weather"}""") + args(d, 0, "{}") +
            stop(d, 0, """{"type":"function_call","arguments":{}}""") + done(d).chunks
        assertEquals("{}", render(chunks).getTools().single().input)
    }

    @Test fun `conflicting final snapshot fails`() {
        val d = decoder()
        start(d, 0, """{"type":"function_call","name":"get_weather"}""")
        args(d, 0, "{}")
        expectProtocol { stop(d, 0, """{"arguments":{"changed":true}}""") }
    }

    @Test fun `stop cannot silently override previously received final snapshot`() {
        val d = decoder()
        start(d, 0, """{"type":"function_call","name":"get_weather"}""")
        delta(d, 0, """{"type":"function_call","arguments":{}}""")
        expectProtocol { stop(d, 0, """{"arguments":{"a":1}}""") }
    }

    @Test fun `invalid or missing complete arguments fail closed`() {
        for (fragment in listOf("", "{", "[]")) {
            val d = decoder()
            start(d, 0, """{"type":"function_call","name":"get_weather"}""")
            if (fragment.isNotEmpty()) args(d, 0, fragment)
            expectProtocol { stop(d, 0) }
        }
    }

    @Test fun `equal repeated start stop and done remain idempotent including ended registry`() {
        val d = decoder()
        val raw = """{"type":"function_call","name":"get_weather","id":"one","arguments":{}}"""
        start(d, 0, raw)
        assertTrue(start(d, 0, raw).isEmpty())
        stop(d, 0)
        assertTrue(start(d, 0, raw).isEmpty())
        assertTrue(stop(d, 0).isEmpty())
        assertEquals(1, done(d).chunks.count { it is StreamChunk.Finish })
        assertTrue(done(d).chunks.isEmpty())
        assertTrue(d.accept(SseEvent(data = "[DONE]")).chunks.isEmpty())
        assertTrue(d.onClosed().isEmpty())
    }

    @Test fun `conflicting repeated start is rejected without replacing state`() {
        val d = decoder()
        start(d, 0, """{"type":"function_call","name":"get_weather","arguments":{}}""")
        expectProtocol { start(d, 0, """{"type":"function_call","name":"other","arguments":{}}""") }
        assertTrue(d.onClosed().isEmpty())
    }

    @Test fun `conflicting repeated stop is rejected`() {
        val d = decoder()
        start(d, 0, """{"type":"thought","signature":"s"}"""); stop(d, 0)
        expectProtocol { stop(d, 0, """{"type":"thought","signature":"s"}""") }
    }

    @Test fun `conflicting completion repeat is rejected`() {
        val d = decoder()
        done(d)
        expectProtocol { d.accept(event("interaction.completed", obj("""{"interaction":{"id":"other"}}"""))) }
    }

    @Test fun `same wire id cannot cross indices even after original ended`() {
        for (ended in listOf(false, true)) {
            val d = decoder()
            start(d, 0, """{"type":"function_call","name":"get_weather","id":"shared","arguments":{}}""")
            if (ended) stop(d, 0)
            expectProtocol { start(d, 1, """{"type":"function_call","name":"other","id":"shared","arguments":{}}""") }
        }
    }

    @Test fun `late conflicting id is rejected`() {
        val d = decoder()
        start(d, 0, """{"type":"function_call","name":"get_weather","id":"one","arguments":{}}""")
        expectProtocol { delta(d, 0, """{"type":"function_call","id":"two"}""") }
    }

    @Test fun `missing index never guesses parallel identity`() {
        val d = decoder()
        start(d, 0, """{"type":"function_call","name":"get_weather"}""")
        start(d, 1, """{"type":"function_call","name":"other"}""")
        expectProtocol { d.accept(event("step.delta", obj("""{"delta":{"type":"arguments_delta","arguments":"{}"}}"""))) }
    }

    @Test fun `delta without start and stop without start fail`() {
        expectProtocol { delta(decoder(), 0, """{"type":"arguments_delta","arguments":"{}"}""") }
        expectProtocol { stop(decoder(), 0) }
    }

    @Test fun `after end delta always fails not only tool name fragments`() {
        for (type in listOf("function_call", "thought", "model_output")) {
            val d = decoder()
            val extra = if (type == "function_call") ",\"name\":\"get_weather\",\"arguments\":{}" else ""
            start(d, 0, "{\"type\":\"$type\"$extra}"); stop(d, 0)
            expectProtocol { delta(d, 0, """{"type":"text","text":"late"}""") }
        }
    }

    @Test fun `complete name snapshots never fuzzy repair or splice conflicting names`() {
        val d = decoder()
        start(d, 0, """{"type":"function_call","name":"get_weather","arguments":{}}""")
        assertTrue(delta(d, 0, """{"type":"function_call","name":"get_weather"}""").all { it !is StreamChunk.ToolCallDelta || it.toolNameDelta.isEmpty() })
        expectProtocol { delta(d, 0, """{"type":"function_call","name":"other"}""") }
    }

    @Test fun `unknown and partial names cannot resolve to registered execution`() {
        for (name in listOf("get_weath", "get_weatherget_weather", "unknown")) {
            val d = decoder()
            start(d, 0, "{\"type\":\"function_call\",\"name\":\"$name\",\"arguments\":{}}")
            expectProtocol { stop(d, 0) }
        }
    }

    @Test fun `late full name id signature and args update existing handler part`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"function_call"}""") + delta(d, 0,
            """{"type":"function_call","name":"get_weather","id":"late","arguments":{},"signature":"sig","localBinding":{"fake":true}}""") + stop(d, 0) + done(d).chunks
        val tool = render(chunks).getTools().single()
        assertEquals("late", tool.toolCallId); assertEquals("get_weather", tool.toolName)
        assertEquals("sig", tool.metadataAs<GoogleInteractionsMetadata>()?.signature)
        assertFalse(tool.isExecuted)
    }

    @Test fun `server tools preserve raw call result positions signatures without client chunks`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"google_search_call","id":"gs","signature":"call-sig","arguments":{"queries":["mountain"]}}""") + stop(d, 0) +
            start(d, 1, """{"type":"thought","signature":"thought-sig"}""") + stop(d, 1) +
            start(d, 2, """{"type":"google_search_result","call_id":"gs","signature":"result-sig","is_error":false,"result":[{"opaque":true}]}""") + stop(d, 2) + done(d).chunks
        assertFalse(chunks.any { it is StreamChunk.ToolCallStart || it is StreamChunk.ToolCallDelta || it is StreamChunk.ToolCallEnd })
        val server = render(chunks).parts.filterIsInstance<UIMessagePart.ServerTool>().single()
        assertEquals(ServerToolStatus.COMPLETED, server.status)
        val meta = server.metadataAs<ServerToolMetadata>()!!
        assertEquals(ServerToolProtocol.GOOGLE_INTERACTIONS, meta.protocol)
        assertEquals(0, meta.callIndex); assertEquals(2, meta.resultIndex)
        assertEquals("call-sig", meta.call!!["signature"]!!.jsonPrimitive.content)
        assertEquals("result-sig", meta.result!!["signature"]!!.jsonPrimitive.content)
        assertEquals("offline-exchange", server.metadataAs<GoogleInteractionsMetadata>()?.exchangeKey)
        assertTrue(render(chunks).getTools().isEmpty())
    }

    @Test fun `server missing id and orphan mismatched or duplicate result fail instead of empty id merges`() {
        expectProtocol { start(decoder(), 0, """{"type":"google_search_call"}""") }
        expectProtocol { start(decoder(), 0, """{"type":"google_search_result","call_id":"unknown"}""") }
        val d = decoder()
        start(d, 0, """{"type":"google_search_call","id":"gs"}""")
        expectProtocol { start(d, 1, """{"type":"url_context_result","call_id":"gs"}""") }
        val duplicate = decoder()
        start(duplicate, 0, """{"type":"google_search_call","id":"gs"}""")
        start(duplicate, 1, """{"type":"google_search_result","call_id":"gs"}""")
        expectProtocol { start(duplicate, 2, """{"type":"google_search_result","call_id":"gs"}""") }
    }

    @Test fun `server call identity conflicts cannot mix raw steps`() {
        val d = decoder()
        start(d, 0, """{"type":"google_search_call","id":"gs"}""")
        expectProtocol { delta(d, 0, """{"type":"google_search_call","id":"different"}""") }
    }

    @Test fun `server failed result remains server only`() {
        val d = decoder()
        val chunks = start(d, 0, """{"type":"url_context_call","id":"url"}""") + stop(d, 0) +
            start(d, 1, """{"type":"url_context_result","call_id":"url","is_error":true}""") + stop(d, 1) + done(d).chunks
        assertEquals(ServerToolStatus.FAILED, render(chunks).parts.filterIsInstance<UIMessagePart.ServerTool>().single().status)
    }

    @Test fun `thought text images citations and usage retain upstream wire facts plus provenance`() {
        val d = decoder()
        val chunks = buildList {
            addAll(start(d, 0, """{"type":"thought"}"""))
            addAll(delta(d, 0, """{"type":"thought_summary","content":{"type":"text","text":"Euclid"}}"""))
            addAll(delta(d, 0, """{"type":"thought_signature","signature":"sig"}""")); addAll(stop(d, 0))
            addAll(start(d, 1, """{"type":"model_output","content":[{"type":"text","text":"Part 1"}]}"""))
            addAll(delta(d, 1, """{"type":"image","data":"AAAA","mime_type":"image/jpeg"}"""))
            addAll(delta(d, 1, """{"type":"text","text":"Part 2"}"""))
            addAll(delta(d, 1, """{"type":"text_annotation_delta","annotations":[{"type":"url_citation","url":"https://example.com","title":"Example"}]}""")); addAll(stop(d, 1))
            addAll(d.accept(event("interaction.completed", obj("""{"interaction":{"status":"completed","usage":{"total_input_tokens":11,"total_cached_tokens":2,"total_output_tokens":90,"total_thought_tokens":245,"total_tokens":346}}}"""))).chunks)
        }
        val message = render(chunks)
        assertEquals("Euclid", (message.parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("sig", message.parts[0].metadataAs<GoogleInteractionsMetadata>()?.signature)
        assertNull(message.parts[0].metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertNull(message.parts[0].metadataAs<ClaudeReasoningMetadata>()?.signature)
        assertEquals(listOf("Part 1", "Part 2"), message.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertEquals("data:image/jpeg;base64,AAAA", message.parts.filterIsInstance<UIMessagePart.Image>().single().url)
        assertEquals(TokenUsage(promptTokens = 11, completionTokens = 335, totalTokens = 346, cachedTokens = 2), message.usage)
        assertEquals(listOf(UIMessageAnnotation.UrlCitation("Example", "https://example.com")), message.annotations)
        assertTrue(message.parts.all { it.metadataAs<GoogleInteractionsMetadata>()?.exchangeKey == "offline-exchange" })
    }

    @Test fun `protocol error abort prevents onClosed success and any later terminal`() {
        val d = decoder()
        expectProtocol { delta(d, 0, """{"type":"text","text":"orphan"}""") }
        assertTrue(d.onClosed().isEmpty())
        expectProtocol { done(d) }
        assertTrue(d.onClosed().isEmpty())
    }

    @Test fun `wire error preserves parsed error and cannot synthesize Finish on closed`() {
        val d = decoder()
        val error = assertThrows(HttpException::class.java) { d.accept(event("error", obj("""{"error":{"message":"Deadline expired","code":"gateway_timeout"}}"""))) }
        assertEquals("Deadline expired", error.message)
        assertTrue(d.onClosed().isEmpty())
    }

    @Test fun `explicit abort and failed completion status never finish`() {
        val d = decoder(); d.abort()
        assertTrue(d.onClosed().isEmpty())
        expectProtocol { done(d) }
        val failure = decoder()
        expectProtocol { failure.accept(event("interaction.completed", obj("""{"interaction":{"status":"failed"}}"""))) }
        assertTrue(failure.onClosed().isEmpty())
    }

    @Test fun `clean EOF without terminal is a protocol failure even with all steps stopped`() {
        val d = decoder()
        start(d, 0, """{"type":"model_output","content":[{"type":"text","text":"partial"}]}"""); stop(d, 0)
        val error = expectProtocol { d.onClosed() }
        assertEquals("interactions_unexpected_eof", error.code)
        assertTrue(d.onClosed().isEmpty())
    }

    @Test fun `clean EOF without terminal and without any content still fails`() {
        expectProtocol { decoder().onClosed() }
    }

    @Test fun `DONE terminal closes active valid steps once and permits subsequent EOF`() {
        val d = decoder()
        start(d, 0, """{"type":"model_output"}"""); delta(d, 0, """{"type":"text","text":"ok"}""")
        val result = d.accept(SseEvent(data = "[DONE]"))
        assertTrue(result.completed)
        assertEquals(1, result.chunks.count { it is StreamChunk.TextEnd })
        assertEquals(1, result.chunks.count { it is StreamChunk.Finish })
        assertTrue(d.onClosed().isEmpty())
    }

    @Test fun `unknown status events remain forwards compatible before completion`() {
        val d = decoder()
        assertTrue(d.accept(event("future.event", obj("""{"opaque":true}"""))).chunks.isEmpty())
        assertTrue(done(d).completed)
    }
}
