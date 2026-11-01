package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkihub.data.ai.tools.*
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.ConflictException
import me.rerere.rikkihub.web.NotFoundException

internal const val MAX_PRESET_BODY_BYTES = 128 * 1024L

@Serializable
data class WorkspaceSubagentPresetsResponse(
    val presets: List<WorkspaceSubagentPreset>?,
    val corrupt: Boolean,
)

/** Narrow adapter keeps all writes on the repository CAS/gate path and makes service tests offline. */
internal interface WebSubagentPresetStore {
    suspend fun read(workspaceId: String): String?
    suspend fun create(workspaceId: String, preset: WorkspaceSubagentPreset): WorkspaceSubagentPreset
    suspend fun update(workspaceId: String, preset: WorkspaceSubagentPreset, expectedVersion: Long): WorkspaceSubagentPreset
    suspend fun delete(workspaceId: String, presetId: String, expectedVersion: Long)
    suspend fun reorder(workspaceId: String, ids: List<String>, expectedVersions: Map<String, Long>): List<WorkspaceSubagentPreset>
    suspend fun reset(workspaceId: String)
}

internal class WebSubagentPresetService(
    private val store: WebSubagentPresetStore,
    private val validateModel: (String) -> Unit,
) {
    suspend fun list(id: String): WorkspaceSubagentPresetsResponse {
        val raw = store.read(id) ?: throw NoSuchElementException("Workspace not found")
        val presets = WorkspaceSubagentPresets.tryParse(raw)
        return WorkspaceSubagentPresetsResponse(presets?.sortedBy { it.sortOrder }, presets == null)
    }

    private suspend fun validList(id: String): List<WorkspaceSubagentPreset> =
        list(id).presets ?: throw SubagentPresetConflictException("Preset configuration is corrupt; explicitly reset it first")

    suspend fun create(id: String, body: JsonObject): WorkspaceSubagentPreset {
        val presets = validList(id)
        val next = mergeWebSubagentPreset(body)
        require(presets.size < WorkspaceSubagentPresets.MAX_PRESETS) { "At most 16 presets are allowed" }
        require(presets.none { it.key == next.key }) { "Preset key already exists" }
        if (next.modelSelection == SubagentPresetModelSelection.FIXED) validateModel(requireNotNull(next.modelId))
        return store.create(id, next)
    }

    suspend fun update(id: String, presetId: String, body: JsonObject): WorkspaceSubagentPreset {
        val current = validList(id).firstOrNull { it.id == presetId }
            ?: throw NoSuchElementException("Preset not found")
        if (current.libraryPresetId != null) throw SubagentPresetConflictException("Linked presets must be edited in the global library")
        val expectedVersion = presetExpectedVersion(body["expectedVersion"])
        // Repository is the final atomic CAS authority; early check improves stale-edit feedback.
        if (current.version != expectedVersion) throw SubagentPresetConflictException()
        val next = mergeWebSubagentPreset(body, current)
        // A stale fixed reference is retained on unrelated edits. Never silently fall back.
        if (next.modelSelection == SubagentPresetModelSelection.FIXED &&
            (current.modelSelection != next.modelSelection || current.modelId != next.modelId)
        ) validateModel(requireNotNull(next.modelId))
        return store.update(id, next, expectedVersion)
    }

    suspend fun delete(id: String, presetId: String, expectedVersion: Long) {
        val current = validList(id).firstOrNull { it.id == presetId } ?: throw NoSuchElementException("Preset not found")
        if (current.libraryPresetId != null) throw SubagentPresetConflictException("Linked presets must be removed through their global binding")
        store.delete(id, presetId, expectedVersion)
    }

    suspend fun reorder(id: String, body: JsonObject): List<WorkspaceSubagentPreset> {
        val current = validList(id)
        requirePresetKeys(body, setOf("presetIds", "expectedVersions"))
        val rawIds = body["presetIds"] as? JsonArray ?: throw IllegalArgumentException("presetIds must be an array")
        require(rawIds.size <= WorkspaceSubagentPresets.MAX_PRESETS) { "Too many preset ids" }
        val ids = rawIds.map { presetString(it, "presetIds") }
        val currentIds = current.map { it.id }.toSet()
        if (ids.any { it !in currentIds }) throw NoSuchElementException("Preset not found")
        require(ids.size == ids.distinct().size && ids.toSet() == currentIds) {
            "presetIds must contain every current preset exactly once"
        }
        val rawVersions = body["expectedVersions"] as? JsonObject
            ?: throw IllegalArgumentException("expectedVersions must be an object")
        require(rawVersions.keys == ids.toSet()) { "expectedVersions must contain every preset id" }
        return store.reorder(id, ids, rawVersions.mapValues { presetExpectedVersion(it.value) })
    }

    suspend fun reset(id: String, body: JsonObject) {
        requirePresetKeys(body, setOf("confirm"))
        require(presetBoolean(body["confirm"], "confirm")) { "confirm must be true" }
        require(list(id).corrupt) { "Only corrupt preset configuration may be reset" }
        store.reset(id)
    }
}

