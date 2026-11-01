package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.projectModelForWeb
import me.rerere.rikkihub.web.projectProviderForWeb
import me.rerere.rikkihub.web.dto.CreateProviderRequest
import me.rerere.rikkihub.web.dto.RemoteModelsResponse
import me.rerere.rikkihub.web.dto.ReorderIdsRequest
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private const val REMOTE_MODELS_TIMEOUT_MS = 20_000L
private const val MAX_MODEL_ID_LENGTH = 512

/** What the registry recognises for a model id; [contextLength] is null when no window is known. */
@Serializable
data class ModelCapabilitiesResponse(
    val inputModalities: List<Modality>,
    val outputModalities: List<Modality>,
    val abilities: List<ModelAbility>,
    val contextLength: Int?,
)

internal fun modelCapabilitiesFor(modelId: String): ModelCapabilitiesResponse {
    val detected = ModelRegistry.withDetectedCapabilities(Model(modelId = modelId))
    return ModelCapabilitiesResponse(
        inputModalities = detected.inputModalities,
        outputModalities = detected.outputModalities,
        abilities = detected.abilities,
        contextLength = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(modelId)?.let { detected.contextLength },
    )
}

fun Route.providerRoutes(
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
) {
    route("/providers") {
        post {
            val request = call.receive<CreateProviderRequest>()
            val provider = createProvider(request.type, request.name)
            settingsStore.update { settings ->
                settings.copy(providers = settings.providers + provider)
            }
            call.respond(HttpStatusCode.Created, projectProviderForWeb(provider))
        }

        // 添加模型时按型号自动识别图片输入/输出、工具、推理和上下文长度（与 App 同一套识别）
        get("/model-capabilities") {
            val modelId = call.request.queryParameters["modelId"]?.trim().orEmpty()
            if (modelId.isEmpty() || modelId.length > MAX_MODEL_ID_LENGTH) {
                throw BadRequestException("Missing or too long model id")
            }
            call.respond(HttpStatusCode.OK, modelCapabilitiesFor(modelId))
        }

        put("/order") {
            val request = call.receive<ReorderIdsRequest>()
            val ids = parseUuidList(request.ids)
            settingsStore.update { settings ->
                settings.copy(providers = reorderByIds(settings.providers, ids) { it.id })
            }
            call.respond(
                HttpStatusCode.OK,
                kotlinx.serialization.json.JsonArray(
                    settingsStore.settingsFlow.value.providers.map { projectProviderForWeb(it) }
                )
            )
        }

        put("/{id}") {
            val id = call.parameters["id"].toUuid("providerId")
            val raw = call.receive<JsonObject>()
            val draft = parseProviderUpdate(raw)
            var merged: ProviderSetting? = null
            settingsStore.update { current ->
                val existing = current.providers.find { it.id == id }
                    ?: throw NotFoundException("Provider not found")
                val next = mergeProvider(existing, draft)
                merged = next
                current.copy(providers = current.providers.map { if (it.id == id) next else it })
            }
            call.respond(HttpStatusCode.OK, projectProviderForWeb(requireNotNull(merged)))
        }

        delete("/{id}") {
            val id = call.parameters["id"].toUuid("providerId")
            if (isBuiltInProvider(id)) {
                throw BadRequestException("Cannot delete built-in provider")
            }
            val settings = settingsStore.settingsFlow.value
            if (settings.providers.none { it.id == id }) {
                throw NotFoundException("Provider not found")
            }
            settingsStore.update { current ->
                current.copy(providers = current.providers.filter { it.id != id })
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/{id}/remote-models") {
            val id = call.parameters["id"].toUuid("providerId")
            val provider = settingsStore.settingsFlow.value.providers.find { it.id == id }
                ?: throw NotFoundException("Provider not found")
            val response = try {
                withTimeout(REMOTE_MODELS_TIMEOUT_MS) {
                    providerManager.getProviderByType(provider).listModels(provider)
                }.let { models ->
                    // 与 App 一致：按型号认出图片输入/输出、工具、推理和上下文长度，导入时不用逐个勾
                    RemoteModelsResponse(models = models.map(ModelRegistry::withDetectedCapabilities))
                }
            } catch (error: TimeoutCancellationException) {
                RemoteModelsResponse(error = "Timed out after ${REMOTE_MODELS_TIMEOUT_MS / 1000}s")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                RemoteModelsResponse(error = error.message)
            }
            call.respond(HttpStatusCode.OK, response)
        }

        route("/{id}/models") {
            post {
                val id = call.parameters["id"].toUuid("providerId")
                val raw = call.receive<JsonObject>()
                val draft = parseModelUpdate(raw)
                val model = draft.incoming.copy(id = kotlin.uuid.Uuid.random())
                val updated = updateProvider(settingsStore, id) { it.addModel(model) }
                val created = updated.models.find { it.id == model.id }
                    ?: throw BadRequestException("Failed to add model")
                call.respond(HttpStatusCode.Created, projectModelForWeb(created))
            }

            put("/{modelId}") {
                val id = call.parameters["id"].toUuid("providerId")
                val modelId = call.parameters["modelId"].toUuid("modelId")
                val existingProvider = settingsStore.settingsFlow.value.providers.find { it.id == id }
                    ?: throw NotFoundException("Provider not found")
                val existingModel = requireModel(existingProvider, modelId)
                val raw = call.receive<JsonObject>()
                val draft = parseModelUpdate(raw)
                val merged = mergeModel(existingModel, draft)
                val updated = updateProvider(settingsStore, id) { it.editModel(merged) }
                call.respond(HttpStatusCode.OK, projectModelForWeb(requireModel(updated, modelId)))
            }

            delete("/{modelId}") {
                val id = call.parameters["id"].toUuid("providerId")
                val modelId = call.parameters["modelId"].toUuid("modelId")
                val existingProvider = settingsStore.settingsFlow.value.providers.find { it.id == id }
                    ?: throw NotFoundException("Provider not found")
                val model = requireModel(existingProvider, modelId)
                updateProvider(settingsStore, id) { it.delModel(model) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend fun updateProvider(
    settingsStore: SettingsStore,
    id: Uuid,
    transform: (ProviderSetting) -> ProviderSetting,
): ProviderSetting {
    var updated: ProviderSetting? = null
    settingsStore.update { settings ->
        val existing = settings.providers.find { it.id == id }
            ?: throw NotFoundException("Provider not found")
        val next = transform(existing)
        updated = next
        settings.copy(
            providers = settings.providers.map { provider ->
                if (provider.id == id) next else provider
            }
        )
    }
    return updated ?: throw NotFoundException("Provider not found")
}
