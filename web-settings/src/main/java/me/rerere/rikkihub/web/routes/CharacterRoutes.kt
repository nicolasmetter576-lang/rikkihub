package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.character.CharacterExportFormat
import me.rerere.rikkihub.data.character.CharacterService
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.repository.CharacterConflictException
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.ConflictException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.PayloadTooLargeException
import me.rerere.tavern.card.CardFormatException
import kotlin.uuid.Uuid

private const val MAX_CARD_FILE_BYTES = 64L * 1024 * 1024
private const val MAX_AVATAR_BYTES = 8L * 1024 * 1024

/**
 * 角色（SillyTavern 角色卡）的网页接口。卡的内容、角色书、卡内正则放行、头像、导入导出和“开始聊天”都在这里，
 * 做的事和 App 的角色页一样，且都落在最新的数据上。
 *
 *   GET    /characters?query=            列表（收藏在前，其余按名字）
 *   POST   /characters {name?}           新建空白角色
 *   POST   /characters/import?name=      body 是卡文件原文（PNG / CHARX / JSON，按内容认）→ {character, report}
 *   GET    /characters/{id}              详情
 *   PUT    /characters/{id}              只改出现的字段（白名单），可带 expectedRevision
 *   DELETE /characters/{id}?deleteBook=  删除；deleteBook=true 时连同卡内世界书（没有别的角色在用）
 *   GET    /characters/{id}/avatar       头像图片
 *   PUT    /characters/{id}/avatar       body 是图片原文（PNG / JPEG / WebP / GIF）
 *   GET    /characters/{id}/export?format=png|json|charx
 *   PUT    /characters/{id}/books        {extraBookIds, embeddedBookId?: null}
 *   PUT    /characters/{id}/regex-allowed {allowed}
 *   POST   /characters/{id}/chat         新建一个带开场白的会话 → {conversationId}
 */
