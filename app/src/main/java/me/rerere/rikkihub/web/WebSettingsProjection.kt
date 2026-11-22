package me.rerere.rikkihub.web

import me.rerere.rikkihub.web.routes.entriesJson
import me.rerere.rikkihub.web.routes.summaryJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.legacyApiKeyId
import me.rerere.ai.provider.maskApiKeyPreview
import me.rerere.ai.provider.splitLegacyApiKeys
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import java.net.URI

/**
 * `WebSettingsProjection` — the ONLY way `Settings` (or any
 * settings-owned object) may be serialized to a Web client.
 *
 * The full `Settings` graph contains live credentials: provider apiKey /
 * Google service-account privateKey, per-model and provider customHeaders /
 * customBodies, MCP commonOptions.headers + oauth secrets, search-service
 * apiKey/passwords, TTS/ASR apiKeys, WebDAV/S3 secrets, the HTTP proxy
 * password and the web-server access password itself. None of those may ever
 * reach the browser (events stream, REST responses) — including as "harmless"
 * echoes after an update.
 *
 * Projection contract (consumed by the web-ui + presence-aware merge):
 * - Secret string field  → `"field": ""` + `"fieldConfigured": <bool>` sibling.
 * - `customHeaders[]`    → `{name, value:"", valueConfigured:<bool>}`.
 * - `customBodies[]`     → `{key, value:null, valueConfigured:<bool>}`.
 * - MCP `commonOptions.headers` (Pair list) → `{first, second:"", valueConfigured:<bool>}`.
 * - MCP `oauth`          → clientSecret/accessToken/refreshToken masked to
 *   null + `*Configured` flags; endpoints/enabled/isAuthorized stay visible.
 * - `networkSetting.proxyUrl` → userinfo stripped (may embed credentials);
 *   `proxyPassword` masked + flag.
 * - `providerOverwrite`  → same generic object walk (it is a ProviderSetting).
 *
 * Write-back (implemented in SettingsMutationUtils): absent key / null value /
 * `valueConfigured:true` with empty value ⇒ PRESERVE stored secret; `""` ⇒
 * explicit clear; non-empty ⇒ explicit set.
 *
 * Pure JVM/JSON — every rule is unit-tested without Android or a server.
 */

/** String-valued keys that always carry credential material, anywhere in the tree. */
private val WEB_SECRET_STRING_FIELDS = setOf(
    "apiKey",
    "privateKey",
    "password",
    "proxyPassword",
    "secretAccessKey",
    "accessKeyId",
    "webServerAccessPassword",
)

/** Object-valued keys whose value is itself a secret-bearing structure. */
private val WEB_SECRET_OBJECT_FIELDS = setOf(
    "providerOverwrite",
)

/** Nullable secret keys inside `oauth` state objects. */
private val WEB_OAUTH_SECRET_FIELDS = setOf(
    "clientSecret",
    "accessToken",
    "refreshToken",
)

/** List keys whose elements are `{name|first, value|second}` credential pairs. */
private val WEB_SECRET_PAIR_LIST_FIELDS = setOf(
    "customHeaders",   // ProviderSetting/Model: {name, value}
    "headers",         // MCP commonOptions: Pair {first, second}
    "envVars",         // CLI agents: customCliAgents[] / builtInCliEnvOverrides[], {name, value}
)

/** List keys whose elements are `{key, value: JsonElement}` opaque overrides. */
private const val CUSTOM_BODIES_FIELD = "customBodies"

private const val CONFIGURED_SUFFIX = "Configured"

// ---------- public API ----------

/**
 * Settings keys whose content is user text with an open schema (prompt entries, lorebooks, the tavern block with
 * its variables, a preset's imported extras): none of it is a credential, and walking it by key name would mask a
 * variable that happens to be called `password`. They are projected by hand instead.
 */
private val WEB_OPEN_CONTENT_KEYS = setOf("promptLibrary", "lorebooks", "tavern", "assistants")