fun Route.workspaceSubagentPresetRoutes(repository: WorkspaceRepository, settingsStore: SettingsStore) {
    val service = WebSubagentPresetService(object : WebSubagentPresetStore {
        override suspend fun read(workspaceId: String) = repository.getById(workspaceId)?.subagentPresets
        override suspend fun create(workspaceId: String, preset: WorkspaceSubagentPreset) = repository.createSubagentPreset(workspaceId, preset)
        override suspend fun update(workspaceId: String, preset: WorkspaceSubagentPreset, expectedVersion: Long) = repository.updateSubagentPreset(workspaceId, preset, expectedVersion)
        override suspend fun delete(workspaceId: String, presetId: String, expectedVersion: Long) = repository.deleteSubagentPreset(workspaceId, presetId, expectedVersion)
        override suspend fun reorder(workspaceId: String, ids: List<String>, expectedVersions: Map<String, Long>) = repository.reorderSubagentPresets(workspaceId, ids, expectedVersions)
        override suspend fun reset(workspaceId: String) = repository.resetSubagentPresets(workspaceId)
    }) { requireToolCapableChatModelId(settingsStore.settingsFlow.value, it) }

    // Shared, authenticated template endpoint: one source of truth for App and Web prompts.
    get("/subagent-presets/templates") { call.respond(workspaceSubagentPresetTemplates()) }
    route("/workspaces/{id}/subagent-presets") {
        get { call.respond(presetApiOperation { service.list(call.presetWorkspaceId()) }) }
        post {
            val next = presetApiOperation { service.create(call.presetWorkspaceId(), readPresetBody(call)) }
            call.respond(HttpStatusCode.Created, next)
        }
        put("/{presetId}") {
            call.respond(presetApiOperation { service.update(call.presetWorkspaceId(), call.presetId(), readPresetBody(call)) })
        }
        delete("/{presetId}") {
            presetApiOperation {
                val raw = call.request.queryParameters["expectedVersion"]
                val version = raw?.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toLongOrNull()
                    ?: throw IllegalArgumentException("expectedVersion must be a positive integer")
                service.delete(call.presetWorkspaceId(), call.presetId(), version)
            }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/order") {
            call.respond(presetApiOperation { service.reorder(call.presetWorkspaceId(), readPresetBody(call)) })
        }
        post("/reset") {
            presetApiOperation { service.reset(call.presetWorkspaceId(), readPresetBody(call)) }
            call.respond(WorkspaceSubagentPresetsResponse(emptyList(), false))
        }
    }
}

private fun ApplicationCall.presetWorkspaceId() = parameters["id"] ?: throw BadRequestException("Missing workspace id")
private fun ApplicationCall.presetId() = parameters["presetId"] ?: throw BadRequestException("Missing preset id")

internal suspend fun <T> presetApiOperation(block: suspend () -> T): T = try {
    block()
} catch (error: SubagentPresetConflictException) {
    throw ConflictException(error.message ?: "Preset version conflict")
} catch (error: NoSuchElementException) {
    throw NotFoundException(error.message ?: "Preset not found")
} catch (error: IllegalArgumentException) {
    throw BadRequestException(error.message ?: "Invalid preset request")
}

internal suspend fun readPresetBody(call: ApplicationCall): JsonObject {
    val bytes = call.receiveChannel().readRemaining(MAX_PRESET_BODY_BYTES + 1).readByteArray()
    return parseWebPresetBody(bytes)
}

internal fun parseWebPresetBody(bytes: ByteArray): JsonObject {
    require(bytes.size <= MAX_PRESET_BODY_BYTES) { "Preset request body exceeds 128 KiB" }
    val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
        .getOrElse { throw IllegalArgumentException("Preset body must be valid UTF-8") }
    return Json.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("Preset request must be a JSON object")
}

internal val presetEditableKeys = setOf(
    "key", "name", "description", "systemPrompt", "enabled", "modelSelection", "modelId",
    "generationConfig", "acceptContext", "maxSteps",
)

