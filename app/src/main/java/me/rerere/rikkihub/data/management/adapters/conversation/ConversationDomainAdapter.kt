package me.rerere.rikkihub.data.management.adapters.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.management.adapters.reject
import me.rerere.rikkihub.data.management.adapters.rejectBusy
import me.rerere.rikkihub.data.management.adapters.rejectInvalid
import me.rerere.rikkihub.data.management.adapters.rejectNotFound
import me.rerere.rikkihub.data.management.adapters.rejectReserved
import me.rerere.rikkihub.data.management.common.DiffLine
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSet
import me.rerere.rikkihub.data.management.common.ReadSetEntry
import me.rerere.rikkihub.data.management.common.RetentionNote
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.coordinator.ManagementDomainAdapter
import me.rerere.rikkihub.data.management.coordinator.PlanContext
import me.rerere.rikkihub.data.management.coordinator.PlannedMutation
import me.rerere.rikkihub.data.management.ports.ConversationCommandOp
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode
import me.rerere.rikkihub.data.mutation.ConversationCommandApplier
import me.rerere.rikkihub.data.mutation.ConversationHostIds
import me.rerere.rikkihub.data.mutation.ConversationMutationCommand
import kotlin.uuid.Uuid

/** Read-port collections owned by the Room conversation projection. */
object ConversationCollections {
    /** Lightweight row projection (metadata + references, no message bodies). */
    const val ROWS = "room.conversations"

    /** Full ordinary tree projection: nodes/candidates with plain text only. */
    const val TREE = "room.conversationTree"
}

/** Typed management command for one ordinary conversation. */
@Serializable
data class ConversationManagementCommand(
    /** Null only for [ConversationMutationCommand.CreateOrdinaryConversation]; the host allocates the ID. */
    val conversationId: String? = null,
    val command: ConversationMutationCommand,
) : ManagementCommand {
    override val domain: ManagementDomain get() = ManagementDomain.CONVERSATION
    override fun affectedRefs(): List<ObjectRef> = listOfNotNull(conversationId?.let { ObjectRef(domain, it) })
    override fun risk(): RiskLevel = when (val c = command) {
        is ConversationMutationCommand.DeleteCandidate,
        is ConversationMutationCommand.PruneNode,
        ConversationMutationCommand.SoftDelete -> RiskLevel.HIGH
        is ConversationMutationCommand.PatchMetadata -> if (c.moveToAssistantId != null) RiskLevel.HIGH else RiskLevel.MEDIUM
        else -> RiskLevel.MEDIUM
    }
}

/**
 * Conversation domain planner. It only reads ordinary projections through the read
 * port, validates the command against the latest state and freezes host IDs; all
 * writes happen later through [ConversationCommandOp] → ChatService.
 */
