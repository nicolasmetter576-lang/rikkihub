package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.export.ExportSerializer
import me.rerere.rikkihub.data.export.LorebookSerializer
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.tavern.worldinfo.WorldInfoCodec
import kotlin.uuid.Uuid

private const val MAX_IMPORT_CHARS = 16 * 1024 * 1024

/**
 * 提示词库与世界书的网页接口。网页端和 App 说同一种数据：库条目是酒馆的提示词 JSON，世界书条目是酒馆世界书条目 JSON。
 * 所有写入都落在**最新**的设置上，只改请求里出现的字段（见 [mergeJsonPatch]），不整包替换。
 */
fun Route.tavernLibraryRoutes(settingsStore: SettingsStore) {
    route("/prompt-library") {
        post {
            val body = requireJsonObject(call.receive<JsonElement>(), "Prompt entry")
            val created = libraryEntryForCreate(body)
            settingsStore.update { settings -> settings.copy(promptLibrary = settings.promptLibrary + created) }
            call.respond(HttpStatusCode.Created, created.toJson())
        }

        put("/{id}") {
            val id = call.parameters["id"].toUuid("promptEntryId").toString()
            val patch = requireJsonObject(call.receive<JsonElement>(), "Prompt entry")
            lateinit var merged: me.rerere.tavern.prompt.PromptEntry
            settingsStore.update { settings ->
                val existing = settings.promptLibrary.find { it.identifier == id }
                    ?: throw NotFoundException("Prompt entry not found")
                merged = libraryEntryMerged(existing, patch)
                settings.copy(promptLibrary = settings.promptLibrary.map { if (it.identifier == id) merged else it })
            }
            call.respond(HttpStatusCode.OK, merged.toJson())
        }

        delete("/{id}") {
            val id = call.parameters["id"].toUuid("promptEntryId")
            settingsStore.update { settings ->
                if (settings.promptLibrary.none { it.identifier == id.toString() }) throw NotFoundException("Prompt entry not found")
                settings.copy(
                    promptLibrary = settings.promptLibrary.filter { it.identifier != id.toString() },
                    assistants = settings.assistants.map { it.copy(libraryEntryIds = it.libraryEntryIds - id) },
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/lorebooks") {
        // 新建一本空书，或带着条目新建（body.entries 是酒馆世界书条目 JSON 数组）
        post {
            val body = requireJsonObject(call.receive<JsonElement>(), "Lorebook")
            val entries = (body["entries"] as? JsonArray)?.mapIndexed { index, element ->
                WorldInfoCodec.decodeEntry(requireJsonObject(element, "Lorebook entry"), fallbackUid = index)
            }.orEmpty()
            val created = Lorebook(
                id = Uuid.random(),
                name = body["name"]?.let { patchString(it, "name") }.orEmpty(),
                description = body["description"]?.let { patchString(it, "description") }.orEmpty(),
                enabled = body["enabled"]?.let { patchBoolean(it, "enabled") } ?: true,
                entries = entries,
            )
            settingsStore.update { settings -> settings.copy(lorebooks = settings.lorebooks + created) }
            call.respond(HttpStatusCode.Created, created.fullJson())
        }

        // 导入：酒馆世界书文件，或本应用导出的文件。body 是文件原文，文件名放 ?name=
        post("/import") {
            val text = call.receiveText()
            if (text.length > MAX_IMPORT_CHARS) throw BadRequestException("Lorebook file is too large")
            val fileName = call.request.queryParameters["name"]?.removeSuffix(".json")?.takeIf { it.isNotBlank() }
            val imported = LorebookSerializer.tryImportNative(text)
                ?: LorebookSerializer.tryImportSillyTavern(text, fileName)
                ?: throw BadRequestException("Unsupported lorebook file")
            settingsStore.update { settings -> settings.copy(lorebooks = settings.lorebooks + imported) }
            call.respond(HttpStatusCode.Created, imported.fullJson())
        }

        get("/{id}") {
            val id = call.parameters["id"].toUuid("lorebookId")
            val book = settingsStore.settingsFlow.value.lorebooks.find { it.id == id } ?: throw NotFoundException("Lorebook not found")
            call.respond(book.fullJson())
        }

        // 导出：?format=native（本应用格式，默认）或 st（酒馆世界书文件）
        get("/{id}/export") {
            val id = call.parameters["id"].toUuid("lorebookId")
            val book = settingsStore.settingsFlow.value.lorebooks.find { it.id == id } ?: throw NotFoundException("Lorebook not found")
            val text = when (call.request.queryParameters["format"]) {
                null, "native" -> LorebookSerializer.exportToJson(book, ExportSerializer.DefaultJson)
                "st" -> LorebookSerializer.exportSillyTavern(book)
                else -> throw BadRequestException("format must be native or st")
            }
            call.respondText(text, ContentType.Application.Json)
        }

        // 只改书自己的字段（名称、说明、总开关），条目不动
        put("/{id}") {
            val id = call.parameters["id"].toUuid("lorebookId")
            val patch = requireJsonObject(call.receive<JsonElement>(), "Lorebook")
            lateinit var updated: Lorebook
            settingsStore.update { settings ->
                val existing = settings.lorebooks.find { it.id == id } ?: throw NotFoundException("Lorebook not found")
                updated = existing.withMetaPatch(patch)
                settings.copy(lorebooks = settings.lorebooks.map { if (it.id == id) updated else it })
            }
            call.respond(HttpStatusCode.OK, updated.summaryJson())
        }

        delete("/{id}") {
            val id = call.parameters["id"].toUuid("lorebookId")
            settingsStore.update { settings ->
                if (settings.lorebooks.none { it.id == id }) throw NotFoundException("Lorebook not found")
                settings.copy(
                    lorebooks = settings.lorebooks.filter { it.id != id },
                    assistants = settings.assistants.map { it.copy(lorebookIds = it.lorebookIds - id) },
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // 新增条目：body 可带初始字段（酒馆世界书条目 JSON 的字段），uid 由服务端按最大值加一分配
        post("/{id}/entries") {
            val id = call.parameters["id"].toUuid("lorebookId")
            val fields = (call.receive<JsonElement>() as? JsonObject)
            lateinit var created: me.rerere.tavern.worldinfo.WorldInfoEntry
            lateinit var result: Lorebook
            settingsStore.update { settings ->
                val book = settings.lorebooks.find { it.id == id } ?: throw NotFoundException("Lorebook not found")
                val (next, entry) = book.withNewEntryFrom(fields)
                created = entry
                result = next
                settings.copy(lorebooks = settings.lorebooks.map { if (it.id == id) next else it })
            }
            call.respond(HttpStatusCode.Created, entryResult(created, result))
        }

        // 批量启停：{"enabled": bool, "uids": [..]?}，不带 uids 就是整本
        put("/{id}/entries") {
            val id = call.parameters["id"].toUuid("lorebookId")
            val body = requireJsonObject(call.receive<JsonElement>(), "Request")
            val enabled = patchBoolean(body["enabled"] ?: throw BadRequestException("enabled is required"), "enabled")
            val uids = (body["uids"] as? JsonArray)?.map { patchInt(it, "uids") }?.toSet()
            if (body["uids"] != null && uids == null) throw BadRequestException("uids must be an array")
            lateinit var result: Lorebook
            settingsStore.update { settings ->
                val book = settings.lorebooks.find { it.id == id } ?: throw NotFoundException("Lorebook not found")
                result = book.withEntriesEnabled(enabled, uids)
                settings.copy(lorebooks = settings.lorebooks.map { if (it.id == id) result else it })
            }
            call.respond(HttpStatusCode.OK, buildJsonObject { put("rev", result.lorebookRevision()) })
        }

        put("/{id}/entries/{uid}") {
            val id = call.parameters["id"].toUuid("lorebookId")
            val uid = call.parameters["uid"]?.toIntOrNull() ?: throw BadRequestException("Invalid entry uid")
            val patch = requireJsonObject(call.receive<JsonElement>(), "Lorebook entry")
            lateinit var merged: me.rerere.tavern.worldinfo.WorldInfoEntry
            lateinit var result: Lorebook
            settingsStore.update { settings ->
                val book = settings.lorebooks.find { it.id == id } ?: throw NotFoundException("Lorebook not found")
                val existing = book.entries.find { it.uid == uid } ?: throw NotFoundException("Lorebook entry not found")
                merged = worldInfoEntryMerged(existing, patch)
                result = book.copy(entries = book.entries.map { if (it.uid == uid) merged else it })
                settings.copy(lorebooks = settings.lorebooks.map { if (it.id == id) result else it })
            }
            call.respond(HttpStatusCode.OK, entryResult(merged, result))
        }

        delete("/{id}/entries/{uid}") {
            val id = call.parameters["id"].toUuid("lorebookId")
            val uid = call.parameters["uid"]?.toIntOrNull() ?: throw BadRequestException("Invalid entry uid")
            lateinit var result: Lorebook
            settingsStore.update { settings ->
                val book = settings.lorebooks.find { it.id == id } ?: throw NotFoundException("Lorebook not found")
                if (book.entries.none { it.uid == uid }) throw NotFoundException("Lorebook entry not found")
                result = book.copy(entries = book.entries.filter { it.uid != uid })
                settings.copy(lorebooks = settings.lorebooks.map { if (it.id == id) result else it })
            }
            call.respond(HttpStatusCode.OK, buildJsonObject { put("rev", result.lorebookRevision()) })
        }
    }
}

/** 改了一条之后的回包：这一条（合并后的样子）和整本书新的指纹。 */
private fun entryResult(entry: me.rerere.tavern.worldinfo.WorldInfoEntry, book: Lorebook): JsonObject = buildJsonObject {
    put("entry", entry.webJson())
    put("rev", book.lorebookRevision())
}