/** Literal JSON types only, strict allowlist, latest.copy merge; all server-owned fields are rejected. */
internal fun mergeWebSubagentPreset(body: JsonObject, existing: WorkspaceSubagentPreset? = null): WorkspaceSubagentPreset {
    requirePresetKeys(body, presetEditableKeys + if (existing != null) setOf("expectedVersion") else emptySet())
    require(body.keys.any { it in presetEditableKeys }) { "No preset fields to update" }
    val base = existing ?: WorkspaceSubagentPreset()
    fun string(field: String, old: String) = if (field in body) presetString(body[field], field) else old
    fun boolean(field: String, old: Boolean) = if (field in body) presetBoolean(body[field], field) else old
    val selection = if ("modelSelection" in body) when (presetString(body["modelSelection"], "modelSelection")) {
        "workspace_default" -> SubagentPresetModelSelection.WORKSPACE_DEFAULT
        "parent" -> SubagentPresetModelSelection.PARENT
        "fixed" -> SubagentPresetModelSelection.FIXED
        else -> throw IllegalArgumentException("Invalid modelSelection")
    } else base.modelSelection
    val modelId = if ("modelId" in body) {
        if (body["modelId"] == JsonNull) null else presetString(body["modelId"], "modelId")
    } else if (selection != SubagentPresetModelSelection.FIXED) null else base.modelId
    val next = WorkspaceSubagentPresets.normalize(base.copy(
        key = string("key", base.key), name = string("name", base.name),
        description = string("description", base.description), systemPrompt = string("systemPrompt", base.systemPrompt),
        enabled = boolean("enabled", base.enabled), modelSelection = selection, modelId = modelId,
        generationConfig = if ("generationConfig" in body) mergePresetGeneration(body["generationConfig"], base.generationConfig) else base.generationConfig,
        acceptContext = boolean("acceptContext", base.acceptContext),
        maxSteps = if ("maxSteps" in body) presetInt(body["maxSteps"], "maxSteps") else base.maxSteps,
    ))
    require(existing == null || next.key == existing.key) { "Preset key is immutable" }
    WorkspaceSubagentPresets.validate(next)
    return next
}

private fun mergePresetGeneration(raw: JsonElement?, old: SubagentPresetGenerationConfig): SubagentPresetGenerationConfig {
    if (raw == JsonNull) return SubagentPresetGenerationConfig()
    val body = raw as? JsonObject ?: throw IllegalArgumentException("generationConfig must be an object or null")
    requirePresetKeys(body, setOf("temperature", "topP", "maxTokens", "reasoningLevel"))
    fun number(field: String, value: Float?): Float? {
        if (field !in body) return value
        if (body[field] == JsonNull) return null
        val p = body[field] as? JsonPrimitive
        require(p != null && !p.isString) { "$field must be a number or null" }
        return p.floatOrNull?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Invalid $field")
    }
    val reasoning = if ("reasoningLevel" !in body) old.reasoningLevel
        else when (presetString(body["reasoningLevel"], "reasoningLevel")) {
            "off" -> ReasoningLevel.OFF; "auto" -> ReasoningLevel.AUTO; "low" -> ReasoningLevel.LOW
            "medium" -> ReasoningLevel.MEDIUM; "high" -> ReasoningLevel.HIGH; "xhigh" -> ReasoningLevel.XHIGH; "max" -> ReasoningLevel.MAX
            else -> throw IllegalArgumentException("Invalid reasoningLevel")
        }
    return old.copy(
        temperature = number("temperature", old.temperature), topP = number("topP", old.topP),
        maxTokens = if ("maxTokens" !in body) old.maxTokens else if (body["maxTokens"] == JsonNull) null else presetInt(body["maxTokens"], "maxTokens"),
        reasoningLevel = reasoning,
    )
}

internal fun requirePresetKeys(body: JsonObject, allowed: Set<String>) {
    require(body.keys.all { it in allowed }) { "Unknown or server-owned preset field" }
}
internal fun presetString(raw: JsonElement?, field: String): String {
    val p = raw as? JsonPrimitive
    require(p != null && p.isString) { "$field must be a string" }
    return p.content
}
internal fun presetBoolean(raw: JsonElement?, field: String): Boolean {
    val p = raw as? JsonPrimitive
    require(p != null && !p.isString && p.booleanOrNull != null) { "$field must be a literal boolean" }
    return requireNotNull(p.booleanOrNull)
}
private fun presetInt(raw: JsonElement?, field: String): Int {
    val p = raw as? JsonPrimitive
    require(p != null && !p.isString && p.content.matches(Regex("-?[0-9]+"))) { "$field must be an integer" }
    return p.intOrNull ?: throw IllegalArgumentException("Invalid $field")
}
internal fun presetExpectedVersion(raw: JsonElement?): Long {
    val p = raw as? JsonPrimitive
    require(p != null && !p.isString && p.content.matches(Regex("[1-9][0-9]*"))) { "expectedVersion must be a positive integer" }
    return p.longOrNull ?: throw IllegalArgumentException("Invalid expectedVersion")
}
