package me.rerere.rikkihub.data.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.utils.generateUnifiedDiff
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ACP transcript → chat message parts.
 *
 * Tool calls become [UIMessagePart.Tool] named `acp_<name|kind>` (agy sends a private `name`
 * such as `run_command`; standard agents only a `kind`). Everything ACP-specific stays in
 * `metadata.acp`: kind, title, status, locations and the non-text content (diffs, terminals);
 * its presence marks a part as belonging to a CLI session.
 */
object AcpParts {
    const val TOOL_PREFIX = "acp_"
    const val PLAN_TOOL = "acp_plan"
    const val ASK_QUESTION = "acp_ask_question"
    const val META_KEY = "acp"
    private const val META_PENDING_OUTPUT = "_pendingOutput"
    private const val META_PLACEHOLDER = "_placeholder"

    fun isAcpTool(part: UIMessagePart.Tool): Boolean = part.metadata?.containsKey(META_KEY) == true

    internal fun hasPlaceholderOutput(part: UIMessagePart.Tool): Boolean =
        (part.metadata?.obj(META_KEY)?.get(META_PLACEHOLDER) as? JsonPrimitive)?.booleanOrNull == true

    fun status(part: UIMessagePart.Tool): String? = part.metadata?.obj(META_KEY)?.str("status")

    /** A `!command` card: run by rikkihub itself, never seen by the agent. */
    fun isLocalCommand(part: UIMessagePart): Boolean =
        part is UIMessagePart.Tool && (part.metadata?.obj(META_KEY)?.get("local") as? JsonPrimitive)?.booleanOrNull == true

    fun titleOf(tool: UIMessagePart.Tool): String {
        val title = tool.metadata?.obj(META_KEY)?.str("title")?.replace("\n", " ")?.trim()
        return title?.takeIf { it.isNotEmpty() }
            ?: tool.toolName.removePrefix(TOOL_PREFIX).replace('_', ' ')
    }

    /** Unified diffs of the `diff` content items (`{path, oldText, newText}`). */
    fun unifiedDiffOf(tool: UIMessagePart.Tool): String? {
        val content = tool.metadata?.obj(META_KEY)?.arr("content") ?: return null
        val diffs = content.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") != "diff") return@mapNotNull null
            val path = obj.str("path") ?: return@mapNotNull null
            generateUnifiedDiff(obj.str("oldText").orEmpty(), obj.str("newText").orEmpty(), path)
        }
        return diffs.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Folds one `session/update` of the current turn into the assistant [message]. */
    fun fold(message: UIMessage, update: JsonObject, now: Instant = Clock.System.now()): UIMessage =
        when (update.str("sessionUpdate")) {
            "agent_message_chunk" -> update.obj("content")?.textOrNull()?.let { text ->
                message.copy(parts = appendText(closeReasoning(message.parts, now), text))
            } ?: message
            "agent_thought_chunk" -> update.obj("content")?.textOrNull()?.let { text ->
                message.copy(parts = appendReasoning(message.parts, text, now))
            } ?: message
            "tool_call" -> message.copy(parts = upsertTool(closeReasoning(message.parts, now), update, now, create = true))
            "tool_call_update" -> message.copy(parts = upsertTool(message.parts, update, now, create = false))
            "plan" -> message.copy(parts = upsertPlan(closeReasoning(message.parts, now), update))
            else -> message
        }

    /**
     * End of turn: every ACP tool without output gets one, so no card stays "running" or is
     * later taken for an interrupted call. An [ASK_QUESTION] card gets an empty output: it is
     * answered by the user's next message. The others show [reason] (e.g. cancelled).
     */
    fun finishTools(message: UIMessage, reason: String, now: Instant = Clock.System.now()): UIMessage =
        message.copy(parts = closeReasoning(message.parts, now).map { part ->
            if (part is UIMessagePart.Tool && isAcpTool(part)) {
                val cleanedMeta = stripTransientAcpMeta(part.metadata)
                if (part.output.isEmpty()) {
                    val staged = pendingOutputOf(part)
                    val text = when {
                        part.toolName == ASK_QUESTION -> ""
                        staged.isNotBlank() -> "$staged\n[${reason.removeSurrounding("[", "]")}]"
                        else -> reason
                    }
                    part.copy(
                        output = listOf(UIMessagePart.Text(text)),
                        metadata = cleanedMeta,
                        finishedAt = part.finishedAt ?: now,
                    )
                } else if (cleanedMeta !== part.metadata) {
                    part.copy(metadata = cleanedMeta)
                } else {
                    part
                }
            } else {
                part
            }
        })

