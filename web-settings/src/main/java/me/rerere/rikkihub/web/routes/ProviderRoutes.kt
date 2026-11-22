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
import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.VertexServiceAccount
import me.rerere.ai.provider.effectiveApiKeyEntries
import me.rerere.ai.provider.effectiveServiceAccounts
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.CredentialHealthStatus
import me.rerere.rikkihub.data.creator.runtime.CreatorProviderTester
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

@Serializable
data class CredentialRuntimeStatusDto(
    val credentialId: String,
    val status: String,
    val cooldownUntil: Long,
    val remainingMillis: Long,
    val consecutiveRateLimits: Int,
    val lastStatusCode: Int? = null,
    val lastErrorSummary: String? = null,
    val modelCooldowns: Map<String, Long> = emptyMap(),
)

@Serializable
data class CredentialStatusResponse(
    val statuses: Map<String, CredentialRuntimeStatusDto>,
)

@Serializable
data class ResetCredentialsRequest(
    val credentialId: String? = null,
)

@Serializable
data class TestCredentialRequest(
    val type: String,
    val credentialId: String? = null,
    val key: String? = null,
    val projectId: String? = null,
    val serviceAccountEmail: String? = null,
    val privateKey: String? = null,
    val location: String? = null,
)

@Serializable
data class TestCredentialResponse(
    val ok: Boolean,
    val modelCount: Int? = null,
    val error: String? = null,
)
@Serializable
data class ModelCapabilitiesResponse(
    val inputModalities: List<Modality>,
    val outputModalities: List<Modality>,
    val abilities: List<ModelAbility>,
    val contextLength: Int?,
    /** models.dev 参考价（美元 / 百万 token），网页端「填入参考价」用 */
    val referencePrice: me.rerere.ai.provider.ModelPricing? = null,
)

