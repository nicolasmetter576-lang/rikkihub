package me.rerere.rikkihub.web.routes

import androidx.core.net.toUri
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Avatar
import me.rerere.rikkihub.data.model.Tag
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.projectAssistantForWeb
import me.rerere.rikkihub.web.projectAssistantListForWeb
import me.rerere.rikkihub.web.requireVisibleAssistant
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.web.dto.AssistantTagRequest
import me.rerere.rikkihub.web.dto.CreateAssistantRequest
import me.rerere.rikkihub.web.dto.ReorderIdsRequest
import kotlin.uuid.Uuid

fun Route.assistantRoutes(
    settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
    memoryRepo: MemoryRepository,
    filesManager: FilesManager,
    availableSkillNames: (() -> Set<String>)? = null,
    // null = 不校验工作区是否存在（旧调用点）；聊天栏换绑工作区用
    workspaceExists: (suspend (String) -> Boolean)? = null,
) {
    route("/assistants") {
        post {
            val request = call.receive<CreateAssistantRequest>()
            val assistant = Assistant(name = request.name?.trim().orEmpty())
            settingsStore.update { settings ->
                settings.copy(assistants = settings.assistants + assistant)
            }
            call.respond(HttpStatusCode.Created, projectAssistantForWeb(assistant))
        }

        put("/order") {
            val request = call.receive<ReorderIdsRequest>()
            val ids = parseUuidList(request.ids)
            settingsStore.update { settings ->
                settings.copy(assistants = reorderByIds(settings.assistants, ids) { it.id })
            }
            call.respond(
                HttpStatusCode.OK,
                projectAssistantListForWeb(settingsStore.settingsFlow.value.assistants)
            )
        }

        put("/{id}") {
            val id = call.parameters["id"].toUuid("assistantId")
            // Read the raw object first: `localTools` must be handled by field
            // presence, because decoding straight into Assistant would silently
            // substitute the Kotlin default (empty list) when an older client
            // omits the key, clobbering the assistant's enabled tools.
            val rawBody = try {
                call.receive<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BadRequestException("Invalid assistant payload")
            }
            val (incoming, localTools) = parseAssistantUpdate(rawBody)
            id.requireVisibleAssistant()
            val settings = settingsStore.settingsFlow.value
            val existing = settings.assistants.find { it.id == id }
                ?: throw NotFoundException("Assistant not found")
            // Names deleted meanwhile are dropped rather than rejected (the same race the app prunes).
            val enabledSkills = parseEnabledSkillsField(rawBody)?.let { requested ->
                val available = availableSkillNames?.invoke() ?: return@let requested
                requested.filterTo(LinkedHashSet()) { it in available }
            }
            val merged = mergeAssistant(existing, incoming, localTools, presentKeys = rawBody.keys, enabledSkills = enabledSkills)
            validateAssistantWritable(merged, settings)
            settingsStore.update { current ->
                current.copy(
                    assistants = current.assistants.map { assistant ->
                        if (assistant.id == id) merged else assistant
                    }
                )
            }
            call.respond(HttpStatusCode.OK, projectAssistantForWeb(merged))
        }

        // Chat-page skill picker: only enabledSkills changes, applied to the latest stored assistant
        // so it never races a concurrent full-assistant save over unrelated fields.
        put("/{id}/skills") {
            val id = call.parameters["id"].toUuid("assistantId")
            id.requireVisibleAssistant()
            val rawBody = try {
                call.receive<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BadRequestException("Invalid payload")
            }
            val requested = parseEnabledSkillsField(rawBody) ?: throw BadRequestException("Missing enabledSkills")
            val available = availableSkillNames?.invoke()
            val skills = if (available == null) requested else requested.filterTo(LinkedHashSet()) { it in available }
            if (settingsStore.settingsFlow.value.assistants.none { it.id == id }) throw NotFoundException("Assistant not found")
            var updated: Assistant? = null
            settingsStore.update { current ->
                val assistants = withEnabledSkills(current.assistants, id, skills) ?: return@update current
                updated = assistants.first { it.id == id }
                current.copy(assistants = assistants)
            }
            call.respond(HttpStatusCode.OK, projectAssistantForWeb(updated ?: throw NotFoundException("Assistant not found")))
        }

        // Chat-bar workspace switch: only workspaceId changes, on the latest stored
        // assistant, like PUT /{id}/skills. It is per assistant, so every conversation of it follows.
        put("/{id}/workspace") {
            val id = call.parameters["id"].toUuid("assistantId")
            id.requireVisibleAssistant()
            val rawBody = try {
                call.receive<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BadRequestException("Invalid payload")
            }
            val workspaceId = parseAssistantWorkspaceId(rawBody)
            if (workspaceId != null && workspaceExists != null && !workspaceExists(workspaceId.toString())) {
                throw NotFoundException("Workspace not found")
            }
            if (settingsStore.settingsFlow.value.assistants.none { it.id == id }) throw NotFoundException("Assistant not found")
            var updated: Assistant? = null
            settingsStore.update { current ->
                val assistants = withWorkspaceId(current.assistants, id, workspaceId) ?: return@update current
                updated = assistants.first { it.id == id }
                current.copy(assistants = assistants)
            }
            call.respond(HttpStatusCode.OK, projectAssistantForWeb(updated ?: throw NotFoundException("Assistant not found")))
        }

        delete("/{id}") {
            val id = call.parameters["id"].toUuid("assistantId")
            id.requireVisibleAssistant()
            if (isDefaultAssistant(id)) {
                throw BadRequestException("Cannot delete default assistant")
            }
            if (CreatorAssistantDefinition.isCreator(id)) {
                throw BadRequestException("Cannot delete the creator assistant")
            }
            val settings = settingsStore.settingsFlow.value
            val existing = settings.assistants.find { it.id == id }
                ?: throw NotFoundException("Assistant not found")

            cleanupAssistantFiles(existing, filesManager)
            settingsStore.update { current ->
                current.copy(
                    assistants = current.assistants.filter { it.id != id },
                    assistantId = if (current.assistantId == id) DEFAULT_ASSISTANT_ID else current.assistantId,
                )
            }
            memoryRepo.deleteFilesOfAssistant(id.toString())
            conversationRepo.deleteConversationOfAssistant(id)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/clone") {
            val id = call.parameters["id"].toUuid("assistantId")
            id.requireVisibleAssistant()
            if (CreatorAssistantDefinition.isCreator(id)) {
                throw BadRequestException("The creator assistant cannot be cloned")
            }
            val settings = settingsStore.settingsFlow.value
            val existing = settings.assistants.find { it.id == id }
                ?: throw NotFoundException("Assistant not found")
            val cloned = existing.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${existing.name} (Clone)",
                avatar = if (existing.avatar is Avatar.Image) Avatar.Dummy else existing.avatar,
            )
            settingsStore.update { current ->
                current.copy(assistants = current.assistants + cloned)
            }
            call.respond(HttpStatusCode.Created, projectAssistantForWeb(cloned))
        }
    }

    route("/assistant-tags") {
        post {
            val request = call.receive<AssistantTagRequest>()
            val name = request.name?.trim().orEmpty()
            if (name.isEmpty()) {
                throw BadRequestException("Tag name must not be blank")
            }
            val tag = Tag(id = Uuid.random(), name = name)
            settingsStore.update { settings ->
                if (settings.assistantTags.any { it.name.equals(name, ignoreCase = true) }) {
                    throw BadRequestException("Tag name already exists")
                }
                settings.copy(assistantTags = settings.assistantTags + tag)
            }
            call.respond(HttpStatusCode.Created, tag)
        }

        put("/{id}") {
            val id = call.parameters["id"].toUuid("tagId")
            val request = call.receive<AssistantTagRequest>()
            val name = request.name?.trim().orEmpty()
            if (name.isEmpty()) {
                throw BadRequestException("Tag name must not be blank")
            }
            lateinit var updated: Tag
            settingsStore.update { settings ->
                val existing = settings.assistantTags.find { it.id == id }
                    ?: throw NotFoundException("Tag not found")
                if (settings.assistantTags.any { it.id != id && it.name.equals(name, ignoreCase = true) }) {
                    throw BadRequestException("Tag name already exists")
                }
                updated = existing.copy(name = name)
                settings.copy(
                    assistantTags = settings.assistantTags.map { if (it.id == id) updated else it },
                )
            }
            call.respond(HttpStatusCode.OK, updated)
        }

        delete("/{id}") {
            val id = call.parameters["id"].toUuid("tagId")
            settingsStore.update { settings ->
                if (settings.assistantTags.none { it.id == id }) {
                    throw NotFoundException("Tag not found")
                }
                settings.copy(
                    assistantTags = settings.assistantTags.filter { it.id != id },
                    assistants = settings.assistants.map { assistant ->
                        assistant.copy(tags = assistant.tags - id)
                    },
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun cleanupAssistantFiles(assistant: Assistant, filesManager: FilesManager) {
    val uris = buildList {
        (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
        assistant.background?.let { add(it.toUri()) }
    }
    if (uris.isNotEmpty()) {
        filesManager.deleteChatFiles(uris)
    }
}
