package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.character.PresetFormatException
import me.rerere.rikkihub.data.character.PresetImportReport
import me.rerere.rikkihub.data.character.PresetPorting
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.projectAssistantForWeb

private const val MAX_PRESET_CHARS = 16 * 1024 * 1024

/**
 * SillyTavern Chat Completion 预设与助手互转的网页接口：
 *
 *   POST /assistants/import-preset?name=   body 是预设文件原文；预设成为一个新助手 → {assistant, report}
 *   GET  /assistants/{id}/export-preset    助手导出为预设 JSON 文件
 *
 * 预设里的连接类字段不导入，预设自带的正则脚本导入时是关着的（报告里都数出来）。
 */
fun Route.assistantPresetRoutes(settingsStore: SettingsStore) {
    route("/assistants") {
        post("/import-preset") {
            val text = call.receiveText()
            if (text.length > MAX_PRESET_CHARS) throw BadRequestException("Preset file is too large")
            val fileName = call.request.queryParameters["name"]?.removeSuffix(".json")?.trim().orEmpty()
            val json = try {
                Json.parseToJsonElement(text) as? JsonObject ?: throw BadRequestException("A preset must be a JSON object")
            } catch (e: SerializationException) {
                throw BadRequestException("The file is not valid JSON")
            } catch (e: IllegalArgumentException) {
                throw BadRequestException("The file is not valid JSON")
            }
            val imported = try {
                PresetPorting.import(json, fileName)
            } catch (e: PresetFormatException) {
                throw BadRequestException(e.message ?: "Unsupported preset file")
            }
            settingsStore.update { settings -> settings.copy(assistants = settings.assistants + imported.assistant) }
            call.respond(
                HttpStatusCode.Created,
                buildJsonObject {
                    put("assistant", projectAssistantForWeb(imported.assistant))
                    put("report", imported.report.webJson())
                },
            )
        }

        get("/{id}/export-preset") {
            val id = call.parameters["id"].toUuid("assistantId")
            val assistant = settingsStore.settingsFlow.value.assistants.find { it.id == id }
                ?: throw NotFoundException("Assistant not found")
            val name = assistant.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifEmpty { "preset" }.take(80) + ".json"
            call.response.header(HttpHeaders.ContentDisposition, attachmentHeader(name))
            call.respondText(PresetPorting.export(assistant).toString(), ContentType.Application.Json)
        }
    }
}

internal fun PresetImportReport.webJson(): JsonObject = buildJsonObject {
    put("promptCount", promptCount)
    put("regexScriptsDisabled", regexScriptsDisabled)
    put("samplingImported", JsonArray(samplingImported.map { JsonPrimitive(it) }))
    put("connectionKeysSkipped", JsonArray(connectionKeysSkipped.map { JsonPrimitive(it) }))
}
