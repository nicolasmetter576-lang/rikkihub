package me.rerere.rikkihub.data.creator.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.creator.session.CreatorGetChangeStatusInput
import me.rerere.rikkihub.data.creator.session.CreatorObjectKind
import me.rerere.rikkihub.data.creator.session.CreatorOperation
import me.rerere.rikkihub.data.creator.session.CreatorPreviewChangeInput
import me.rerere.rikkihub.data.creator.session.CreatorProposeUndoInput
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.data.management.adapters.SettingsBatchCommand
import me.rerere.rikkihub.data.management.adapters.SettingsBatchStepException
import me.rerere.rikkihub.data.management.adapters.SettingsDomainCommand
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentCollections
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.management.proposal.Proposal
import me.rerere.rikkihub.data.management.proposal.ProposalServiceError
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.management.proposal.Provenance
import me.rerere.rikkihub.data.management.proposal.commandDomains
import me.rerere.rikkihub.data.management.runtime.ManagementRuntime
import kotlin.uuid.Uuid

/** Model-visible proposal summary: exact diff lines (bounded), never approval material. */
@Serializable
data class CreatorProposalSummary(
    val proposalId: String,
    val domain: String,
    val status: String,
    val risk: String,
    val requiresEnhancedConfirm: Boolean,
    val expiresAtMillis: Long,
    val diff: List<CreatorDiffLine>,
    val omittedDiffLines: Int = 0,
    /** Paths of changed secret fields (API keys, header values); their values are never shown. */
    val hiddenValues: List<String> = emptyList(),
    /** Indexes of the operations (in the creator_preview_change call) this proposal contains. */
    val operations: List<Int> = emptyList(),
    /** `"$<index>"` of a Create/Clone → the id the object gets once the user approves. */
    val createdIds: Map<String, String> = emptyMap(),
    val statusReason: String? = null,
    val note: String = "Waiting for the user's review in the app. You cannot approve or apply it.",
)

@Serializable
data class CreatorDiffLine(val path: String, val before: String? = null, val after: String? = null)

@Serializable
data class CreatorPreviewResult(
    val proposals: List<CreatorProposalSummary>,
    val rejected: List<CreatorRejectedOperation> = emptyList(),
    val note: String? = null,
)

@Serializable
data class CreatorRejectedOperation(
    val index: Int,
    val code: String,
    val detail: String,
    val hint: String? = null,
)

/** One line per proposal of this conversation (`creator_get_change_status` without proposalId). */
@Serializable
data class CreatorProposalListItem(
    val proposalId: String,
    val status: String,
    val domain: String,
    val summary: String,
    val createdIds: Map<String, String> = emptyMap(),
    val statusReason: String? = null,
)

/** Where a creator tool call came from: the creator conversation and the model's tool call. */
data class CreatorCallSource(val sessionId: Uuid, val runId: String, val callId: String) {
    val provenance: Provenance get() = Provenance(sessionId.toString(), runId, callId)
}

/**
 * Wiring between creator tool calls and the management chain.
 *
 * `creator_preview_change` is all-or-nothing: every operation is decoded and planned first, and
 * proposals are stored only when all of them are valid. All Settings operations of one call (assistants,
 * tags, quick messages, mode injections, lorebooks, providers, models, default slots and prompts) become ONE proposal
 * committed in one transaction, planned in order so later operations see earlier ones (and can reference
 * an object created earlier in the call as `"$<index>"`). Sub-agent presets, skills and conversations live
 * in other stores: each such operation is its own proposal. Status and undo are scoped to the calling
 * conversation (proposal `sessionId` = conversation id).
 */