    /**
     * Stale CLI recovery (process killed before [finishTools] could run): closes unfinished ACP
     * tools without faking a denied approval state or a post-restart duration, and seals open
     * reasoning blocks within the message's known activity window. Returns [message] unchanged
     * when nothing needed closing.
     */
    fun finishInterrupted(
        message: UIMessage,
        reason: String,
        now: Instant = Clock.System.now(),
        closeOpenReasoning: Boolean = true,
    ): UIMessage {
        var latestActivity: Instant? = null
        for (part in message.parts) {
            val candidate = when (part) {
                is UIMessagePart.Tool -> part.finishedAt ?: part.startedAt
                is UIMessagePart.Reasoning -> part.finishedAt ?: part.createdAt
                else -> null
            }
            if (candidate != null && (latestActivity == null || candidate > latestActivity)) {
                latestActivity = candidate
            }
        }
        var changed = false
        val updatedParts = message.parts.map { part ->
            when {
                part is UIMessagePart.Reasoning && part.finishedAt == null && closeOpenReasoning -> {
                    changed = true
                    val sealed = (latestActivity?.coerceAtMost(now) ?: part.createdAt)
                        .coerceAtLeast(part.createdAt)
                    part.copy(finishedAt = sealed)
                }
                part is UIMessagePart.Tool && isAcpTool(part) && !part.isExecuted -> {
                    changed = true
                    val staged = pendingOutputOf(part)
                    if (part.toolName == ASK_QUESTION) {
                        part.copy(
                            output = listOf(UIMessagePart.Text("")),
                            metadata = stripTransientAcpMeta(part.metadata),
                        )
                    } else {
                        val text = if (staged.isNotBlank()) "$staged\n[${reason.removeSurrounding("[", "]")}]" else reason
                        part.copy(
                            output = listOf(UIMessagePart.Text(text)),
                            metadata = stripTransientAcpMeta(part.metadata, status = "interrupted"),
                        )
                    }
                }
                part is UIMessagePart.Tool && isAcpTool(part) -> {
                    val cleanedMeta = stripTransientAcpMeta(part.metadata)
                    if (cleanedMeta !== part.metadata) {
                        changed = true
                        part.copy(metadata = cleanedMeta)
                    } else {
                        part
                    }
                }
                else -> part
            }
        }
        return if (changed) message.copy(parts = updatedParts) else message
    }

    private fun pendingOutputOf(part: UIMessagePart.Tool): String =
        part.metadata?.obj(META_KEY)?.str(META_PENDING_OUTPUT).orEmpty()

    private fun stripTransientAcpMeta(metadata: JsonObject?, status: String? = null): JsonObject? {
        val previous = metadata?.obj(META_KEY) ?: return metadata
        val hasTransient = META_PENDING_OUTPUT in previous || META_PLACEHOLDER in previous
        val statusUnchanged = status == null || previous.str("status") == status
        if (!hasTransient && statusUnchanged) return metadata
        val updated = JsonObject(buildMap {
            previous.forEach { (key, value) ->
                if (key != META_PENDING_OUTPUT && key != META_PLACEHOLDER) put(key, value)
            }
            if (status != null) put("status", JsonPrimitive(status))
        })
        return JsonObject(metadata.orEmpty() + (META_KEY to updated))
    }

    private fun JsonObject.textOrNull(): String? = if (str("type") == "text") str("text") else null

    private fun closeReasoning(parts: List<UIMessagePart>, now: Instant): List<UIMessagePart> = parts.map { part ->
        if (part is UIMessagePart.Reasoning && part.finishedAt == null) part.copy(finishedAt = now) else part
    }

