package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.rikkihub.data.ai.tools.*
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.repository.SubagentPresetRepository
import me.rerere.rikkihub.data.repository.SubagentPresetLibraryState
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.utils.JsonInstant

/** Flattened canonical definitions + read-only assignment selectors. No workspace snapshot is writable. */
@Serializable
internal data class SubagentPresetLibraryResponse(val presets: List<JsonObject>?, val corrupt: Boolean)

internal interface WebSubagentPresetLibraryStore {
    suspend fun list(): SubagentPresetLibraryState
    suspend fun workspacePresets(): Map<String, String>
    suspend fun create(preset: WorkspaceSubagentPreset, workspaceIds: Set<String>): WorkspaceSubagentPreset
    suspend fun update(preset: WorkspaceSubagentPreset, expectedVersion: Long, workspaceIds: Set<String>?): WorkspaceSubagentPreset
    suspend fun delete(presetId: String, expectedVersion: Long)
    suspend fun setWorkspaceEnabled(workspaceId: String, presetId: String, enabled: Boolean, expectedVersion: Long): WorkspaceSubagentPreset
    suspend fun reset()
}

internal fun presetWorkspaceIds(raw: JsonElement?): Set<String> {
    val values = raw as? JsonArray ?: throw IllegalArgumentException("workspaceIds must be an array")
    require(values.size <= 1024) { "Too many workspace ids" }
    val ids = values.map { presetString(it, "workspaceIds").also { id -> require(id.isNotBlank() && id.length <= 128) { "Invalid workspace id" } } }
    require(ids.distinct().size == ids.size) { "Duplicate workspace ids" }
    return ids.toSet()
}

/** Membership uses libraryPresetId, not enabled: a disabled definition may still be assigned. */
internal fun presetLibraryWorkspaceIds(presetId: String, workspaces: Map<String, String>): List<String> =
    workspaces.filterValues { raw -> WorkspaceSubagentPresets.tryParse(raw)?.any { it.libraryPresetId == presetId } == true }.keys.sorted()

internal class WebSubagentPresetLibraryService(
    private val store: WebSubagentPresetLibraryStore,
    private val validateModel: (String) -> Unit,
) {
    suspend fun list(): SubagentPresetLibraryResponse {
        val state = store.list()
        val workspaces = store.workspacePresets()
        return SubagentPresetLibraryResponse(state.presets?.map { preset ->
            JsonObject(JsonInstant.encodeToJsonElement(preset).jsonObject +
                ("workspaceIds" to JsonArray(presetLibraryWorkspaceIds(preset.id, workspaces).map(::JsonPrimitive))))
        }, state.corrupt)
    }

    private suspend fun validList(): List<WorkspaceSubagentPreset> {
        val state = store.list()
        val presets = state.presets
        if (state.corrupt || presets == null) throw SubagentPresetConflictException("Preset library is corrupt; explicitly reset it first")
        return presets
    }

    private fun validateReference(next: WorkspaceSubagentPreset, current: WorkspaceSubagentPreset? = null) {
        if (next.modelSelection == SubagentPresetModelSelection.FIXED &&
            (current == null || current.modelSelection != next.modelSelection || current.modelId != next.modelId)) {
            validateModel(requireNotNull(next.modelId))
        }
    }

    suspend fun create(body: JsonObject): WorkspaceSubagentPreset {
        requirePresetKeys(body, presetEditableKeys + "workspaceIds")
        val targets = if ("workspaceIds" in body) presetWorkspaceIds(body["workspaceIds"]) else emptySet()
        val next = mergeWebSubagentPreset(JsonObject(body - "workspaceIds"))
        val current = validList()
        require(current.size < 64) { "At most 64 library presets are allowed" }
        require(current.none { it.key == next.key }) { "Preset key already exists" }
        validateReference(next)
        return store.create(next, targets)
    }

    suspend fun update(id: String, body: JsonObject): WorkspaceSubagentPreset {
        requirePresetKeys(body, presetEditableKeys + setOf("expectedVersion", "workspaceIds"))
        val expected = presetExpectedVersion(body["expectedVersion"])
        val targets = if ("workspaceIds" in body) presetWorkspaceIds(body["workspaceIds"]) else null
        require(body.keys.any { it in presetEditableKeys || it == "workspaceIds" }) { "No preset fields to update" }
        val current = validList().firstOrNull { it.id == id } ?: throw NoSuchElementException("Preset not found")
        if (current.version != expected) throw SubagentPresetConflictException()
        val fields = JsonObject(body - setOf("workspaceIds", "expectedVersion"))
        val next = if (fields.isEmpty()) current else mergeWebSubagentPreset(fields, current)
        validateReference(next, current)
        if (targets != null && next.modelSelection == SubagentPresetModelSelection.FIXED) {
            val assigned = presetLibraryWorkspaceIds(id, store.workspacePresets()).toSet()
            if (targets.any { it !in assigned }) validateReference(next)
        }
        // Core validates all targets/capacity/local-key conflicts and publishes the library + snapshots atomically.
        return store.update(next, expected, targets)
    }

    suspend fun delete(id: String, body: JsonObject) {
        requirePresetKeys(body, setOf("expectedVersion"))
        val expected = presetExpectedVersion(body["expectedVersion"])
        validList()
        store.delete(id, expected)
    }

    suspend fun enabled(workspaceId: String, id: String, body: JsonObject): WorkspaceSubagentPreset {
        requirePresetKeys(body, setOf("enabled", "expectedVersion"))
        val enabled = presetBoolean(body["enabled"], "enabled")
        val expected = presetExpectedVersion(body["expectedVersion"])
        val current = validList().firstOrNull { it.id == id } ?: throw NoSuchElementException("Preset not found")
        if (current.version != expected) throw SubagentPresetConflictException()
        if (enabled) validateReference(current)
        return store.setWorkspaceEnabled(workspaceId, id, enabled, expected)
    }

    suspend fun reset(body: JsonObject) {
        requirePresetKeys(body, setOf("confirm"))
        require(presetBoolean(body["confirm"], "confirm")) { "confirm must be true" }
        require(store.list().corrupt) { "Only a corrupt library may be reset" }
        store.reset()
    }
}