/** Project a full Settings snapshot for events/REST: secret-free. */
fun projectSettingsForWeb(settings: Settings): JsonObject {
    val tree = JsonInstant.encodeToJsonElement(Settings.serializer(), settings).jsonObject
    val projected = projectSettingsTree(JsonObject(tree.filterKeys { it !in WEB_OPEN_CONTENT_KEYS }))
    return buildJsonObject {
        for ((key, value) in projected) put(key, value)
        // 助手、提示词库、酒馆设置照原生数据下发（预设 JSON、库条目 JSON）。创造助手和普通助手一样可见。
        put("assistants", projectAssistantListForWeb(settings.assistants))
        tree["promptLibrary"]?.let { put("promptLibrary", it) }
        // 世界书只带摘要（名称、条目数、内容指纹）；整本和条目走 /api/lorebooks/{id}
        val bookTrees = tree["lorebooks"] as? JsonArray
        put("lorebooks", JsonArray(settings.lorebooks.mapIndexed { index, book ->
            // 快照编码时条目已经编过一份，指纹直接用它
            val encoded = ((bookTrees?.getOrNull(index) as? JsonObject)?.get("entries") as? JsonArray) ?: book.entriesJson()
            book.summaryJson(encoded)
        }))
        tree["tavern"]?.let { put("tavern", it) }
        // 去八股的规则包可能很大，快照里不带；设置页走 /api/qubagu/config
        (projected["qubagu"] as? JsonObject)?.let { put("qubagu", JsonObject(it - "packs")) }
    }
}

fun projectProviderForWeb(provider: ProviderSetting): JsonObject =
    projectSettingsTree(JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), provider).jsonObject)

fun projectModelForWeb(model: Model): JsonObject =
    projectSettingsTree(JsonInstant.encodeToJsonElement(Model.serializer(), model).jsonObject)

fun projectMcpServerForWeb(server: McpServerConfig): JsonObject =
    projectSettingsTree(JsonInstant.encodeToJsonElement(McpServerConfig.serializer(), server).jsonObject)

fun projectAssistantForWeb(assistant: Assistant): JsonObject {
    val tree = JsonInstant.encodeToJsonElement(Assistant.serializer(), assistant).jsonObject
    val projected = projectSettingsTree(JsonObject(tree - "promptPreset"))
    // 预设 JSON（提示词、顺序、选项、助手正则、导入时带进来的字段）是用户内容，不按键名遍历
    return buildJsonObject {
        for ((key, value) in projected) put(key, value)
        tree["promptPreset"]?.let { put("promptPreset", it) }
    }
}

fun projectAssistantListForWeb(assistants: List<Assistant>): JsonArray =
    JsonArray(assistants.map { projectAssistantForWeb(it) })

// ---------- tree walk ----------

/**
 * Recursive, field-name-driven projection. Deterministic: unknown containers
 * are recursed; unknown scalar fields pass through unchanged — fail-open for
 * new non-secret fields is intentional (a hidden field the client can't edit
 * would brick legitimate settings), while the secret set is closed and
 * centrally listed above.
 */
