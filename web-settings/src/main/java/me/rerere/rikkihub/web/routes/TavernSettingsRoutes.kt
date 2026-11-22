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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.tavern.RegexCheck
import me.rerere.rikkihub.data.ai.tavern.TavernConversationState
import me.rerere.rikkihub.data.ai.tavern.TavernRegexes
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.AuthorsNoteDefaults
import me.rerere.rikkihub.data.model.withVariable
import me.rerere.rikkihub.data.model.withoutVariable
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.ExtensionPromptType
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WI_MAX_SCAN_DEPTH
import me.rerere.tavern.worldinfo.WorldInfoSettings
import kotlin.uuid.Uuid

private const val MAX_REGEX_SAMPLE_CHARS = 50_000
private const val MAX_VARIABLE_NAME_CHARS = 200
private const val MAX_VARIABLE_TEXT_CHARS = 1_000_000
private const val MAX_NICKNAME_CHARS = 100
private const val MAX_PERSONA_CHARS = 200_000

/** 作者注释各字段的取值范围检查；越界 400，不静默夹住。 */
internal fun validateAuthorsNote(depth: Int, position: Int, role: Int, interval: Int) {
    if (depth < 0) throw BadRequestException("depth must be >= 0")
    if (interval < 0) throw BadRequestException("interval must be >= 0")
    val positions = setOf(ExtensionPromptType.IN_PROMPT, ExtensionPromptType.IN_CHAT, ExtensionPromptType.BEFORE_PROMPT)
    if (position !in positions) throw BadRequestException("position must be one of $positions")
    if (PromptRole.entries.none { it.code == role }) throw BadRequestException("role must be 0, 1 or 2")
}

/** 全局世界书设置的取值范围检查（同 App 设置页的输入范围）。 */
internal fun validateWorldInfoSettings(s: WorldInfoSettings) {
    if (s.depth !in 0..WI_MAX_SCAN_DEPTH) throw BadRequestException("world_info_depth must be 0..$WI_MAX_SCAN_DEPTH")
    if (s.minActivations < 0) throw BadRequestException("world_info_min_activations must be >= 0")
    if (s.minActivationsDepthMax < 0) throw BadRequestException("world_info_min_activations_depth_max must be >= 0")
    if (s.budget !in 1..100) throw BadRequestException("world_info_budget must be 1..100")
    if (s.budgetCap < 0) throw BadRequestException("world_info_budget_cap must be >= 0")
    if (s.maxRecursionSteps < 0) throw BadRequestException("world_info_max_recursion_steps must be >= 0")
    if (s.characterStrategy !in 0..2) throw BadRequestException("world_info_character_strategy must be 0..2")
}

internal fun mergeWorldInfoSettings(current: WorldInfoSettings, patch: JsonObject): WorldInfoSettings {
    val base = JsonInstant.encodeToJsonElement(WorldInfoSettings.serializer(), current) as JsonObject
    val merged = try {
        JsonInstant.decodeFromJsonElement(WorldInfoSettings.serializer(), mergeJsonPatch(base, patch))
    } catch (e: IllegalArgumentException) {
        throw BadRequestException("Invalid world info settings")
    }
    validateWorldInfoSettings(merged)
    return merged
}

internal fun mergeAuthorsNoteDefaults(current: AuthorsNoteDefaults, patch: JsonObject): AuthorsNoteDefaults {
    val base = JsonInstant.encodeToJsonElement(AuthorsNoteDefaults.serializer(), current) as JsonObject
    val merged = try {
        JsonInstant.decodeFromJsonElement(AuthorsNoteDefaults.serializer(), mergeJsonPatch(base, patch))
    } catch (e: IllegalArgumentException) {
        throw BadRequestException("Invalid author's note")
    }
    validateAuthorsNote(merged.depth, merged.position, merged.role, merged.interval)
    return merged
}

private suspend fun requireConversation(conversationRepo: ConversationRepository, id: Uuid) {
    if (!conversationRepo.existsConversationById(id)) throw NotFoundException("Conversation not found")
}

/** 变量名与文字的长度限制；名字去掉首尾空白后不能为空。 */
internal fun checkedVariableName(raw: JsonElement?): String {
    val name = patchString(raw ?: throw BadRequestException("name is required"), "name").trim()
    if (name.isEmpty()) throw BadRequestException("name must not be blank")
    if (name.length > MAX_VARIABLE_NAME_CHARS) throw BadRequestException("name is too long")
    return name
}

