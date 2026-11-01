package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.provider.stream.ToolCallLedger
import me.rerere.ai.provider.stream.ToolNameResolver
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

/** Strict response-local step registry. Ended indices and wire IDs remain reserved.
 * Logical client keys come from ToolCallLedger, never the late interaction ID.
 * Arguments deltas are characters, not deduplicated snapshots. Complete snapshots
 * are held separately and checked against the assembled delta JSON at close. */
internal class InteractionsStreamDecoder(
    private val fallbackModel: String? = null,
    private val knownToolNames: Set<String> = emptySet(),
    private val exchangeKey: String = Uuid.random().toString(),
) : StreamChunkDecoder {
    private val steps = linkedMapOf<Int, Step>()
    private val ledger = ToolCallLedger()
    private val wireOwners = mutableMapOf<String, Int>()
    private var interactionId: String? = null
    private var model: String? = null
    private var status: String? = null
    private var completion: JsonObject? = null
    private var finished = false
    private var aborted = false

    fun abort() { aborted = true; ledger.markClosed() }

    override fun accept(event: SseEvent): DecodeResult {
        if (aborted) fail("event_after_abort", "event received after decoder abort")
        try {
            if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)
            val payload = json.parseToJsonElement(event.data).jsonObject
            val type = payload.stringOrNull("event_type") ?: event.event ?: payload.stringOrNull("type")
            if (finished) {
                // Only equal terminal retransmissions and equal recorded starts/stops
                // are idempotent. A delta must never revive an ended index.
                when (type) {
                    "interaction.completed" -> {
                        if (completion != payload) fail("completion_conflict", "conflicting completion retransmission")
                        return DecodeResult(completed = true)
                    }
                    "step.start" -> return DecodeResult(start(payload), completed = true)
                    "step.stop" -> return DecodeResult(stop(payload), completed = true)
                    else -> fail("event_after_finish", "event received after completion")
                }
            }
            return when (type) {
                "interaction.created" -> { capture(payload["interaction"]?.jsonObjectOrNull); DecodeResult() }
                "step.start" -> DecodeResult(start(payload))
                "step.delta" -> DecodeResult(delta(payload))
                "step.stop" -> DecodeResult(stop(payload))
                "interaction.completed" -> {
                    capture(payload["interaction"]?.jsonObjectOrNull)
                    completion = payload
                    DecodeResult(buildList {
                        parseInteractionsUsage(payload["interaction"]?.jsonObjectOrNull?.get("usage")?.jsonObjectOrNull)
                            ?.let { add(StreamChunk.Usage(it)) }
                        addAll(finish())
                    }, completed = true)
                }
                "error" -> throw payload.parseErrorDetail()
                "interaction.failed", "interaction.cancelled", "interaction.aborted" ->
                    fail("interaction_aborted", "interaction aborted by server")
                else -> { payload.stringOrNull("status")?.let { status = it }; DecodeResult() }
            }
        } catch (e: Throwable) {
            abort()
            throw e
        }
    }

    override fun onClosed(): List<StreamChunk> {
        if (aborted || finished) return emptyList()
        abort()
        fail("unexpected_eof", "Interactions stream closed without a completion event")
    }

    private fun capture(value: JsonObject?) {
        if (value == null) return
        value.stringOrNull("id")?.let {
            if (interactionId != null && interactionId != it) fail("interaction_id_conflict", "interaction changed identity")
            interactionId = it
        }
        value.stringOrNull("model")?.let { model = it }
        value.stringOrNull("status")?.let {
            if (it in setOf("failed", "cancelled", "aborted")) fail("interaction_aborted", "interaction status is $it")
            status = it
        }
    }

    private fun index(payload: JsonObject): Int =
        payload["index"]?.jsonPrimitiveOrNull?.intOrNull?.takeIf { it >= 0 }
            ?: fail("missing_step_index", "step requires a nonnegative index")

    private fun start(payload: JsonObject): List<StreamChunk> {
        val index = index(payload)
        val raw = payload["step"]?.jsonObjectOrNull ?: fail("missing_step", "start requires a step")
        steps[index]?.let {
            if (it.initial != raw) fail("step_start_conflict", "index=$index has a conflicting start")
            return emptyList()
        }
        if (finished) fail("step_after_finish", "new step after completion")
        val type = raw.stringOrNull("type") ?: fail("missing_step_type", "start requires a type")
        val state = Step(index, type, raw)
        steps[index] = state
        return when {
            type == "function_call" -> {
                val route = ledger.route(index, wireId(raw, "id"), 0, "interactions-tool-$index")
                state.call = route.call
                reserve(route.call.wireCallId, index)
                route.call.started = true
                state.initialArgs = argumentsSnapshot(raw)
                listOf(StreamChunk.ToolCallStart(route.call.streamKey,
                    toolName = acceptName(state, raw.stringOrNull("name")),
                    metadata = state.metadata(), toolCallId = route.call.displayId))
            }
            type == "thought" -> buildList {
                add(StreamChunk.ReasoningStart(state.reasoningId, state.metadata()))
                raw["summary"]?.jsonArrayOrNull.orEmpty().forEach { item ->
                    item.jsonObjectOrNull?.let { addAll(thoughtDelta(state, it)) }
                }
            }
            type == "model_output" -> raw["content"]?.jsonArrayOrNull.orEmpty().flatMap {
                it.jsonObjectOrNull?.let { contentDelta(state, it) }.orEmpty()
            }
            isInteractionsServerToolCall(type) -> {
                state.serverId = wireId(raw, "id") ?: fail("missing_server_id", "server call requires an id")
                reserve(state.serverId, index)
                listOf(state.serverStart())
            }
            isInteractionsServerToolResult(type) -> {
                val id = wireId(raw, "call_id") ?: fail("missing_server_id", "server result requires call_id")
                val owner = wireOwners[id]?.let(steps::get)
                    ?: fail("orphan_server_result", "server result has no recorded call")
                if (owner.type.removeSuffix("_call") + "_result" != type || owner.serverId != id) {
                    fail("server_result_conflict", "result does not match its server call")
                }
                if (steps.values.any { it !== state && it.serverId == id && isInteractionsServerToolResult(it.type) }) {
                    fail("server_result_reused", "server call has two result indices")
                }
                state.serverId = id
                emptyList()
            }
            else -> emptyList() // Future non-client step types stay non-executable.
        }
    }

    private fun delta(payload: JsonObject): List<StreamChunk> {
        val index = index(payload)
        val state = steps[index] ?: fail("delta_without_start", "delta targets unknown index=$index")
        if (state.ended) fail("delta_after_end", "delta targets ended index=$index")
        val delta = payload["delta"]?.jsonObjectOrNull ?: fail("missing_delta", "step.delta requires delta")
        val type = delta.stringOrNull("type") ?: fail("missing_delta_type", "delta requires type")
        return when {
            state.type == "function_call" -> {
                if (type != "arguments_delta" && type != "function_call") fail("delta_type_conflict", "invalid function delta type")
                buildList {
                    addAll(updateCall(state, delta))
                    updateSignature(state, delta)
                    if (type == "arguments_delta") {
                        val chars = delta.stringOrNull("arguments") ?: fail("invalid_arguments_delta", "arguments delta must be text")
                        state.hasDelta = true
                        state.args.append(chars) // Repeated characters/fragments are legitimate.
                        add(StreamChunk.ToolCallDelta(state.call!!.streamKey, inputDelta = chars, metadata = state.metadata()))
                    } else {
                        argumentsSnapshot(delta)?.let {
                            if (state.finalArgs != null && state.finalArgs != it) fail("arguments_snapshot_conflict", "conflicting final snapshots")
                            state.finalArgs = it
                        }
                        add(StreamChunk.ToolCallDelta(state.call!!.streamKey, metadata = state.metadata()))
                    }
                }
            }
            state.type == "thought" -> when (type) {
                "thought_summary" -> thoughtDelta(state, delta["content"]?.jsonObjectOrNull
                    ?: fail("invalid_thought_delta", "thought summary requires content"))
                "thought_signature" -> { updateSignature(state, delta); emptyList() }
                else -> fail("delta_type_conflict", "invalid thought delta type")
            }
            state.type == "model_output" -> when (type) {
                "text", "image" -> contentDelta(state, delta)
                "text_annotation_delta" -> delta["annotations"].toInteractionsAnnotations()
                    .let { if (it.isEmpty()) emptyList() else listOf(StreamChunk.Annotations(it)) }
                else -> fail("delta_type_conflict", "invalid output delta type")
            }
            isInteractionsServerToolCall(state.type) || isInteractionsServerToolResult(state.type) -> {
                if (type != state.type) fail("delta_type_conflict", "server delta changed step type")
                val idField = if (isInteractionsServerToolCall(type)) "id" else "call_id"
                wireId(delta, idField)?.let { if (it != state.serverId) fail("server_id_conflict", "server delta changed identity") }
                updateSignature(state, delta)
                state.raw = JsonObject(state.raw + delta)
                if (isInteractionsServerToolCall(type)) listOf(state.serverStart()) else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun stop(payload: JsonObject): List<StreamChunk> {
        val index = index(payload)
        val state = steps[index] ?: fail("stop_without_start", "stop targets unknown index=$index")
        if (state.stop != null) {
            if (state.stop != payload) fail("step_stop_conflict", "conflicting stop at index=$index")
            return emptyList()
        }
        if (state.ended) fail("stop_after_finish", "unrecorded stop after implicit end")
        val chunks = buildList {
            payload["step"]?.jsonObjectOrNull?.let { final ->
                final.stringOrNull("type")?.let { if (it != state.type) fail("step_stop_conflict", "stop changed type") }
                if (state.type == "function_call") {
                    addAll(updateCall(state, final))
                    argumentsSnapshot(final)?.let {
                        if (state.finalArgs != null && state.finalArgs != it) fail("arguments_snapshot_conflict", "stop disagrees with final arguments snapshot")
                        state.finalArgs = it
                    }
                } else if (isInteractionsServerToolCall(state.type) || isInteractionsServerToolResult(state.type)) {
                    val field = if (isInteractionsServerToolCall(state.type)) "id" else "call_id"
                    wireId(final, field)?.let { if (it != state.serverId) fail("server_id_conflict", "stop changed server identity") }
                    state.raw = JsonObject(state.raw + final)
                }
                updateSignature(state, final)
            }
            addAll(closeStep(state))
        }
        state.stop = payload
        return chunks
    }

    private fun updateCall(state: Step, raw: JsonObject): List<StreamChunk> {
        val route = ledger.route(state.index, wireId(raw, "id"), 0, "interactions-tool-${state.index}")
        reserve(route.call.wireCallId, state.index)
        return buildList {
            route.assignedId?.let { add(StreamChunk.ToolCallIdAssigned(route.call.streamKey, it)) }
            val name = acceptName(state, raw.stringOrNull("name"))
            if (name.isNotEmpty()) add(StreamChunk.ToolCallDelta(route.call.streamKey, toolNameDelta = name))
        }
    }

    private fun acceptName(state: Step, name: String?): String {
        if (name.isNullOrEmpty()) return ""
        // The function_call name field is a complete name, not a name delta.
        if (state.wireName != null) {
            if (state.wireName != name) fail("tool_name_conflict", "function name snapshot changed")
            return ""
        }
        state.wireName = name
        val part = state.resolver.accept(name)
        state.emittedName += part
        return part
    }

    private fun updateSignature(state: Step, raw: JsonObject) {
        raw.stringOrNull("signature")?.takeIf { it.isNotEmpty() }?.let {
            if (state.signature != null && state.signature != it) fail("signature_conflict", "step signature changed")
            state.signature = it
        }
    }

    private fun closeStep(state: Step): List<StreamChunk> {
        if (state.ended) return emptyList()
        val chunks = when {
            state.type == "function_call" -> buildList {
                val call = state.call!!
                val tail = state.resolver.finish()
                state.emittedName += tail
                if (state.emittedName !in knownToolNames) fail("tool_name_unregistered", "function name is not in the final wire declaration table")
                val assembled = if (state.hasDelta) parseArguments(state.args.toString()) else null
                // Documented streaming start uses arguments:{} as an opening
                // placeholder before populated arguments_delta (fixed U fixture).
                // With no deltas it is a valid complete empty call. A FINAL {}
                // remains authoritative and must still agree with assembled deltas.
                val initial = state.initialArgs?.takeUnless { state.hasDelta && it.isEmpty() }
                val snapshots = listOfNotNull(initial, state.finalArgs)
                if (snapshots.distinct().size > 1 || (assembled != null && snapshots.any { it != assembled })) {
                    fail("arguments_snapshot_conflict", "complete arguments snapshot disagrees with delta JSON")
                }
                val complete = assembled ?: snapshots.lastOrNull()
                    ?: fail("missing_arguments", "function call has no complete arguments")
                add(StreamChunk.ToolCallDelta(call.streamKey, toolNameDelta = tail,
                    inputDelta = if (state.hasDelta) "" else complete.toString(), metadata = state.metadata()))
                add(StreamChunk.ToolCallEnd(call.streamKey))
                call.ended = true
            }
            state.type == "thought" -> listOf(StreamChunk.ReasoningEnd(state.reasoningId, state.metadata()))
            state.type == "model_output" -> state.closeText()
            isInteractionsServerToolCall(state.type) -> listOf(state.serverStart())
            isInteractionsServerToolResult(state.type) -> listOf(StreamChunk.ServerToolEnd(
                id = state.serverStreamKey(), output = state.raw["result"], status = state.raw.interactionsServerToolResultStatus(),
                metadata = JsonObject(ServerToolMetadata(protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                    result = state.raw, resultIndex = state.index).toMetadata() + state.metadata())))
            else -> emptyList()
        }
        state.ended = true
        return chunks
    }

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        val chunks = buildList {
            steps.values.forEach { addAll(closeStep(it)) }
            add(StreamChunk.Finish(status, interactionId, model ?: fallbackModel))
        }
        finished = true
        ledger.markClosed()
        return chunks
    }

    private fun contentDelta(state: Step, content: JsonObject): List<StreamChunk> = when (content.stringOrNull("type")) {
        "text" -> buildList {
            val text = content.stringOrNull("text").orEmpty()
            if (text.isNotEmpty()) {
                val id = state.textId ?: state.nextPart("text").also {
                    state.textId = it
                    add(StreamChunk.TextStart(it, state.metadata(state.partSequence)))
                }
                add(StreamChunk.TextDelta(id, text))
            }
            content["annotations"].toInteractionsAnnotations().takeIf { it.isNotEmpty() }
                ?.let { add(StreamChunk.Annotations(it)) }
        }
        "image" -> buildList {
            val data = content.stringOrNull("data") ?: return@buildList
            addAll(state.closeText())
            val id = state.nextPart("image")
            add(StreamChunk.ImageStart(id, content.stringOrNull("mime_type") ?: "image/png", state.metadata(state.partSequence)))
            add(StreamChunk.ImageDelta(id, data)); add(StreamChunk.ImageEnd(id))
        }
        else -> emptyList()
    }

    private fun thoughtDelta(state: Step, content: JsonObject): List<StreamChunk> {
        if (content.stringOrNull("type") != "text") return emptyList()
        return listOf(StreamChunk.ReasoningDelta(state.reasoningId, content.stringOrNull("text").orEmpty()))
    }

    private fun argumentsSnapshot(raw: JsonObject): JsonObject? {
        val value = raw["arguments"] ?: return null
        return value as? JsonObject ?: fail("invalid_arguments_snapshot", "complete arguments must be an object")
    }

    private fun parseArguments(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: fail("invalid_arguments", "arguments must decode to an object")
    } catch (e: ToolStreamProtocolException) { throw e }
    catch (_: Exception) { fail("invalid_arguments", "arguments deltas do not form complete JSON") }

    private fun reserve(id: String?, index: Int) {
        if (id == null) return
        val previous = wireOwners.putIfAbsent(id, index)
        if (previous != null && previous != index) fail("id_reused_across_index", "wire call id reused across indices")
    }

    private fun wireId(raw: JsonObject, field: String): String? = raw.stringOrNull(field)?.takeIf { it.isNotBlank() }

    private inner class Step(val index: Int, val type: String, val initial: JsonObject) {
        var raw = initial
        var stop: JsonObject? = null
        var ended = false
        var signature = initial.stringOrNull("signature")?.takeIf { it.isNotEmpty() }
        var call: ToolCallLedger.Call? = null
        var serverId: String? = null
        var initialArgs: JsonObject? = null
        var finalArgs: JsonObject? = null
        var hasDelta = false
        val args = StringBuilder()
        val resolver = ToolNameResolver(knownToolNames)
        var wireName: String? = null
        var emittedName = ""
        var textId: String? = null
        var partSequence = 0
        val reasoningId = "interactions-step-$index:thought"
        fun nextPart(kind: String) = "interactions-step-$index:$kind-${++partSequence}"
        fun metadata(contentIndex: Int? = null) = interactionsSignatureMetadata(signature, index, contentIndex, exchangeKey)
        fun closeText(): List<StreamChunk> = textId?.let {
            textId = null; listOf(StreamChunk.TextEnd(it))
        }.orEmpty()
        // Server chunks have no dual-ID field in the shared schema. Qualify their
        // UI key by response to avoid the legacy handler's historical ID fallback;
        // exact external IDs remain authoritative in raw call/result metadata.
        fun serverStreamKey(): String {
            val owner = wireOwners[serverId!!] ?: fail("orphan_server_result", "server routing key has no owner")
            return "interactions-$exchangeKey:server-$owner"
        }
        fun serverStart() = StreamChunk.ServerToolStart(serverStreamKey(), type.removeSuffix("_call"), raw["arguments"],
            JsonObject(ServerToolMetadata(protocol = ServerToolProtocol.GOOGLE_INTERACTIONS, call = raw, callIndex = index).toMetadata() + metadata()))
    }

    private fun fail(code: String, message: String): Nothing =
        throw ToolStreamProtocolException("interactions_$code", message)
}
