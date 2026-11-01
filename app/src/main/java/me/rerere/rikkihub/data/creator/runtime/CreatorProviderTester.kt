package me.rerere.rikkihub.data.creator.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant

/** `creator_test_provider` 输入。 */
@Serializable
data class CreatorTestProviderInput(
    val providerId: String,
    /** MODEL_DEFINITION id or the provider's model name; null = the provider's first chat model. */
    val model: String? = null,
    val message: String? = null,
    val listModels: Boolean = false,
)

@Serializable
data class CreatorTestProviderOutput(
    val providerId: String,
    val providerName: String,
    /** null when no message was sent (only the model list was asked for). */
    val ok: Boolean? = null,
    val model: String? = null,
    val latencyMs: Long? = null,
    val reply: String? = null,
    val error: String? = null,
    val remoteModels: List<String>? = null,
    val remoteModelCount: Int? = null,
    val remoteModelsError: String? = null,
    val note: String? = null,
)

/**
 * `creator_test_provider`: one test message (and optionally the remote model list) through a SAVED
 * provider, with the same request parameters as the provider page's connection test. Read-only: it
 * changes no settings, so it needs no proposal. Secrets of the provider are masked in error texts.
 */
class CreatorProviderTester(
    private val settingsProvider: suspend () -> Settings,
    private val client: Client,
    private val messageTimeoutMillis: Long = 60_000,
    private val listTimeoutMillis: Long = 20_000,
) {
    interface Client {
        suspend fun generate(provider: ProviderSetting, model: Model, message: String): String
        suspend fun listModels(provider: ProviderSetting): List<Model>
    }

    /** The app's providers, as the connection test on the provider page calls them. */
    class ManagerClient(private val manager: ProviderManager) : Client {
        override suspend fun generate(provider: ProviderSetting, model: Model, message: String): String {
            val result = manager.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user(message)),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    reasoningLevel = ReasoningLevel.AUTO,
                ),
            )
            return result.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        }

        override suspend fun listModels(provider: ProviderSetting): List<Model> =
            manager.getProviderByType(provider).listModels(provider)
    }

    suspend fun test(args: JsonElement): String {
        val input = runCatching { JsonInstant.decodeFromJsonElement<CreatorTestProviderInput>(args) }.getOrNull()
            ?: return CreatorToolSurface.unavailableJson("BAD_INPUT", "expected {providerId, model?, message?, listModels?}")
        val provider = settingsProvider().providers.firstOrNull { it.id.toString() == input.providerId.trim() }
            ?: return CreatorToolSurface.unavailableJson("NOT_FOUND", "no saved provider with id '${input.providerId}'. " +
                "A provider in a proposal exists only after the user approves it; list kind=PROVIDER for ids")
        val message = input.message?.trim().orEmpty().ifEmpty { "hello" }
        if (message.length > MAX_MESSAGE_CHARS) return CreatorToolSurface.unavailableJson("BAD_INPUT",
            "message must be at most $MAX_MESSAGE_CHARS characters")
        val secrets = secretsOf(provider)
        val notes = mutableListOf<String>()
        if (!provider.enabled) notes += "This provider is disabled, so its models are hidden in the app until it is enabled."

        var remote: List<String>? = null
        var remoteCount: Int? = null
        var remoteError: String? = null
        if (input.listModels) {
            try {
                val models = withTimeout(listTimeoutMillis) { client.listModels(provider) }.map { it.modelId }.distinct()
                remote = models.take(MAX_REMOTE_MODELS)
                remoteCount = models.size
            } catch (e: TimeoutCancellationException) {
                remoteError = "Timed out after ${listTimeoutMillis / 1000}s"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                remoteError = describe(e, secrets)
            }
        }

        val model = resolveModel(provider, input.model)
        if (model == null) {
            if (input.listModels) return encode(CreatorTestProviderOutput(provider.id.toString(), provider.name,
                remoteModels = remote, remoteModelCount = remoteCount, remoteModelsError = remoteError,
                note = (notes + "No message sent: the provider has no chat model; pass model (a model name) to send one.").joinToString(" ")))
            return CreatorToolSurface.unavailableJson("NO_MODEL", "this provider has no chat model yet; pass model " +
                "(a model name such as gpt-4o-mini, or listModels:true to see what the service offers)")
        }
        if (model.type != ModelType.CHAT) return CreatorToolSurface.unavailableJson("BAD_INPUT",
            "'${model.modelId}' is a ${model.type.name} model; only chat models can be sent a test message")
        if (provider.models.none { it.id == model.id }) notes += "'${model.modelId}' is not in this provider's model list yet."

        val started = System.currentTimeMillis()
        val (ok, reply, error) = try {
            val text = withTimeout(messageTimeoutMillis) { client.generate(provider, model, message) }
            Triple(true, mask(text, secrets).let { if (it.length > MAX_REPLY_CHARS) it.take(MAX_REPLY_CHARS) + "…[cut]" else it }, null)
        } catch (e: TimeoutCancellationException) {
            Triple(false, null, "Timed out after ${messageTimeoutMillis / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Triple(false, null, describe(e, secrets))
        }
        return encode(CreatorTestProviderOutput(
            providerId = provider.id.toString(), providerName = provider.name, ok = ok, model = model.modelId,
            latencyMs = System.currentTimeMillis() - started, reply = reply, error = error,
            remoteModels = remote, remoteModelCount = remoteCount, remoteModelsError = remoteError,
            note = notes.joinToString(" ").ifEmpty { null },
        ))
    }

    /** By MODEL_DEFINITION id, then by model name; an unknown name is tried as a temporary chat model. */
    private fun resolveModel(provider: ProviderSetting, raw: String?): Model? {
        val wanted = raw?.trim().orEmpty()
        if (wanted.isEmpty()) return provider.models.firstOrNull { it.type == ModelType.CHAT }
        return provider.models.firstOrNull { it.id.toString() == wanted }
            ?: provider.models.firstOrNull { it.modelId == wanted }
            ?: provider.models.firstOrNull { it.modelId.equals(wanted, ignoreCase = true) }
            ?: Model(modelId = wanted.take(1024), displayName = wanted.take(1024))
    }

    private fun describe(e: Throwable, secrets: List<String>): String =
        mask(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName, secrets).take(MAX_ERROR_CHARS)

    private fun encode(output: CreatorTestProviderOutput) = JsonInstant.encodeToString(CreatorTestProviderOutput.serializer(), output)

    companion object {
        const val MAX_MESSAGE_CHARS = 2000
        const val MAX_REPLY_CHARS = 1000
        const val MAX_ERROR_CHARS = 600
        const val MAX_REMOTE_MODELS = 200

        /** Every key (several may be stored, separated like KeyRoulette does) and header value. */
        internal fun secretsOf(provider: ProviderSetting): List<String> {
            val keys = when (provider) {
                is ProviderSetting.OpenAI -> listOf(provider.apiKey)
                is ProviderSetting.Google -> listOf(provider.apiKey, provider.privateKey)
                is ProviderSetting.Claude -> listOf(provider.apiKey)
                else -> emptyList()
            }
            return (keys.flatMap { it.split(Regex("[\\s,]+")) } + provider.customHeaders.map { it.value })
                .map { it.trim() }.filter { it.length >= 6 }.distinct().sortedByDescending { it.length }
        }

        internal fun mask(text: String, secrets: List<String>): String =
            secrets.fold(text) { acc, secret -> acc.replace(secret, "***") }
    }
}
