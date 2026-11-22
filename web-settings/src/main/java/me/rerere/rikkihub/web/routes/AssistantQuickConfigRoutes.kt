package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.withEntryEnabled
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.projectAssistantForWeb
import kotlin.uuid.Uuid

/** The chat page's + menu prompt tile: each request changes one field of one assistant. */
fun Route.assistantQuickConfigRoutes(settingsStore: SettingsStore) {
    route("/assistants") {
        // Chat-page prompt tile: switches one entry of the assistant's prompt layout, on the latest stored assistant.
        put("/{id}/prompt-entries/{identifier}") {
            val id = call.parameters["id"].toUuid("assistantId")
            val identifier = call.parameters["identifier"].orEmpty()
            val rawBody = try {
                call.receive<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BadRequestException("Invalid payload")
            }
            val enabled = (rawBody["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw BadRequestException("enabled must be a boolean")
            val updated = updateOneAssistant(settingsStore, id) { assistant ->
                if (assistant.promptPreset.layout().entries.none { it.identifier == identifier }) throw NotFoundException("Prompt entry not found")
                assistant.copy(promptPreset = assistant.promptPreset.withEntryEnabled(identifier, enabled))
            }
            call.respond(HttpStatusCode.OK, projectAssistantForWeb(updated))
        }
    }
}

/** Changes one assistant inside a single settings update, so it always applies to the latest stored value. */
private suspend fun updateOneAssistant(settingsStore: SettingsStore, id: Uuid, transform: (Assistant) -> Assistant): Assistant {
    var updated: Assistant? = null
    settingsStore.update { current ->
        val existing = current.assistants.find { it.id == id } ?: throw NotFoundException("Assistant not found")
        val next = transform(existing)
        updated = next
        current.copy(assistants = current.assistants.map { if (it.id == id) next else it })
    }
    return updated!!
}
