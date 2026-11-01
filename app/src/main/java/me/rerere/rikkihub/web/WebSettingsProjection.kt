package me.rerere.rikkihub.web

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
 * - Creator resources    → filtered/hidden via [WebCreatorResources] hook.
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
)

/** List keys whose elements are `{key, value: JsonElement}` opaque overrides. */
private const val CUSTOM_BODIES_FIELD = "customBodies"

private const val CONFIGURED_SUFFIX = "Configured"

// ---------- public API ----------

/** Project a full Settings snapshot for events/REST: secret-free + creator-hidden. */
fun projectSettingsForWeb(settings: Settings): JsonObject {
    val tree = JsonInstant.encodeToJsonElement(Settings.serializer(), settings).jsonObject
    return projectSettingsTree(tree)
}

fun projectProviderForWeb(provider: ProviderSetting): JsonObject =
    projectSettingsTree(JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), provider).jsonObject)

fun projectModelForWeb(model: Model): JsonObject =
    projectSettingsTree(JsonInstant.encodeToJsonElement(Model.serializer(), model).jsonObject)

fun projectMcpServerForWeb(server: McpServerConfig): JsonObject =
    projectSettingsTree(JsonInstant.encodeToJsonElement(McpServerConfig.serializer(), server).jsonObject)

fun projectAssistantForWeb(assistant: Assistant): JsonObject =
    projectSettingsTree(JsonInstant.encodeToJsonElement(Assistant.serializer(), assistant).jsonObject)

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
    for ((key, value) in tree) {
        when {
            key in WEB_SECRET_STRING_FIELDS -> {
                put(key, JsonPrimitive(""))
                put(key + CONFIGURED_SUFFIX, JsonPrimitive(value.hasSecretContent()))
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

            key == "assistants" && value is JsonArray -> {
                // Creator-domain assistants never appear in the web projection.
                put(
                    key,
                    JsonArray(
                        value.filterIsInstance<JsonObject>()
                            .filter { !isCreatorResourceObject(it) }
                            .map { projectSettingsTree(it) }
                    )
                )
            }

            key == "assistantId" && value is JsonPrimitive -> {
                // If the selected assistant were ever a creator id, hide it
                // rather than leak the reserved identifier.
                val raw = runCatching { value.content }.getOrNull()
                val hidden = raw?.let { idString ->
                    runCatching { kotlin.uuid.Uuid.parse(idString) }
                        .map { WebCreatorResources.isCreatorAssistantId(it) }
                        .getOrDefault(false)
                } == true
                put(key, if (hidden) JsonNull else value)
            }

            value is JsonObject -> put(key, projectSettingsTree(value))
            value is JsonArray -> put(
                key,
                JsonArray(value.map { element -> if (element is JsonObject) projectSettingsTree(element) else element })
            )

            else -> put(key, value)
        }
    }
}

private fun JsonElement.hasSecretContent(): Boolean =
    (this as? JsonPrimitive)?.let { it.isString && it.content.isNotEmpty() } == true

private fun isCreatorResourceObject(obj: JsonObject): Boolean {
    val idString = (obj["id"] as? JsonPrimitive)?.content ?: return false
    val id = runCatching { kotlin.uuid.Uuid.parse(idString) }.getOrNull() ?: return false
    return WebCreatorResources.isCreatorAssistantId(id)
}

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