fun Route.subagentPresetLibraryRoutes(repository: SubagentPresetRepository, workspaces: WorkspaceRepository, settings: SettingsStore) {
    val service = WebSubagentPresetLibraryService(object : WebSubagentPresetLibraryStore {
        override suspend fun list() = repository.list()
        override suspend fun workspacePresets() = workspaces.list().associate { it.id to it.subagentPresets }
        override suspend fun create(preset: WorkspaceSubagentPreset, workspaceIds: Set<String>) = repository.create(preset, workspaceIds)
        override suspend fun update(preset: WorkspaceSubagentPreset, expectedVersion: Long, workspaceIds: Set<String>?) = repository.update(preset, expectedVersion, workspaceIds)
        override suspend fun delete(presetId: String, expectedVersion: Long) = repository.delete(presetId, expectedVersion)
        override suspend fun setWorkspaceEnabled(workspaceId: String, presetId: String, enabled: Boolean, expectedVersion: Long) = repository.setWorkspaceEnabled(workspaceId, presetId, enabled, expectedVersion)
        override suspend fun reset() = repository.reset()
    }) { requireToolCapableChatModelId(settings.settingsFlow.value, it) }
    route("/subagent-presets") {
        get { call.respond(presetApiOperation { service.list() }) }
        post { call.respond(HttpStatusCode.Created, presetApiOperation { service.create(readPresetBody(call)) }) }
        put("/{presetId}") { call.respond(presetApiOperation { service.update(requireNotNull(call.parameters["presetId"]), readPresetBody(call)) }) }
        delete("/{presetId}") {
            presetApiOperation { service.delete(requireNotNull(call.parameters["presetId"]), readPresetBody(call)) }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/reset") {
            presetApiOperation { service.reset(readPresetBody(call)) }
            call.respond(presetApiOperation { service.list() })
        }
    }
    post("/workspaces/{id}/subagent-presets/{presetId}/enabled") {
        call.respond(presetApiOperation { service.enabled(requireNotNull(call.parameters["id"]), requireNotNull(call.parameters["presetId"]), readPresetBody(call)) })
    }
}