internal fun projectSettingsTree(tree: JsonObject): JsonObject = buildJsonObject {
    val providerType = (tree["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val isCredentialProvider = providerType in WEB_CREDENTIAL_PROVIDER_TYPES
    var rawApiKey = ""
    var projectedApiKeys = JsonArray(emptyList())
    var sawApiKeys = false
    var rawPrivateKey = ""
    var rawProjectId = ""
    var rawSaEmail = ""
    var projectedServiceAccounts = JsonArray(emptyList())
    var sawServiceAccounts = false

    for ((key, value) in tree) {
        when {
            key in WEB_SECRET_STRING_FIELDS -> {
                val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                if (key == "apiKey") rawApiKey = raw
                if (key == "privateKey") rawPrivateKey = raw
                put(key, JsonPrimitive(""))
                put(key + CONFIGURED_SUFFIX, JsonPrimitive(value.hasSecretContent()))
            }

            key == "apiKeys" && value is JsonArray -> {
                sawApiKeys = true
                projectedApiKeys = projectApiKeyEntries(value)
                put(key, projectedApiKeys)
            }

            key == "serviceAccounts" && value is JsonArray -> {
                sawServiceAccounts = true
                projectedServiceAccounts = projectServiceAccounts(value)
                put(key, projectedServiceAccounts)
            }

            key in WEB_SECRET_OBJECT_FIELDS && value is JsonObject -> {
                put(key, projectSettingsTree(value))
            }

            key in WEB_SECRET_PAIR_LIST_FIELDS && value is JsonArray -> {
                put(key, projectSecretPairList(value, key))
            }

            key == CUSTOM_BODIES_FIELD && value is JsonArray -> {
                put(key, projectCustomBodies(value))
            }

            key == "oauth" && value is JsonObject -> {
                put(key, projectOAuthState(value))
            }

            key == "proxyUrl" && value is JsonPrimitive -> {
                put(key, JsonPrimitive(sanitizeUrlUserinfo(value.content)))
            }

            value is JsonObject -> put(key, projectSettingsTree(value))
            value is JsonArray -> put(
                key,
                JsonArray(value.map { element -> if (element is JsonObject) projectSettingsTree(element) else element })
            )

            else -> {
                if (isCredentialProvider) {
                    val str = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    if (key == "projectId") rawProjectId = str
                    if (key == "serviceAccountEmail") rawSaEmail = str
                }
                put(key, value)
            }
        }
    }

    if (isCredentialProvider) {
        val effectiveApiKeys = if (projectedApiKeys.isEmpty() && rawApiKey.isNotBlank()) {
            JsonArray(
                splitLegacyApiKeys(rawApiKey).map { legacyKey ->
                    buildJsonObject {
                        put("id", JsonPrimitive(legacyApiKeyId(legacyKey)))
                        put("label", JsonPrimitive(""))
                        put("enabled", JsonPrimitive(true))
                        put("key", JsonPrimitive(""))
                        put("keyConfigured", JsonPrimitive(true))
                        put("keyPreview", JsonPrimitive(maskApiKeyPreview(legacyKey)))
                    }
                }
            )
        } else {
            projectedApiKeys
        }
        if (!sawApiKeys || (projectedApiKeys.isEmpty() && effectiveApiKeys.isNotEmpty())) {
            put("apiKeys", effectiveApiKeys)
        }
        val anyApiKeyConfigured = rawApiKey.isNotEmpty() || effectiveApiKeys.any {
            (it as? JsonObject)?.get("keyConfigured")?.let { flag ->
                (flag as? JsonPrimitive)?.content == "true"
            } == true
        }
        put("apiKeyConfigured", JsonPrimitive(anyApiKeyConfigured))

        if (providerType == "google") {
            val effectiveServiceAccounts = if (
                projectedServiceAccounts.isEmpty() &&
                (rawProjectId.isNotBlank() || rawSaEmail.isNotBlank() || rawPrivateKey.isNotBlank())
            ) {
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", JsonPrimitive("legacy"))
                            put("label", JsonPrimitive(""))
                            put("projectId", JsonPrimitive(rawProjectId.trim()))
                            put("serviceAccountEmail", JsonPrimitive(rawSaEmail.trim()))
                            put("location", JsonPrimitive(""))
                            put("enabled", JsonPrimitive(true))
                            put("privateKey", JsonPrimitive(""))
                            put("privateKeyConfigured", JsonPrimitive(rawPrivateKey.isNotBlank()))
                        }
                    )
                )
            } else {
                projectedServiceAccounts
            }
            if (!sawServiceAccounts || (projectedServiceAccounts.isEmpty() && effectiveServiceAccounts.isNotEmpty())) {
                put("serviceAccounts", effectiveServiceAccounts)
            }
            val anyPrivateKeyConfigured = rawPrivateKey.isNotEmpty() || effectiveServiceAccounts.any {
                (it as? JsonObject)?.get("privateKeyConfigured")?.let { flag ->
                    (flag as? JsonPrimitive)?.content == "true"
                } == true
            }
            put("privateKeyConfigured", JsonPrimitive(anyPrivateKeyConfigured))
        }
    }
}

private val WEB_CREDENTIAL_PROVIDER_TYPES = setOf("openai", "google", "claude")