class ConversationDomainAdapter : ManagementDomainAdapter {
    override val domain = ManagementDomain.CONVERSATION
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun plan(ctx: PlanContext, command: ManagementCommand): PlannedMutation {
        require(command is ConversationManagementCommand)
        val cmd = command.command
        val entries = mutableListOf<ReadSetEntry>()
        val diff = mutableListOf<DiffLine>()
        val assigned = linkedMapOf<String, String>()
        val settings = ctx.read.atomicSettingsSnapshot()
        val ordinary = settings.settings.assistants.map { it.id.toString() }
            .filterNot { AssistantRegistry.isReservedAssistantId(it) }.toSet()

        fun requireOrdinaryAssistant(id: String) {
            if (AssistantRegistry.isReservedAssistantId(id)) rejectReserved("Reserved assistant identity")
            if (id !in ordinary) rejectNotFound("Ordinary assistant unavailable")
            val ref = ObjectRef(ManagementDomain.ASSISTANT, id)
            settings.objects["assistant:$id"]?.let { entries += ReadSetEntry.Object(ref, it) }
                ?: rejectNotFound("Ordinary assistant unavailable")
        }

        fun requireReferences(modes: Set<String>?, books: Set<String>?) {
            val knownModes = settings.settings.modeInjections.map { it.id.toString() }.toSet()
            val knownBooks = settings.settings.lorebooks.map { it.id.toString() }.toSet()
            if (modes != null && !knownModes.containsAll(modes)) rejectNotFound("Unknown mode injection")
            if (books != null && !knownBooks.containsAll(books)) rejectNotFound("Unknown lorebook")
        }

        if (cmd is ConversationMutationCommand.CreateOrdinaryConversation) {
            if (command.conversationId != null) rejectInvalid("New conversation IDs are host allocated")
            requireOrdinaryAssistant(cmd.assistantId)
            requireReferences(cmd.modeInjectionIds, cmd.lorebookIds)
            if (cmd.initialPlainMessages.size > ConversationCommandApplier.MAX_INITIAL_MESSAGES)
                rejectInvalid("Too many initial messages")
            if (cmd.initialPlainMessages.any { it.isEmpty() || it.length > ConversationCommandApplier.MAX_TEXT_CHARS })
                rejectInvalid("Initial message is empty or too long")
            val id = ctx.hostIds.newObjectId().also { assigned["conversation"] = it }
            val ids = ConversationHostIds(
                conversationId = id,
                nodeIds = cmd.initialPlainMessages.map { ctx.hostIds.newEntryId("node") },
                messageIds = cmd.initialPlainMessages.map { ctx.hostIds.newEntryId("message") },
                atMillis = ctx.nowMillis,
            )
            val ref = ObjectRef(domain, id)
            diff += DiffLine("conversation[$id].assistantId", null, cmd.assistantId)
            diff += DiffLine("conversation[$id].title", null, cmd.title)
            cmd.initialPlainMessages.forEachIndexed { i, text -> diff += DiffLine("conversation[$id].message[$i]", null, text) }
            return planned(entries, listOf(ConversationCommandOp(id, null, cmd, ids, listOf(ref))), diff,
                command.risk(), listOf(ref), assigned)
        }

        val id = command.conversationId ?: rejectInvalid("conversationId required")
        runCatching { Uuid.parse(id) }.getOrElse { rejectInvalid("Invalid conversation id") }
        val view = ctx.read.readObjectJson(ConversationCollections.TREE, id) ?: rejectNotFound("Conversation unavailable")
        val deleted = view["deleted"]?.jsonPrimitive?.booleanOrNull == true
        val assistantId = view.getValue("assistantId").jsonPrimitive.content
        if (AssistantRegistry.isReservedAssistantId(assistantId)) rejectReserved("Creator history is not an ordinary conversation")
        if (cmd == ConversationMutationCommand.Restore) {
            if (!deleted) reject(ProposalErrorCode.INVALID_VALUE, "Conversation is not deleted")
        } else if (deleted) rejectNotFound("Conversation unavailable")
        view["busy"]?.jsonPrimitive?.contentOrNull?.let { rejectBusy("Conversation is busy: $it") }
        val ref = ObjectRef(domain, id)
        val token = ctx.read.objectToken(ConversationCollections.ROWS, id) ?: rejectNotFound("Conversation unavailable")
        entries += ReadSetEntry.Object(ref, token)
        val nodes = view["nodes"]?.jsonArray.orEmpty().map { it.jsonObject }
        fun node(nodeId: String): JsonObject = nodes.firstOrNull { it["id"]?.jsonPrimitive?.content == nodeId }
            ?: rejectNotFound("Unknown node")
        fun candidates(node: JsonObject): List<JsonObject> = node["candidates"]?.jsonArray.orEmpty().map { it.jsonObject }
        fun text(candidate: JsonObject): String? = candidate["text"]?.jsonPrimitive?.contentOrNull

        var ids = ConversationHostIds(atMillis = ctx.nowMillis)
        when (cmd) {
            is ConversationMutationCommand.PatchMetadata -> {
                cmd.moveToAssistantId?.let(::requireOrdinaryAssistant)
                requireReferences(cmd.modeInjectionIds, cmd.lorebookIds)
                cmd.title?.let { diff += DiffLine("conversation[$id].title", view["title"]?.jsonPrimitive?.contentOrNull, it) }
                cmd.isPinned?.let { diff += DiffLine("conversation[$id].isPinned", view["isPinned"].toString(), it.toString()) }
                cmd.moveToAssistantId?.let { diff += DiffLine("conversation[$id].assistantId", assistantId, it) }
                if (cmd.clearFolder || cmd.folderId != null)
                    diff += DiffLine("conversation[$id].folderId", view["folderId"]?.jsonPrimitive?.contentOrNull, cmd.folderId)
                if (cmd.clearCustomSystemPrompt || cmd.customSystemPrompt != null)
                    diff += DiffLine("conversation[$id].customSystemPrompt",
                        view["customSystemPrompt"]?.jsonPrimitive?.contentOrNull, cmd.customSystemPrompt)
                if (cmd.clearWorkspaceCwd || cmd.workspaceCwd != null)
                    diff += DiffLine("conversation[$id].workspaceCwd", view["workspaceCwd"]?.jsonPrimitive?.contentOrNull, cmd.workspaceCwd)
                cmd.modeInjectionIds?.let { diff += DiffLine("conversation[$id].modeInjectionIds", view["modeInjectionIds"].toString(), it.sorted().toString()) }
                cmd.lorebookIds?.let { diff += DiffLine("conversation[$id].lorebookIds", view["lorebookIds"].toString(), it.sorted().toString()) }
                if (diff.isEmpty()) rejectInvalid("Patch has no effect")
            }
            is ConversationMutationCommand.AppendPlainMessage -> {
                val textParts = cmd.parts.map { (it as? ConversationMutationCommand.PlainPart.Text)?.text
                    ?: rejectInvalid("Only plain text parts are accepted") }
                val text = textParts.joinToString("\n")
                if (text.isEmpty() || text.length > ConversationCommandApplier.MAX_TEXT_CHARS) rejectInvalid("Text is empty or too long")
                ids = ids.copy(nodeIds = listOf(ctx.hostIds.newEntryId("node")),
                    messageIds = listOf(ctx.hostIds.newEntryId("message")))
                diff += DiffLine("conversation[$id].append(${cmd.role})", null, text)
            }
            is ConversationMutationCommand.EditPlainTextAsNewCandidate -> {
                val n = node(cmd.nodeId)
                if (cmd.text.isEmpty() || cmd.text.length > ConversationCommandApplier.MAX_TEXT_CHARS) rejectInvalid("Text is empty or too long")
                val selected = candidates(n).getOrNull(n["selectIndex"]?.jsonPrimitive?.intOrNull ?: 0)
                ids = ids.copy(messageIds = listOf(ctx.hostIds.newEntryId("message")))
                diff += DiffLine("conversation[$id].node[${cmd.nodeId}].newCandidate", selected?.let(::text), cmd.text)
            }
            is ConversationMutationCommand.SelectCandidate -> {
                val n = node(cmd.nodeId)
                val all = candidates(n)
                if (cmd.selectIndex !in all.indices) rejectInvalid("Candidate index out of range")
                diff += DiffLine("conversation[$id].node[${cmd.nodeId}].selectIndex",
                    n["selectIndex"]?.jsonPrimitive?.content, cmd.selectIndex.toString())
            }
            is ConversationMutationCommand.DeleteCandidate -> {
                val n = node(cmd.nodeId)
                val all = candidates(n)
                val target = all.firstOrNull { it["messageId"]?.jsonPrimitive?.content == cmd.messageId }
                    ?: rejectNotFound("Unknown candidate")
                val path = if (all.size == 1) "conversation[$id].node[${cmd.nodeId}] (last candidate: node pruned)"
                    else "conversation[$id].node[${cmd.nodeId}].candidate[${cmd.messageId}]"
                diff += DiffLine(path, text(target) ?: "[non-text content]", null)
            }
            is ConversationMutationCommand.PruneNode -> {
                val n = node(cmd.nodeId)
                diff += DiffLine("conversation[$id].node[${cmd.nodeId}]",
                    candidates(n).joinToString(" | ") { text(it) ?: "[non-text content]" }, null)
            }
            ConversationMutationCommand.SoftDelete ->
                diff += DiffLine("conversation[$id]", view["title"]?.jsonPrimitive?.contentOrNull ?: "", null)
            ConversationMutationCommand.Restore ->
                diff += DiffLine("conversation[$id]", null, view["title"]?.jsonPrimitive?.contentOrNull ?: "")
            is ConversationMutationCommand.CreateOrdinaryConversation -> error("handled above")
        }
        val op = ConversationCommandOp(id, token, cmd, ids, listOf(ref))
        return planned(entries, listOf(op), diff, command.risk(), listOf(ref), assigned)
    }