internal fun modelCapabilitiesFor(modelId: String): ModelCapabilitiesResponse {
    val detected = ModelRegistry.withDetectedCapabilities(Model(modelId = modelId))
    return ModelCapabilitiesResponse(
        inputModalities = detected.inputModalities,
        outputModalities = detected.outputModalities,
        abilities = detected.abilities,
        contextLength = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(modelId)?.let { detected.contextLength },
        referencePrice = ModelRegistry.referencePrice(modelId),
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
            var previous: ProviderSetting? = null
            var merged: ProviderSetting? = null
            settingsStore.update { current ->
                val existing = current.providers.find { it.id == id }
                    ?: throw NotFoundException("Provider not found")
                previous = existing
                val next = mergeProvider(existing, draft)
                merged = next
                current.copy(providers = current.providers.map { if (it.id == id) next else it })
            }
            val saved = requireNotNull(merged)
            previous?.let { prev ->
                providerManager.credentialPool.resetChanged(id.toString(), prev, saved)
            }
            call.respond(HttpStatusCode.OK, projectProviderForWeb(saved))
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

        get("/{id}/credential-status") {
            val id = call.parameters["id"].toUuid("providerId")
            val provider = settingsStore.settingsFlow.value.providers.find { it.id == id }
                ?: throw NotFoundException("Provider not found")
            call.respond(HttpStatusCode.OK, buildCredentialStatusResponse(providerManager, provider))
        }

        post("/{id}/credentials/reset") {
            val id = call.parameters["id"].toUuid("providerId")
            val provider = settingsStore.settingsFlow.value.providers.find { it.id == id }
                ?: throw NotFoundException("Provider not found")
            val req = runCatching { call.receive<ResetCredentialsRequest>() }.getOrDefault(ResetCredentialsRequest())
            val targetId = req.credentialId?.trim()?.takeIf { it.isNotEmpty() }
            if (targetId != null) {
                providerManager.credentialPool.resetCredential(id.toString(), targetId)
            } else {
                providerManager.credentialPool.resetProvider(id.toString())
            }
            call.respond(HttpStatusCode.OK, buildCredentialStatusResponse(providerManager, provider))
        }

        post("/{id}/credentials/test") {
            val id = call.parameters["id"].toUuid("providerId")
            val provider = settingsStore.settingsFlow.value.providers.find { it.id == id }
                ?: throw NotFoundException("Provider not found")
            val req = call.receive<TestCredentialRequest>()
            val response = when (req.type.lowercase()) {
                "api_key" -> testSingleApiKey(providerManager, provider, req)
                "service_account" -> testSingleServiceAccount(providerManager, provider, req)
                else -> throw BadRequestException("Unsupported credential test type")
            }
            call.respond(HttpStatusCode.OK, response)
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

private fun buildCredentialStatusResponse(
    providerManager: ProviderManager,
    provider: ProviderSetting,
): CredentialStatusResponse {
    val ids = buildList {
        provider.effectiveApiKeyEntries().forEach { add(it.id) }
        if (provider is ProviderSetting.Google) {
            provider.effectiveServiceAccounts().forEach { add(it.id) }
        }
    }.distinct()
    val snapshots = providerManager.credentialPool.snapshotsFor(provider.id.toString(), ids)
    val dtoMap = snapshots.mapValues { (_, snap) ->
        CredentialRuntimeStatusDto(
            credentialId = snap.credentialId,
            status = when (snap.status) {
                CredentialHealthStatus.HEALTHY -> "healthy"
                CredentialHealthStatus.COOLING_DOWN -> "cooling_down"
                CredentialHealthStatus.AUTH_ERROR -> "auth_error"
            },
            cooldownUntil = snap.cooldownUntil,
            remainingMillis = snap.remainingMillis,
            consecutiveRateLimits = snap.consecutiveRateLimits,
            lastStatusCode = snap.lastStatusCode,
            lastErrorSummary = snap.lastErrorSummary,
            modelCooldowns = snap.modelCooldowns,
        )
    }
    return CredentialStatusResponse(statuses = dtoMap)
}

private suspend fun testSingleApiKey(
    providerManager: ProviderManager,
    provider: ProviderSetting,
    req: TestCredentialRequest,
): TestCredentialResponse {
    val credId = req.credentialId?.trim()?.takeIf { it.isNotEmpty() } ?: "temp"
    val resolvedKey = req.key?.trim()?.takeIf { it.isNotEmpty() }
        ?: provider.effectiveApiKeyEntries().find { it.id == credId }?.key
        ?: return TestCredentialResponse(ok = false, error = "API key not configured")
    val entry = ApiKeyEntry(id = credId, key = resolvedKey, enabled = true)
    val isolated: ProviderSetting = when (provider) {
        is ProviderSetting.OpenAI -> provider.copy(apiKey = "", apiKeys = listOf(entry))
        is ProviderSetting.Google -> provider.copy(apiKey = "", apiKeys = listOf(entry), vertexAI = false, useServiceAccount = false)
        is ProviderSetting.Claude -> provider.copy(apiKey = "", apiKeys = listOf(entry))
        else -> throw BadRequestException("Provider does not support API keys")
    }
    val secrets = (CreatorProviderTester.secretsOf(provider) + resolvedKey)
        .filter { it.length >= 6 }
        .distinct()
        .sortedByDescending { it.length }
    return try {
        val models = withTimeout(REMOTE_MODELS_TIMEOUT_MS) {
            providerManager.getProviderByType(isolated).listModels(isolated)
        }
        providerManager.credentialPool.resetCredential(provider.id.toString(), credId)
        TestCredentialResponse(ok = true, modelCount = models.size)
    } catch (error: CancellationException) {
        throw error
    } catch (error: TimeoutCancellationException) {
        TestCredentialResponse(ok = false, error = "Timed out after ${REMOTE_MODELS_TIMEOUT_MS / 1000}s")
    } catch (error: Throwable) {
        val msg = CreatorProviderTester.mask(error.message ?: error.javaClass.simpleName, secrets)
        TestCredentialResponse(ok = false, error = msg.take(400))
    }
}

private suspend fun testSingleServiceAccount(
    providerManager: ProviderManager,
    provider: ProviderSetting,
    req: TestCredentialRequest,
): TestCredentialResponse {
    if (provider !is ProviderSetting.Google) {
        throw BadRequestException("Service accounts are only supported on Google providers")
    }
    val credId = req.credentialId?.trim()?.takeIf { it.isNotEmpty() } ?: "temp"
    val existing = provider.effectiveServiceAccounts().find { it.id == credId }
    val account = VertexServiceAccount(
        id = credId,
        projectId = req.projectId?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.projectId.orEmpty(),
        serviceAccountEmail = req.serviceAccountEmail?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing?.serviceAccountEmail.orEmpty(),
        privateKey = req.privateKey?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.privateKey.orEmpty(),
        location = req.location?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.location.orEmpty(),
        enabled = true,
    )
    if (account.serviceAccountEmail.isBlank() || account.privateKey.isBlank()) {
        return TestCredentialResponse(ok = false, error = "Service account email and private key are required")
    }
    val secrets = (CreatorProviderTester.secretsOf(provider) +
        account.privateKey.split(Regex("[\\s,]+")))
        .map { it.trim() }
        .filter { it.length >= 6 }
        .distinct()
        .sortedByDescending { it.length }
    return try {
        val googleProvider = providerManager.getProviderByType(provider) as GoogleProvider
        withTimeout(REMOTE_MODELS_TIMEOUT_MS) {
            googleProvider.verifyServiceAccountToken(account)
        }
        providerManager.credentialPool.resetCredential(provider.id.toString(), credId)
        TestCredentialResponse(ok = true)
    } catch (error: CancellationException) {
        throw error
    } catch (error: TimeoutCancellationException) {
        TestCredentialResponse(ok = false, error = "Timed out after ${REMOTE_MODELS_TIMEOUT_MS / 1000}s")
    } catch (error: Throwable) {
        val msg = CreatorProviderTester.mask(error.message ?: error.javaClass.simpleName, secrets)
        TestCredentialResponse(ok = false, error = msg.take(400))
    }
}