private fun projectApiKeyEntries(list: JsonArray): JsonArray = JsonArray(
    list.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val rawKey = (obj["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        buildJsonObject {
            put("id", obj["id"] ?: JsonPrimitive(""))
            put("label", obj["label"] ?: JsonPrimitive(""))
            put("enabled", obj["enabled"] ?: JsonPrimitive(true))
            put("key", JsonPrimitive(""))
            put("keyConfigured", JsonPrimitive(rawKey.isNotEmpty()))
            put("keyPreview", JsonPrimitive(maskApiKeyPreview(rawKey)))
        }
    }
)

private fun projectServiceAccounts(list: JsonArray): JsonArray = JsonArray(
    list.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val rawPrivateKey = (obj["privateKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        buildJsonObject {
            put("id", obj["id"] ?: JsonPrimitive(""))
            put("label", obj["label"] ?: JsonPrimitive(""))
            put("projectId", obj["projectId"] ?: JsonPrimitive(""))
            put("serviceAccountEmail", obj["serviceAccountEmail"] ?: JsonPrimitive(""))
            put("location", obj["location"] ?: JsonPrimitive(""))
            put("enabled", obj["enabled"] ?: JsonPrimitive(true))
            put("privateKey", JsonPrimitive(""))
            put("privateKeyConfigured", JsonPrimitive(rawPrivateKey.isNotEmpty()))
        }
    }
)

private fun JsonElement.hasSecretContent(): Boolean =
    (this as? JsonPrimitive)?.let { it.isString && it.content.isNotEmpty() } == true

/**
 * Project `[{name,value}]` / `[{first,second}]` credential-pair lists:
 * value masked to `""` with `valueConfigured` flag. Elements in any other
 * shape are dropped (fail-closed for secrets).
 */
private fun projectSecretPairList(list: JsonArray, field: String): JsonArray {
    val nameKey = if (field == "headers") "first" else "name"
    val valueKey = if (field == "headers") "second" else "value"
    return JsonArray(
        list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (!obj.containsKey(nameKey)) return@mapNotNull null
            buildJsonObject {
                for ((k, v) in obj) {
                    if (k == valueKey) {
                        put(valueKey, JsonPrimitive(""))
                        put("valueConfigured", JsonPrimitive(v.hasSecretContent()))
                    } else {
                        put(k, v)
                    }
                }
                if (!obj.containsKey(valueKey)) {
                    put(valueKey, JsonPrimitive(""))
                    put("valueConfigured", JsonPrimitive(false))
                }
            }
        }
    )
}

/** Project `[{key, value:JsonElement}]` overrides — values may carry secrets. */
private fun projectCustomBodies(list: JsonArray): JsonArray =
    JsonArray(
        list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val keyValue = obj["key"] ?: return@mapNotNull null
            buildJsonObject {
                put("key", keyValue)
                put("value", JsonNull)
                put("valueConfigured", JsonPrimitive((obj["value"] ?: JsonNull) != JsonNull))
            }
        }
    )

/** Project an MCP `oauth` state object: secrets → null + flags, status stays. */
private fun projectOAuthState(oauth: JsonObject): JsonObject = buildJsonObject {
    for ((key, value) in oauth) {
        if (key in WEB_OAUTH_SECRET_FIELDS) {
            put(key, JsonNull)
            put(key + CONFIGURED_SUFFIX, JsonPrimitive(value.hasSecretContent()))
        } else {
            put(key, value)
        }
    }
}

/** Strip embedded credentials (`scheme://user:pass@host`) from a proxy URL. */
internal fun sanitizeUrlUserinfo(url: String): String {
    if (url.isBlank()) return url
    val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return url
    val userinfo = parsed.userInfo ?: return url
    if (userinfo.isEmpty()) return url
    return runCatching {
        URI(
            parsed.scheme,
            null,               // userInfo dropped
            parsed.host,
            parsed.port,
            parsed.path,
            parsed.query,
            parsed.fragment,
        ).toString()
    }.getOrDefault(url.substringBefore('@').substringBefore("://") + "://" + url.substringAfter('@'))
}