    private fun appendText(parts: List<UIMessagePart>, text: String): List<UIMessagePart> {
        val last = parts.lastOrNull()
        return if (last is UIMessagePart.Text) parts.dropLast(1) + last.copy(text = last.text + text)
        else parts + UIMessagePart.Text(text)
    }

    private fun appendReasoning(parts: List<UIMessagePart>, text: String, now: Instant): List<UIMessagePart> {
        val last = parts.lastOrNull()
        return if (last is UIMessagePart.Reasoning && last.finishedAt == null) {
            parts.dropLast(1) + last.copy(reasoning = last.reasoning + text)
        } else {
            parts + UIMessagePart.Reasoning(reasoning = text, createdAt = now, finishedAt = null)
        }
    }

    private fun upsertTool(parts: List<UIMessagePart>, update: JsonObject, now: Instant, create: Boolean): List<UIMessagePart> {
        val id = update.str("toolCallId") ?: return parts
        val index = parts.indexOfFirst { it is UIMessagePart.Tool && it.toolCallId == id }
        // Updates for calls of an earlier turn (agy finishes a question when it is answered) are not ours.
        if (index < 0 && !create) return parts
        val existing = parts.getOrNull(index) as? UIMessagePart.Tool
        val previous = existing?.metadata?.obj(META_KEY) ?: JsonObject(emptyMap())
        val prevStaged = previous.str(META_PENDING_OUTPUT).orEmpty()
        val wasPlaceholder = (previous[META_PLACEHOLDER] as? JsonPrimitive)?.booleanOrNull == true
        val status = update.str("status") ?: previous.str("status") ?: "pending"
        val finished = status == "completed" || status == "failed"
        val incoming = outputText(update)
        val initialName = update.str("name") ?: update.str("kind") ?: previous.str("name") ?: previous.str("kind") ?: "other"
        val input = (update["rawInput"] ?: update["args"])?.let { JsonInstant.encodeToString(JsonElement.serializer(), it) }
        val base = existing ?: UIMessagePart.Tool(toolCallId = id, toolName = TOOL_PREFIX + initialName, input = input ?: "{}")

        val nextOutput: List<UIMessagePart>
        var nextStaged: String? = null
        var nextPlaceholder = false
        if (!finished) {
            nextOutput = base.output
            if (base.output.isEmpty()) {
                nextStaged = incoming.ifBlank { prevStaged }.takeIf { it.isNotBlank() }
            }
        } else {
            when {
                base.output.isEmpty() -> {
                    val resolved = incoming.ifBlank { prevStaged }
                    if (resolved.isNotBlank()) {
                        nextOutput = listOf(UIMessagePart.Text(resolved))
                    } else {
                        nextOutput = listOf(UIMessagePart.Text("[$status]"))
                        nextPlaceholder = true
                    }
                }
                wasPlaceholder && base.approvalState !is ToolApprovalState.Denied -> {
                    if (incoming.isNotBlank()) {
                        nextOutput = listOf(UIMessagePart.Text(incoming))
                    } else {
                        nextOutput = listOf(UIMessagePart.Text("[$status]"))
                        nextPlaceholder = true
                    }
                }
                else -> nextOutput = base.output
            }
        }

        val acp = JsonObject(buildMap {
            previous.forEach { (key, value) ->
                if (key != META_PENDING_OUTPUT && key != META_PLACEHOLDER) put(key, value)
            }
            update.str("name")?.let { put("name", JsonPrimitive(it)) }
            update.str("kind")?.let { put("kind", JsonPrimitive(it)) }
            update.str("title")?.let { put("title", JsonPrimitive(it)) }
            put("status", JsonPrimitive(status))
            (update["locations"] as? JsonArray)?.let { put("locations", it) }
            (update["content"] as? JsonArray)?.let { content ->
                val rich = content.filter { (it as? JsonObject)?.str("type") != "content" }
                if (rich.isNotEmpty()) put("content", JsonArray(rich))
            }
            nextStaged?.let { put(META_PENDING_OUTPUT, JsonPrimitive(it)) }
            if (nextPlaceholder) put(META_PLACEHOLDER, JsonPrimitive(true))
        })
        val name = acp.str("name") ?: acp.str("kind") ?: "other"
        val tool = base.copy(
            toolName = TOOL_PREFIX + name,
            input = input ?: base.input,
            metadata = JsonObject(base.metadata.orEmpty() + (META_KEY to acp)),
            startedAt = base.startedAt ?: now.takeIf { status == "in_progress" || finished },
            finishedAt = if (finished) base.finishedAt ?: now else base.finishedAt,
            output = nextOutput,
        )
        return if (existing == null) parts + tool else parts.toMutableList().also { it[index] = tool }
    }