internal fun checkedVariableText(raw: JsonElement?): String {
    val text = patchString(raw ?: throw BadRequestException("text is required"), "text")
    if (text.length > MAX_VARIABLE_TEXT_CHARS) throw BadRequestException("text is too long")
    return text
}

private fun TavernConversationState.NoteFields.toJson(own: Boolean): JsonObject = buildJsonObject {
    put("prompt", prompt)
    put("interval", interval)
    put("depth", depth)
    put("position", position)
    put("role", role.code)
    put("own", own)
}

/**
 * 全局酒馆设置（世界书、作者注释默认值、全局正则、全局变量）与正则工具。
 * 读取走设置快照（`tavern` 键）；这里只管写，且每次都落在最新设置上。
 */
fun Route.tavernSettingsRoutes(settingsStore: SettingsStore, tavernRuntime: TavernRuntime?) {
    route("/tavern") {
        put("/world-info") {
            val patch = requireJsonObject(call.receive<JsonElement>(), "World info settings")
            lateinit var next: WorldInfoSettings
            settingsStore.update { settings ->
                next = mergeWorldInfoSettings(settings.tavern.worldInfo, patch)
                settings.copy(tavern = settings.tavern.copy(worldInfo = next))
            }
            call.respond(HttpStatusCode.OK, JsonInstant.encodeToJsonElement(WorldInfoSettings.serializer(), next))
        }

        delete("/world-info") {
            settingsStore.update { settings -> settings.copy(tavern = settings.tavern.copy(worldInfo = WorldInfoSettings())) }
            call.respond(HttpStatusCode.NoContent)
        }

        // 用户设定：昵称（进 `{{user}}`）和设定描述（进提示词的 personaDescription 标记，宏 `{{persona}}` 也读它）
        put("/persona") {
            val body = requireJsonObject(call.receive<JsonElement>(), "Persona")
            body.keys.firstOrNull { it != "nickname" && it != "description" }?.let { throw BadRequestException("Unknown field: $it") }
            val nickname = body["nickname"]?.let { patchString(it, "nickname").trim() }
            val description = body["description"]?.let { patchString(it, "description") }
            if (nickname != null && nickname.length > MAX_NICKNAME_CHARS) throw BadRequestException("nickname is too long")
            if (description != null && description.length > MAX_PERSONA_CHARS) throw BadRequestException("description is too long")
            lateinit var latest: me.rerere.rikkihub.data.datastore.Settings
            settingsStore.update { settings ->
                var next = settings
                if (nickname != null) next = next.copy(displaySetting = next.displaySetting.copy(userNickname = nickname))
                if (description != null) next = next.copy(tavern = next.tavern.copy(personaDescription = description))
                latest = next
                next
            }
            call.respond(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("nickname", latest.displaySetting.userNickname)
                    put("description", latest.tavern.personaDescription)
                },
            )
        }

        put("/authors-note") {
            val patch = requireJsonObject(call.receive<JsonElement>(), "Author's note")
            lateinit var next: AuthorsNoteDefaults
            settingsStore.update { settings ->
                next = mergeAuthorsNoteDefaults(settings.tavern.authorsNote, patch)
                settings.copy(tavern = settings.tavern.copy(authorsNote = next))
            }
            call.respond(HttpStatusCode.OK, JsonInstant.encodeToJsonElement(AuthorsNoteDefaults.serializer(), next))
        }

        delete("/authors-note") {
            settingsStore.update { settings -> settings.copy(tavern = settings.tavern.copy(authorsNote = AuthorsNoteDefaults())) }
            call.respond(HttpStatusCode.NoContent)
        }

        route("/regex") {
            post {
                val script = regexScriptForCreate(requireJsonObject(call.receive<JsonElement>(), "Regex script"))
                settingsStore.update { settings ->
                    if (settings.tavern.regexScripts.any { it.id == script.id }) throw BadRequestException("Regex script id already exists")
                    settings.copy(tavern = settings.tavern.copy(regexScripts = settings.tavern.regexScripts + script))
                }
                call.respond(HttpStatusCode.Created, script.toJson())
            }

            put("/order") {
                val body = requireJsonObject(call.receive<JsonElement>(), "Request")
                val ids = (body["ids"] as? kotlinx.serialization.json.JsonArray)
                    ?.map { patchString(it, "ids") } ?: throw BadRequestException("ids is required")
                settingsStore.update { settings ->
                    val current = settings.tavern.regexScripts
                    val byId = current.associateBy { it.id }
                    val ordered = ids.mapNotNull { byId[it] }
                    // 请求里没提到的脚本（并发新增的）排在后面，不丢
                    val rest = current.filter { it.id !in ids.toSet() }
                    settings.copy(tavern = settings.tavern.copy(regexScripts = ordered + rest))
                }
                call.respond(HttpStatusCode.OK, buildJsonObject { put("status", "ok") })
            }

            put("/{id}") {
                val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
                val patch = requireJsonObject(call.receive<JsonElement>(), "Regex script")
                lateinit var merged: RegexScript
                settingsStore.update { settings ->
                    val existing = settings.tavern.regexScripts.find { it.id == id } ?: throw NotFoundException("Regex script not found")
                    merged = regexScriptMerged(existing, patch)
                    settings.copy(tavern = settings.tavern.copy(
                        regexScripts = settings.tavern.regexScripts.map { if (it.id == id) merged else it },
                    ))
                }
                call.respond(HttpStatusCode.OK, merged.toJson())
            }

            delete("/{id}") {
                val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
                settingsStore.update { settings ->
                    if (settings.tavern.regexScripts.none { it.id == id }) throw NotFoundException("Regex script not found")
                    settings.copy(tavern = settings.tavern.copy(regexScripts = settings.tavern.regexScripts.filter { it.id != id }))
                }
                call.respond(HttpStatusCode.NoContent)
            }

            if (tavernRuntime != null) {
                // 查找串能不能编译：{"findRegex": "...", "substituteRegex": 0}
                post("/check") {
                    val body = requireJsonObject(call.receive<JsonElement>(), "Request")
                    val find = patchString(body["findRegex"] ?: throw BadRequestException("findRegex is required"), "findRegex")
                    val substitute = body["substituteRegex"]?.let { patchInt(it, "substituteRegex") } ?: 0
                    val result = TavernRegexes(tavernRuntime).checkFindRegex(find, substitute)
                    call.respond(HttpStatusCode.OK, buildJsonObject {
                        when (result) {
                            RegexCheck.Empty -> put("status", "empty")
                            RegexCheck.Ok -> put("status", "ok")
                            RegexCheck.Skipped -> put("status", "skipped")
                            is RegexCheck.Invalid -> {
                                put("status", "invalid")
                                result.message?.let { put("message", it) }
                            }
                        }
                    })
                }

                // 试运行：{"script": {...}, "sample": "...", "assistantId": "uuid"?}，结果和真实使用时一致
                post("/preview") {
                    val body = requireJsonObject(call.receive<JsonElement>(), "Request")
                    val script = RegexScript.fromJson(requireJsonObject(body["script"] ?: throw BadRequestException("script is required"), "script"))
                    val sample = patchString(body["sample"] ?: throw BadRequestException("sample is required"), "sample")
                    if (sample.length > MAX_REGEX_SAMPLE_CHARS) throw BadRequestException("sample is too long")
                    val settings = settingsStore.settingsFlow.value
                    val assistant = (body["assistantId"] as? JsonPrimitive)?.takeIf { it.isString }?.let { raw ->
                        val id = runCatching { Uuid.parse(raw.content) }.getOrNull() ?: throw BadRequestException("Invalid assistantId")
                        settings.assistants.find { it.id == id }
                    }
                    val text = TavernRegexes(tavernRuntime).preview(script, sample, settings, assistant)
                    call.respond(HttpStatusCode.OK, buildJsonObject { put("text", text) })
                }
            }
        }

        // 全局变量：{"name": "...", "text": "..."}（值怎么存按 App 的规则：原来是数字/布尔且新文字也是就保持类型）
        put("/variables") {
            val body = requireJsonObject(call.receive<JsonElement>(), "Request")
            val name = checkedVariableName(body["name"])
            val text = checkedVariableText(body["text"])
            settingsStore.update { settings ->
                settings.copy(tavern = settings.tavern.copy(globalVariables = settings.tavern.globalVariables.withVariable(name, text)))
            }
            call.respond(HttpStatusCode.OK, buildJsonObject { put("status", "ok") })
        }

        delete("/variables") {
            val name = checkedVariableName(call.request.queryParameters["name"]?.let { JsonPrimitive(it) })
            settingsStore.update { settings ->
                settings.copy(tavern = settings.tavern.copy(globalVariables = settings.tavern.globalVariables.withoutVariable(name)))
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * 会话里的酒馆状态：作者注释与聊天变量（`tavern_metadata`）。写入都对**最新**的会话状态做变换
 * （生成中会把宏改过的变量写回，整份覆盖会丢）。
 */
fun Route.tavernConversationRoutes(settingsStore: SettingsStore, chatService: ChatService, conversationRepo: ConversationRepository) {
    route("/conversations/{id}/tavern") {
        get {
            val id = parseConversationId(call.parameters["id"])
            val conversation = conversationRepo.getConversationById(id) ?: throw NotFoundException("Conversation not found")
            val settings = settingsStore.settingsFlow.value
            val metadata = conversation.tavernMetadata
            call.respond(buildJsonObject {
                put("note", TavernConversationState.readNoteFields(metadata, settings.tavern.authorsNote)
                    .toJson(own = TavernConversationState.hasOwnNote(metadata)))
                put("variables", (metadata[TavernConversationState.VARIABLES] as? JsonObject) ?: JsonObject(emptyMap()))
            })
        }

        // 作者注释：只写请求里出现的字段（其余沿用当前值），五个键一起写进会话，同酒馆
        put("/note") {
            val id = parseConversationId(call.parameters["id"])
            val patch = requireJsonObject(call.receive<JsonElement>(), "Author's note")
            val defaults = settingsStore.settingsFlow.value.tavern.authorsNote
            requireConversation(conversationRepo, id)
            chatService.updateTavernMetadata(id) { metadata ->
                TavernConversationState.writeNoteFields(metadata, mergedNoteFields(TavernConversationState.readNoteFields(metadata, defaults), patch))
            }
            call.respond(HttpStatusCode.OK, buildJsonObject { put("status", "ok") })
        }

        // 去掉会话自己的作者注释，回到跟随全局默认
        delete("/note") {
            val id = parseConversationId(call.parameters["id"])
            requireConversation(conversationRepo, id)
            chatService.updateTavernMetadata(id) { TavernConversationState.clearNoteFields(it) }
            call.respond(HttpStatusCode.NoContent)
        }

        put("/variables") {
            val id = parseConversationId(call.parameters["id"])
            val body = requireJsonObject(call.receive<JsonElement>(), "Request")
            val name = checkedVariableName(body["name"])
            val text = checkedVariableText(body["text"])
            requireConversation(conversationRepo, id)
            chatService.updateTavernMetadata(id) { metadata ->
                val current = (metadata[TavernConversationState.VARIABLES] as? JsonObject) ?: JsonObject(emptyMap())
                TavernConversationState.writeVariables(metadata, current.withVariable(name, text))
            }
            call.respond(HttpStatusCode.OK, buildJsonObject { put("status", "ok") })
        }

        delete("/variables") {
            val id = parseConversationId(call.parameters["id"])
            val name = checkedVariableName(call.request.queryParameters["name"]?.let { JsonPrimitive(it) })
            requireConversation(conversationRepo, id)
            chatService.updateTavernMetadata(id) { metadata ->
                val current = (metadata[TavernConversationState.VARIABLES] as? JsonObject) ?: JsonObject(emptyMap())
                TavernConversationState.writeVariables(metadata, current.withoutVariable(name))
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** 请求里出现的作者注释字段盖在 [current] 上，其余沿用；取值范围检查不过就 400。 */
internal fun mergedNoteFields(current: TavernConversationState.NoteFields, patch: JsonObject): TavernConversationState.NoteFields {
    val role = patch["role"]?.let { patchInt(it, "role") } ?: current.role.code
    val next = TavernConversationState.NoteFields(
        prompt = patch["prompt"]?.let { patchString(it, "prompt") } ?: current.prompt,
        interval = patch["interval"]?.let { patchInt(it, "interval") } ?: current.interval,
        depth = patch["depth"]?.let { patchInt(it, "depth") } ?: current.depth,
        position = patch["position"]?.let { patchInt(it, "position") } ?: current.position,
        role = PromptRole.fromCode(role),
    )
    validateAuthorsNote(next.depth, next.position, role, next.interval)
    return next
}