fun Route.characterRoutes(
    settingsStore: SettingsStore,
    repository: CharacterRepository,
    service: CharacterService,
    chatService: ChatService?,
) {
    route("/characters") {
        get {
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            val all = repository.summaries()
            val ids = if (query.isEmpty()) null else repository.search(query)
            val shown = all.filter { ids == null || it.id in ids }
                .sortedWith(compareByDescending<CharacterSummary> { it.fav }.thenBy { it.name.lowercase() })
            call.respond(JsonArray(shown.map { it.webJson() }))
        }

        post {
            val body = requireJsonObject(call.receive<JsonElement>(), "Character")
            val name = body["name"]?.let { patchString(it, "name") }.orEmpty()
            val record = service.createBlank(name)
            call.respond(HttpStatusCode.Created, record.webJson(settingsStore.settingsFlow.value))
        }

        post("/import") {
            val bytes = readBody(call, MAX_CARD_FILE_BYTES, "Character file")
            val name = call.request.queryParameters["name"].orEmpty()
            val imported = try {
                service.import(bytes, name)
            } catch (e: CardFormatException) {
                throw BadRequestException(e.message ?: "This file is not a character card")
            }
            // 详情让界面再取一次：刚建的世界书要等设置快照跟上
            call.respond(
                HttpStatusCode.Created,
                buildJsonObject {
                    put("id", imported.record.id.toString())
                    put("name", imported.record.name)
                    put("report", imported.report.webJson())
                },
            )
        }

        get("/{id}") {
            val record = repository.get(characterId(call)) ?: throw NotFoundException("Character not found")
            call.respond(record.webJson(settingsStore.settingsFlow.value))
        }

        put("/{id}") {
            val id = characterId(call)
            val patch = requireJsonObject(call.receive<JsonElement>(), "Character")
            val updated = updateCharacter(repository, id, expectedRevisionOf(patch)) { it.copy(card = it.card.withWebPatch(patch)) }
            call.respond(updated.webJson(settingsStore.settingsFlow.value))
        }

        delete("/{id}") {
            val id = characterId(call)
            val deleteBook = call.request.queryParameters["deleteBook"] == "true"
            if (repository.get(id) == null) throw NotFoundException("Character not found")
            service.delete(id, deleteBook)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/{id}/avatar") {
            val record = repository.get(characterId(call)) ?: throw NotFoundException("Character not found")
            val file = repository.avatarFile(record) ?: throw NotFoundException("The character has no avatar")
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            // 地址里带着头像文件名当版本：对得上就能长期缓存，对不上（旧地址）每次重新验证
            val cache = if (call.request.queryParameters["v"] == record.avatarPath) "private, max-age=31536000, immutable" else "private, no-cache"
            call.response.header(HttpHeaders.CacheControl, cache)
            call.respondBytes(bytes, ContentType.parse(imageContentType(file.name)))
        }

        put("/{id}/avatar") {
            val id = characterId(call)
            val bytes = readBody(call, MAX_AVATAR_BYTES, "Avatar image")
            val ext = imageExtension(bytes) ?: throw BadRequestException("The avatar must be a PNG, JPEG, WebP or GIF image")
            val updated = try {
                repository.setAvatar(id, bytes, ext)
            } catch (e: NoSuchElementException) {
                throw NotFoundException("Character not found")
            }
            call.respond(updated.webJson(settingsStore.settingsFlow.value))
        }

        get("/{id}/export") {
            val id = characterId(call)
            val format = when (call.request.queryParameters["format"]?.lowercase()) {
                null, "png" -> CharacterExportFormat.PNG
                "json" -> CharacterExportFormat.JSON
                "charx" -> CharacterExportFormat.CHARX
                else -> throw BadRequestException("format must be png, json or charx")
            }
            val exported = try {
                service.export(id, format)
            } catch (e: NoSuchElementException) {
                throw NotFoundException("Character not found")
            }
            call.response.header(HttpHeaders.ContentDisposition, attachmentHeader(exported.fileName))
            call.respondBytes(exported.bytes, ContentType.parse(exported.mimeType))
        }

        put("/{id}/books") {
            val id = characterId(call)
            val body = requireJsonObject(call.receive<JsonElement>(), "Character books")
            val known = settingsStore.settingsFlow.value.lorebooks.map { it.id }.toSet()
            val extra = body["extraBookIds"]?.let { element ->
                val array = element as? JsonArray ?: throw BadRequestException("extraBookIds must be an array of lorebook ids")
                array.map { item ->
                    val bookId = runCatching { Uuid.parse(patchString(item, "extraBookIds[]")) }.getOrNull()
                        ?: throw BadRequestException("extraBookIds contains an invalid id")
                    if (bookId !in known) throw BadRequestException("extraBookIds contains an unknown lorebook id")
                    bookId
                }.distinct()
            }
            // 卡内世界书只能解除（不能换成别的），换书用 extraBookIds
            val detachEmbedded = when (body["embeddedBookId"]) {
                null -> false
                is JsonNull -> true
                else -> throw BadRequestException("embeddedBookId can only be set to null (detach)")
            }
            val updated = updateCharacter(repository, id, null) { r ->
                val embeddedId = if (detachEmbedded) null else r.local.embeddedBookId
                r.copy(local = r.local.copy(embeddedBookId = embeddedId, extraBookIds = (extra ?: r.local.extraBookIds).filter { it != embeddedId }))
            }
            call.respond(updated.webJson(settingsStore.settingsFlow.value))
        }

        put("/{id}/regex-allowed") {
            val id = characterId(call)
            val body = requireJsonObject(call.receive<JsonElement>(), "Regex allowance")
            val allowed = patchBoolean(body["allowed"] ?: throw BadRequestException("allowed is required"), "allowed")
            val record = repository.get(id) ?: throw NotFoundException("Character not found")
            service.setRegexAllowed(id, allowed)
            // 回包按刚写的值算，不等设置快照跟上
            val settings = settingsStore.settingsFlow.value
            val json = record.webJson(settings)
            call.respond(JsonObject(json + ("regexAllowed" to JsonPrimitive(allowed))))
        }

        post("/{id}/chat") {
            val id = characterId(call)
            val chat = chatService ?: throw NotFoundException("Chat is not available")
            if (repository.get(id) == null) throw NotFoundException("Character not found")
            val conversationId = chat.startCharacterConversation(id)
            call.respond(HttpStatusCode.Created, buildJsonObject { put("conversationId", conversationId.toString()) })
        }
    }
}

private fun characterId(call: ApplicationCall): Uuid = call.parameters["id"].toUuid("characterId")

/** 改一个角色：不存在 404，版本对不上或被抢先 409。[transform] 里抛出的 400 原样往外传。 */
private suspend fun updateCharacter(
    repository: CharacterRepository,
    id: Uuid,
    expectedRevision: Long?,
    transform: (CharacterRecord) -> CharacterRecord,
): CharacterRecord = try {
    repository.update(id, expectedRevision, transform)
} catch (e: NoSuchElementException) {
    throw NotFoundException("Character not found")
} catch (e: CharacterConflictException) {
    throw ConflictException(e.message ?: "The character was changed elsewhere")
}

/** 有界地读请求体：超过 [limit] 字节 413，不会整体进内存。 */
private suspend fun readBody(call: ApplicationCall, limit: Long, what: String): ByteArray {
    val packet = call.receiveChannel().readRemaining(limit + 1)
    val bytes = packet.readByteArray()
    if (bytes.size > limit) throw PayloadTooLargeException("$what is too large (max ${limit / (1024 * 1024)} MiB)")
    if (bytes.isEmpty()) throw BadRequestException("$what is empty")
    return bytes
}