    private fun outputText(update: JsonObject): String {
        val content = update.arr("content").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") == "content") obj.obj("content")?.textOrNull()?.takeIf { it.isNotBlank() } else null
        }
        if (content.isNotEmpty()) return content.joinToString("\n")
        return rawOutputText(update["rawOutput"])
    }

    private fun rawOutputText(raw: JsonElement?): String = when (raw) {
        is JsonObject -> {
            val primary = raw.nonBlankStr("output") ?: raw.nonBlankStr("stdout")
            val stderr = raw.nonBlankStr("stderr")
            when {
                primary != null && stderr != null -> when {
                    stderr in primary -> primary
                    primary.endsWith("\n") -> "$primary$stderr"
                    else -> "$primary\n$stderr"
                }
                primary != null -> primary
                stderr != null -> stderr
                else -> raw.nonBlankStr("error")
                    ?: raw.obj("error")?.nonBlankStr("message")
                    ?: raw.nonBlankStr("message")
                    ?: raw.nonBlankStr("text")
                    ?: raw.nonBlankStr("result")
                    ?: raw.nonBlankStr("content")
                    ?: textBlocksOf(raw["content"] as? JsonArray)
            }
        }
        is JsonArray -> textBlocksOf(raw)
        is JsonPrimitive -> raw.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }.orEmpty()
        else -> ""
    }

    private fun JsonObject.nonBlankStr(key: String): String? = str(key)?.takeIf { it.isNotBlank() }

    private fun textBlocksOf(array: JsonArray?): String =
        array.orEmpty().mapNotNull { item ->
            (item as? JsonObject)?.textOrNull()?.takeIf { it.isNotBlank() }
        }.joinToString("\n")

    private fun upsertPlan(parts: List<UIMessagePart>, update: JsonObject): List<UIMessagePart> {
        val entries = update.arr("entries").mapNotNull { it as? JsonObject }
        val text = entries.joinToString("\n") { entry ->
            val mark = when (entry.str("status")) {
                "completed" -> "[x]"
                "in_progress" -> "[~]"
                else -> "[ ]"
            }
            "$mark ${entry.str("content").orEmpty()}"
        }
        val plan = UIMessagePart.Tool(
            toolCallId = PLAN_TOOL,
            toolName = PLAN_TOOL,
            input = JsonInstant.encodeToString(JsonElement.serializer(), JsonArray(entries)),
            output = listOf(UIMessagePart.Text(text)),
            metadata = buildJsonObject { put(META_KEY, buildJsonObject { put("kind", "plan") }) },
        )
        val index = parts.indexOfFirst { it is UIMessagePart.Tool && it.toolCallId == PLAN_TOOL }
        return if (index < 0) parts + plan else parts.toMutableList().also { it[index] = plan }
    }
}

/**
 * Resolves the display [Model] for a message: live provider lookup first, then [UIMessage.snapshotModel],
 * falling back to [UIMessage.modelSnapshot] with [Uuid.NIL] when `modelId` is null (e.g. CLI messages).
 */
fun UIMessage.displayModelOrNull(providers: List<ProviderSetting>): Model? =
    modelId?.let { providers.findModelById(it) }
        ?: snapshotModel()
        ?: modelSnapshot?.let { Model(modelId = it.modelId, displayName = it.displayName, id = Uuid.NIL) }