class CreatorManagementBridge(
    private val runtime: () -> ManagementRuntime,
    private val json: Json,
) {
    private class Rejection(val index: Int, val code: String, val detail: String, val hint: String?)

    suspend fun preview(source: CreatorCallSource, args: JsonObject): List<UIMessagePart> {
        if (args.toString().length > CreatorReadDomain.MAX_PROPOSAL_CHARS)
            return error("PROPOSAL_TOO_LARGE", "proposal request exceeds 256KiB; split it over several turns")
        val input = runCatching { json.decodeFromJsonElement(CreatorPreviewChangeInput.serializer(), args) }.getOrNull()
            ?: return error("BAD_INPUT", "expected {operations:[{kind, command, targetId?, params?}]}; call creator_describe for formats")
        if (input.operations.isEmpty()) return error("EMPTY_PROPOSAL", "no operations")
        if (input.operations.size > MAX_OPERATIONS_PER_CALL)
            return error("TOO_MANY_OPERATIONS", "at most $MAX_OPERATIONS_PER_CALL operations per call")
        val operations = input.operations
        val rt = try {
            runtime()
        } catch (e: Exception) {
            return result(operations.indices.map { Rejection(it, "UNAVAILABLE", e.message.orEmpty().take(300),
                "The management chain is still starting or unavailable; try again in a moment.") })
        }
        val ctx = runCatching { decodeContext(rt) }.getOrDefault(CreatorDecodeContext.Empty)

        // 1. Decode every operation.
        val rejected = mutableListOf<Rejection>()
        val decoded = sortedMapOf<Int, ManagementCommand>()
        val creates = operations.indices.filter { isSettingsCreate(operations[it]) }.toSet()
        operations.forEachIndexed { index, op ->
            try {
                checkReferences(index, op, creates)
                decoded[index] = CreatorCommandDecoder.decode(op, ctx)
            } catch (e: CreatorCommandRejected) {
                rejected += Rejection(index, e.code, e.message.orEmpty().take(400), hintFor(e.code, op))
            } catch (e: IllegalArgumentException) {
                rejected += Rejection(index, "INVALID", e.message.orEmpty().take(400), hintFor("INVALID", op))
            }
        }
        // Several commands on the same object in other stores would conflict once the first is approved.
        val others = decoded.filterValues { it !is SettingsDomainCommand }
        val seen = mutableMapOf<String, Int>()
        others.forEach { (index, command) ->
            command.affectedRefs().map { it.toString() }.forEach { ref ->
                val first = seen.putIfAbsent(ref, index)
                if (first != null && first != index) rejected += Rejection(index, "SAME_TARGET",
                    "operation $first already changes $ref in this call",
                    "Put the final state into one operation (e.g. one PatchTextFile with the whole new file).")
            }
        }
        if (rejected.isNotEmpty()) return result(rejected)

        // 2. Plan everything before storing anything.
        val planned = mutableListOf<Pair<List<Int>, ManagementRuntime.PlannedCommand>>()
        val settings = decoded.filterValues { it is SettingsDomainCommand }.mapValues { it.value as SettingsDomainCommand }
        if (settings.isNotEmpty()) {
            val indexes = settings.keys.toList()
            val command: ManagementCommand = if (settings.size == 1) settings.values.single()
                else SettingsBatchCommand(settings.values.toList(), indexes.map { "\$$it" })
            try {
                planned += indexes to rt.plan(command)
            } catch (e: SettingsBatchStepException) {
                val index = e.label.removePrefix("\$").toIntOrNull() ?: indexes.first()
                rejected += rejection(index, e.cause, operations[index])
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                rejected += rejection(indexes.first(), e, operations[indexes.first()])
            }
        }
        others.forEach { (index, command) ->
            try {
                planned += listOf(index) to rt.plan(command)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                rejected += rejection(index, e, operations[index])
            }
        }
        if (rejected.isNotEmpty()) return result(rejected)

        // 3. Store.
        val proposals = planned.sortedBy { it.first.first() }.map { (indexes, plan) ->
            summarize(rt.freeze(plan, source.provenance), indexes)
        }
        return text(json.encodeToString(CreatorPreviewResult.serializer(), CreatorPreviewResult(proposals,
            note = "Proposed. Tell the user what will change and that they approve it with the Proposals button " +
                "at the top right of this chat. Nothing changes until they approve.")))
    }

    private fun result(rejected: List<Rejection>): List<UIMessagePart> = text(json.encodeToString(
        CreatorPreviewResult.serializer(),
        CreatorPreviewResult(
            proposals = emptyList(),
            rejected = rejected.sortedBy { it.index }.map { CreatorRejectedOperation(it.index, it.code, it.detail, it.hint) },
            note = "Nothing was proposed. Fix the rejected operations and send the whole operations list again in ONE call.",
        )))

    private fun rejection(index: Int, e: Throwable, op: CreatorOperation): Rejection {
        val (code, detail) = when (e) {
            is CreatorCommandRejected -> e.code to e.message.orEmpty()
            is ProposalServiceError -> e.errorCode.name to e.message.orEmpty()
            is IllegalArgumentException -> "INVALID" to e.message.orEmpty()
            is IllegalStateException -> "UNAVAILABLE" to e.message.orEmpty()
            else -> "FAILED" to (e.message ?: e.javaClass.simpleName)
        }
        return Rejection(index, code, detail.take(400), hintFor(code, op))
    }

    /** What to do next, per rejection code. */
    private fun hintFor(code: String, op: CreatorOperation): String? {
        val kind = CreatorObjectKind.parse(op.kind)?.name ?: op.kind
        return when (code) {
            "BAD_KIND" -> "Kinds: ${CreatorObjectKind.names}."
            "BAD_COMMAND", "BAD_INPUT", "FORBIDDEN_FIELD", "INVALID", "INVALID_VALUE" ->
                "Call creator_describe {kind:\"$kind\", command:\"${op.command}\"} for the exact format and an example."
            "NOT_FOUND", "UNKNOWN_OBJECT" -> "Call creator_list_objects {kind:\"$kind\"} for valid ids (ids are UUIDs, not names)."
            "BAD_REFERENCE" -> "\"\$n\" may only name a Create/Clone of assistants, tags, quick messages, mode injections, " +
                "lorebooks, providers or models that comes EARLIER in the same operations list."
            "TOKEN_CONFLICT", "STALE_READSET", "STALE_EPOCH" -> "The data changed meanwhile: read the object again and resend."
            "RESERVED_ID" -> "The creator assistant and its conversations cannot be managed."
            "LEASE_BUSY" -> "The object is busy (e.g. a conversation is generating); try again later."
            "MISSING_REPLACEMENT_PLAN" -> "In the same call, first move the default slots / assistants to another model, then delete."
            "NOT_ALLOWED" -> "Tell the user to do it in Settings › Providers."
            "READ_ONLY_KIND" -> "Tell the user to change it in the app."
            "UNAVAILABLE" -> "The management chain is still starting; try again in a moment."
            "BUDGET_EXCEEDED" -> "Split the change into smaller calls."
            else -> null
        }
    }

    private fun isSettingsCreate(op: CreatorOperation): Boolean {
        val kind = CreatorObjectKind.parse(op.kind) ?: return false
        val command = op.command.trim().lowercase()
        return kind in REFERENCEABLE && (command == "create" || command == "clone" || command == "createtag")
    }

    /** `"$n"` in an id position must name an earlier Create/Clone of a Settings object. */
    private fun checkReferences(index: Int, op: CreatorOperation, creates: Set<Int>) {
        val settingsKind = CreatorObjectKind.parse(op.kind) in SETTINGS_KINDS
        fun check(value: String?) {
            val n = value?.let { LABEL.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return
            if (!settingsKind) throw CreatorCommandRejected("BAD_REFERENCE",
                "\"$value\": objects created in this call exist only after approval; send this operation in a later turn, " +
                    "after the user approved, using the real id")
            if (n >= index || n !in creates) throw CreatorCommandRejected("BAD_REFERENCE",
                "\"$value\" does not name a Create/Clone earlier in this call")
        }
        check(op.targetId)
        fun walk(key: String?, e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> walk(k, v) }
                is JsonArray -> e.forEach { walk(key, it) }
                is JsonPrimitive -> if (e.isString && key != null && ID_KEY.matches(key)) check(e.content)
            }
        }
        walk(null, op.params)
    }

    private suspend fun decodeContext(rt: ManagementRuntime): CreatorDecodeContext {
        val read = rt.readPort
        val settings = read.settingsSnapshot()
        val modelProviders = settings.providers.flatMap { p -> p.models.map { it.id.toString() to p.id.toString() } }.toMap()
        val libraryKeys = runCatching {
            read.snapshotIds(SubagentCollections.LIBRARY).associateWith { id ->
                read.readObjectJson("room.subagentLibrary", id)?.get("key")?.jsonPrimitive?.contentOrNull
            }
        }.getOrDefault(emptyMap())
        val local = runCatching {
            read.snapshotIds("room.workspaces").flatMap { ws ->
                val row = read.readObjectJson("room.workspaces", ws)
                val presets = row?.get("subagentPresets")?.jsonPrimitive?.contentOrNull
                    ?.let(WorkspaceSubagentPresets::tryParse).orEmpty()
                presets.filter { it.libraryPresetId == null }.map { it.id to (ws to it.key) }
            }.toMap()
        }.getOrDefault(emptyMap())
        return object : CreatorDecodeContext {
            override fun providerOfModel(modelId: String) = modelProviders[modelId]
            override fun workspaceOfLocalPreset(presetId: String) = local[presetId]?.first
            override fun isLibraryPreset(presetId: String) = when (presetId) {
                in libraryKeys -> true
                in local -> false
                else -> null
            }
            override fun presetKey(presetId: String) = libraryKeys[presetId] ?: local[presetId]?.second
        }
    }

    suspend fun status(sessionId: Uuid, args: JsonElement): List<UIMessagePart> {
        val input = runCatching { json.decodeFromJsonElement(CreatorGetChangeStatusInput.serializer(), args) }.getOrNull()
        val rt = try { runtime() } catch (e: Exception) {
            return error("UNAVAILABLE", "the management chain is not ready; try again in a moment")
        }
        if (input?.proposalId.isNullOrBlank()) {
            val items = runCatching { rt.list() }.getOrDefault(emptyList())
                .filter { it.sessionId == sessionId.toString() }
                .take(MAX_LISTED)
                .map { p ->
                    CreatorProposalListItem(p.proposalId, p.status.name, domainNames(p),
                        p.diffSummary.filterNot { it.secret }.take(3).joinToString("; ") { line ->
                            "${line.path}: ${line.before?.take(60) ?: "∅"} → ${line.after?.take(60) ?: "∅"}"
                        },
                        createdIds(p, null), p.statusReason)
                }
            return text(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CreatorProposalListItem.serializer()), items))
        }
        val proposal = runCatching { rt.proposals.get(checkNotNull(input?.proposalId)) }.getOrNull()
        if (proposal == null || proposal.sessionId != sessionId.toString())
            return error("NOT_FOUND", "proposal not found in this conversation; call creator_get_change_status without proposalId to list them")
        return text(json.encodeToString(CreatorProposalSummary.serializer(), summarize(proposal, null)))
    }

    suspend fun proposeUndo(source: CreatorCallSource, args: JsonElement): List<UIMessagePart> {
        val sessionId = source.sessionId
        val input = runCatching { json.decodeFromJsonElement(CreatorProposeUndoInput.serializer(), args) }.getOrNull()
            ?: return error("BAD_INPUT", "expected {proposalId}")
        val rt = try { runtime() } catch (e: Exception) {
            return error("UNAVAILABLE", "the management chain is not ready; try again in a moment")
        }
        val proposal = runCatching { rt.proposals.get(input.proposalId) }.getOrNull()
        if (proposal == null || proposal.sessionId != sessionId.toString()) return error("NOT_FOUND", "proposal not found in this conversation")
        if (proposal.status != ProposalStatus.APPLIED) return error("NOT_APPLIED",
            "only applied proposals can be undone (this one is ${proposal.status}); a pending one is simply not approved")
        return try {
            val undo = rt.previewUndo(proposal, source.provenance)
                ?: return error("UNDO_UNAVAILABLE", "this change has no automatic undo; propose the reverse change explicitly")
            text(json.encodeToString(CreatorPreviewResult.serializer(), CreatorPreviewResult(listOf(summarize(undo, null)))))
        } catch (e: ProposalServiceError) {
            error(e.errorCode.name, e.message.orEmpty().take(300) +
                if (e.errorCode.name == "TOKEN_CONFLICT") "; the objects changed after it was applied, propose the reverse change explicitly" else "")
        }
    }

    /** Proposals of one conversation (for the status reminder); empty while the chain is unavailable. */
    suspend fun sessionProposals(sessionId: Uuid): List<Proposal> =
        runCatching { runtime().list().filter { it.sessionId == sessionId.toString() } }.getOrDefault(emptyList())

    private fun domainNames(p: Proposal) = p.commandDomains().joinToString("+") { it.name }

    /** Host-assigned ids under the `"$<index>"` names the model used. */
    private fun createdIds(p: Proposal, indexes: List<Int>?): Map<String, String> =
        p.hostAssignedIds.mapKeys { (key, _) ->
            if (key.startsWith("\$") && key.drop(1).all(Char::isDigit)) key
            else indexes?.singleOrNull()?.let { "\$$it" } ?: key
        }

    private fun summarize(p: Proposal, indexes: List<Int>?): CreatorProposalSummary {
        val visible = p.diffSummary.filterNot { it.secret }
        val lines = visible.take(MAX_DIFF_LINES).map {
            CreatorDiffLine(it.path.take(300), it.before?.take(MAX_VALUE), it.after?.take(MAX_VALUE))
        }
        val note = when (p.status) {
            ProposalStatus.PENDING_APPROVAL -> "Waiting for the user's review in the app. You cannot approve or apply it."
            ProposalStatus.APPLIED -> "Applied."
            ProposalStatus.CONFLICT -> "Not applied: the data changed after you proposed it. Read the objects again and propose anew."
            ProposalStatus.REJECTED -> "The user rejected it. Ask what they want instead."
            ProposalStatus.EXPIRED -> "Expired without approval."
            else -> p.status.name
        }
        return CreatorProposalSummary(p.proposalId, domainNames(p), p.status.name, p.risk.name,
            p.requiresEnhancedConfirm, p.expiresAtMillis, lines, (visible.size - lines.size).coerceAtLeast(0),
            hiddenValues = p.diffSummary.filter { it.secret }.take(MAX_DIFF_LINES).map { it.path.take(300) },
            operations = indexes.orEmpty(), createdIds = createdIds(p, indexes), statusReason = p.statusReason, note = note)
    }

    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    private fun error(code: String, detail: String) =
        listOf(UIMessagePart.Text(CreatorToolSurface.unavailableJson(code, detail)))

    companion object {
        const val MAX_OPERATIONS_PER_CALL = 16
        const val MAX_DIFF_LINES = 64
        const val MAX_VALUE = 2000
        private const val MAX_LISTED = 30

        private val LABEL = Regex("^\\$(\\d+)$")
        /** Keys whose string values are object ids (where `"$n"` references are allowed). */
        private val ID_KEY = Regex("^(assistantId|modelId|chatModelId|targetId|providerId|tags|lorebookIds|modeInjectionIds|quickMessageIds|mcpServers|orderedIds|moveToAssistantId|workspaceId|workspaceIds|presetId|orderedPresetIds)$")

        private val SETTINGS_KINDS = setOf(CreatorObjectKind.ASSISTANT, CreatorObjectKind.ASSISTANT_TAG,
            CreatorObjectKind.QUICK_MESSAGE, CreatorObjectKind.MODE_INJECTION, CreatorObjectKind.LOREBOOK,
            CreatorObjectKind.PROVIDER, CreatorObjectKind.MODEL_DEFINITION, CreatorObjectKind.DEFAULT_SLOT, CreatorObjectKind.PROMPT)
        private val REFERENCEABLE = SETTINGS_KINDS - CreatorObjectKind.DEFAULT_SLOT - CreatorObjectKind.PROMPT
    }
}