    private fun planned(
        entries: List<ReadSetEntry>, ops: List<ConversationCommandOp>, diff: List<DiffLine>, risk: RiskLevel,
        refs: List<ObjectRef>, assigned: Map<String, String>,
    ) = PlannedMutation(
        readSet = ReadSet(entries.distinct()),
        steps = ops,
        diffSummary = diff,
        risk = risk,
        retention = RetentionNote(recycleDays = if (ops.any { it.command == ConversationMutationCommand.SoftDelete }) 1 else 0),
        cascadeTargets = refs,
        requiresEnhancedConfirm = risk == RiskLevel.HIGH,
        hostAssignedIds = assigned,
    )

    /** Undo of a soft delete is a restore; other edits have no automatic inverse (history-preserving). */
    override suspend fun inverse(ctx: PlanContext, command: ManagementCommand): ManagementCommand? {
        require(command is ConversationManagementCommand)
        return when (command.command) {
            ConversationMutationCommand.SoftDelete -> command.copy(command = ConversationMutationCommand.Restore)
            ConversationMutationCommand.Restore -> command.copy(command = ConversationMutationCommand.SoftDelete)
            is ConversationMutationCommand.SelectCandidate, is ConversationMutationCommand.PatchMetadata -> null
            else -> null
        }
    }

    override fun needsRecyclePayload(command: ManagementCommand): Boolean = false

    override fun decodeCommand(json: String): ManagementCommand =
        this.json.decodeFromString(ConversationManagementCommand.serializer(), json)
}

/** Helper for read ports building [ConversationCollections.TREE] projections. */
internal fun conversationNodeJson(id: String, selectIndex: Int, candidates: List<Triple<String, String, String?>>) =
    JsonObject(mapOf(
        "id" to JsonPrimitive(id),
        "selectIndex" to JsonPrimitive(selectIndex),
        "candidates" to JsonArray(candidates.map { (messageId, role, text) ->
            JsonObject(mapOf("messageId" to JsonPrimitive(messageId), "role" to JsonPrimitive(role),
                "text" to JsonPrimitive(text)))
        }),
    ))
