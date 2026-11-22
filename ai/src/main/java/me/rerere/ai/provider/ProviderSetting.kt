package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.json
import java.security.MessageDigest
import kotlin.uuid.Uuid

@Serializable
enum class CredentialRotationStrategy {
    @SerialName("round_robin")
    ROUND_ROBIN,

    @SerialName("failover")
    FAILOVER,
}

@Serializable
data class ApiKeyEntry(
    val id: String = Uuid.random().toString(),
    val key: String = "",
    val label: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class VertexServiceAccount(
    val id: String = Uuid.random().toString(),
    val label: String = "",
    val projectId: String = "",
    val serviceAccountEmail: String = "",
    val privateKey: String = "",
    /** 留空时继承提供商默认 location。 */
    val location: String = "",
    val enabled: Boolean = true,
)

data class VertexSaImportResult(
    val accounts: List<VertexServiceAccount>,
    val skippedCount: Int,
)

private val SPLIT_API_KEY_REGEX = "[\\s,]+".toRegex()
private const val MAX_IMPORTED_SA_COUNT = 200

fun splitLegacyApiKeys(raw: String): List<String> =
    raw.split(SPLIT_API_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

fun legacyApiKeyId(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    val hex = digest.take(6).joinToString("") { "%02x".format(it) }
    return "legacy:$hex"
}

/** 仅用于展示的脱敏尾号预览，不暴露前缀或完整密钥。 */
fun maskApiKeyPreview(key: String): String {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.length >= 12) "••••${trimmed.takeLast(4)}" else "••••"
}

fun ApiKeyEntry.displayLabel(index: Int): String =
    label.trim().ifEmpty {
        maskApiKeyPreview(key).ifEmpty { "Key #${index + 1}" }
    }

fun VertexServiceAccount.displayLabel(index: Int): String =
    label.trim().ifEmpty {
        projectId.trim().ifEmpty {
            serviceAccountEmail.trim().substringBefore('@').ifEmpty { "SA #${index + 1}" }
        }
    }

fun VertexServiceAccount.resolvedLocation(defaultLocation: String): String =
    location.trim().ifEmpty { defaultLocation.trim().ifEmpty { "us-central1" } }

fun ProviderSetting.effectiveApiKeyEntries(): List<ApiKeyEntry> {
    val (rawLegacy, structured) = when (this) {
        is ProviderSetting.OpenAI -> apiKey to apiKeys
        is ProviderSetting.Google -> apiKey to apiKeys
        is ProviderSetting.Claude -> apiKey to apiKeys
        else -> return emptyList()
    }
    if (structured.isNotEmpty()) return structured
    return splitLegacyApiKeys(rawLegacy).map { k ->
        ApiKeyEntry(id = legacyApiKeyId(k), key = k)
    }
}

fun ProviderSetting.Google.effectiveServiceAccounts(): List<VertexServiceAccount> {
    if (serviceAccounts.isNotEmpty()) return serviceAccounts
    val proj = projectId.trim()
    val email = serviceAccountEmail.trim()
    val key = privateKey.trim()
    if (proj.isEmpty() && email.isEmpty() && key.isEmpty()) return emptyList()
    return listOf(
        VertexServiceAccount(
            id = "legacy",
            projectId = proj,
            serviceAccountEmail = email,
            privateKey = key,
            location = "",
            enabled = true,
        )
    )
}

fun ProviderSetting.rotationStrategyOrDefault(): CredentialRotationStrategy = when (this) {
    is ProviderSetting.OpenAI -> rotationStrategy
    is ProviderSetting.Google -> rotationStrategy
    is ProviderSetting.Claude -> rotationStrategy
    else -> CredentialRotationStrategy.ROUND_ROBIN
}

/**
 * 解析单条 JSON 对象、JSON 数组 `[{...}]`，或连续/换行分隔的多段服务账号 JSON。
 */
fun parseVertexServiceAccountJsons(raw: String): VertexSaImportResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return VertexSaImportResult(emptyList(), 0)

    fun extractAccount(obj: JsonObject): VertexServiceAccount? {
        val projectId = (obj["project_id"] ?: obj["projectId"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val email = (obj["client_email"] ?: obj["serviceAccountEmail"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val privateKey = (obj["private_key"] ?: obj["privateKey"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (projectId.isEmpty() || email.isEmpty() || privateKey.isEmpty()) return null
        val location = obj["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val label = obj["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return VertexServiceAccount(
            label = label,
            projectId = projectId,
            serviceAccountEmail = email,
            privateKey = privateKey,
            location = location,
            enabled = true,
        )
    }

    val direct = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    if (direct is JsonObject) {
        val acc = extractAccount(direct)
        return if (acc != null) VertexSaImportResult(listOf(acc), 0) else VertexSaImportResult(emptyList(), 1)
    }
    if (direct is JsonArray) {
        val accounts = mutableListOf<VertexServiceAccount>()
        var skipped = 0
        for (element in direct.take(MAX_IMPORTED_SA_COUNT)) {
            val acc = (element as? JsonObject)?.let(::extractAccount)
            if (acc != null) accounts += acc else skipped++
        }
        return VertexSaImportResult(accounts, skipped)
    }

    val ranges = scanTopLevelJsonObjects(trimmed) ?: return VertexSaImportResult(emptyList(), 1)
    if (ranges.isEmpty()) return VertexSaImportResult(emptyList(), 1)
    val accounts = mutableListOf<VertexServiceAccount>()
    var skipped = 0
    for (range in ranges) {
        val obj = runCatching { json.parseToJsonElement(trimmed.substring(range)) as? JsonObject }.getOrNull()
        val acc = obj?.let(::extractAccount)
        if (acc != null) accounts += acc else skipped++
    }
    return VertexSaImportResult(accounts, skipped)
}

private fun scanTopLevelJsonObjects(input: String): List<IntRange>? {
    val ranges = mutableListOf<IntRange>()
    var i = 0
    var commaSinceLast = false
    while (i < input.length) {
        val c = input[i]
        when {
            c.isWhitespace() -> i++
            c == ',' -> {
                if (ranges.isEmpty() || commaSinceLast) return null
                commaSinceLast = true
                i++
            }
            c == '{' -> {
                if (ranges.size >= MAX_IMPORTED_SA_COUNT) break
                val end = findJsonObjectEnd(input, i) ?: return null
                ranges += i..end
                commaSinceLast = false
                i = end + 1
            }
            else -> return null
        }
    }
    if (commaSinceLast) return null
    return ranges
}

private fun findJsonObjectEnd(input: String, start: Int): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until input.length) {
        val c = input[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> depth++
            '}', ']' -> {
                depth--
                if (depth == 0) return i
                if (depth < 0) return null
            }
        }
    }
    return null
}

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption
    abstract val customHeaders: List<CustomHeader>

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        customHeaders: List<CustomHeader> = this.customHeaders,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var apiKeys: List<ApiKeyEntry> = emptyList(),
        var rotationStrategy: CredentialRotationStrategy = CredentialRotationStrategy.ROUND_ROBIN,
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
        var responsesPath: String = "/responses",
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                balanceOption = balanceOption,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var apiKeys: List<ApiKeyEntry> = emptyList(),
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
        var serviceAccounts: List<VertexServiceAccount> = emptyList(),
        var rotationStrategy: CredentialRotationStrategy = CredentialRotationStrategy.ROUND_ROBIN,
        var useInteractionsApi: Boolean = false,
        /** Vertex streaming only: ask Gemini 3+ to stream function-call arguments (`partialArgs`). */
        var streamFunctionCallArguments: Boolean = true,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var apiKeys: List<ApiKeyEntry> = emptyList(),
        var rotationStrategy: CredentialRotationStrategy = CredentialRotationStrategy.ROUND_ROBIN,
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("codex")
    data class Codex(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Codex",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("devin")
    data class Devin(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Devin",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        val clientVersion: String = "3000.10.23",
        val deviceSeed: String = "",
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("antigravity")
    data class Antigravity(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Antigravity",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        /** 上报的 Antigravity 客户端版本；空 = 自动（auto-updater manifest，取不到用 2.9.1）。 */
        val clientVersion: String = "",
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}
