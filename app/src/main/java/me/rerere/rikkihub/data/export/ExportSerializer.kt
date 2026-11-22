package me.rerere.rikkihub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkihub.data.datastore.migration.TavernSettingsMigration
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.withLibraryMeta
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.worldinfo.WorldInfoBook
import me.rerere.tavern.worldinfo.WorldInfoCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkihub.utils.toLocalString
import java.time.LocalDateTime
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

/**
 * 模式注入（提示词库条目）的导出文件。新格式 `version = 2`：数据就是酒馆格式的 `PromptEntry` JSON；
 * 2.32.0 及更早导出的文件（`version = 1`，数据是旧的模式注入对象）导入时仍然认得，用迁移同一套规则转换。
 */
object ModeInjectionSerializer : ExportSerializer<PromptEntry> {
    override val type = "mode_injection"

    override fun getExportFileName(data: PromptEntry): String {
        return "${data.name.orEmpty().ifEmpty { type }}.json"
    }

    override fun export(data: PromptEntry): ExportData {
        return ExportData(version = 2, type = type, data = data.toJson())
    }

    override fun import(context: Context, uri: Uri): Result<PromptEntry> {
        return runCatching {
            val json = readUri(context, uri)
            tryImportNative(json) ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    internal fun tryImportNative(json: String): PromptEntry? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(ExportData.serializer(), json)
            if (exportData.type != type) return null
            val obj = exportData.data.jsonObject
            val entry = if ("identifier" in obj) PromptEntry.fromJson(obj) else TavernSettingsMigration.modeInjectionToLibraryEntry(obj)
            entry.copy(identifier = Uuid.random().toString())
        }.getOrNull()
    }
}

/**
 * 世界书的导出文件。新格式 `version = 2`：书的 `id / name / description / enabled / entries`，条目是酒馆世界书条目 JSON
 * （42 个字段原样）。导入认得三种：新格式、2.32.0 及更早的旧格式（条目按迁移规则转换）、酒馆世界书文件（`entries` 对象，无损）。
 */
object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(version = 2, type = type, data = ExportSerializer.DefaultJson.encodeToJsonElement(data))
    }

    /** 酒馆世界书文件（`entries` 是以 uid 为键的对象），可以直接放进酒馆。 */
    fun exportSillyTavern(data: Lorebook): String = WorldInfoBook(
        entries = data.entries, name = data.name.ifEmpty { null }, description = data.description.ifEmpty { null },
        extra = data.extra,
    ).toJson().toString()

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            tryImportNative(json)
                ?: tryImportSillyTavern(json, getUriFileName(context, uri)?.removeSuffix(".json"))
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    internal fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(ExportData.serializer(), json)
            if (exportData.type != type) return null
            // 旧格式的条目是 RegexInjection（没有 uid / key），先按迁移规则转成酒馆条目
            val book = TavernSettingsMigration.migrateLorebook(exportData.data.jsonObject)
            ExportSerializer.DefaultJson.decodeFromJsonElement<Lorebook>(book).copy(id = Uuid.random())
        }.getOrNull()
    }

    internal fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val book = WorldInfoCodec.decodeBook(json)
            require(book.entries.isNotEmpty() || json.contains("\"entries\""))
            Lorebook(
                id = Uuid.random(),
                name = book.name ?: fileName ?: LocalDateTime.now().toLocalString(),
                description = book.description.orEmpty(),
                enabled = true,
                entries = book.entries,
                extra = book.extra,
            )
        }.getOrNull()
    }
}

/**
 * 把世界书导出成酒馆的世界书文件（`entries` 以 uid 为键的对象），可以直接放进酒馆；导入仍认 [LorebookSerializer] 的全部格式。
 * 导出对话框用它给「导出为酒馆世界书」一个入口。
 */
object SillyTavernLorebookSerializer : ExportSerializer<Lorebook> {
    override val type get() = LorebookSerializer.type

    override fun getExportFileName(data: Lorebook): String = LorebookSerializer.getExportFileName(data)

    override fun export(data: Lorebook): ExportData = LorebookSerializer.export(data)

    override fun exportToJson(data: Lorebook, json: Json): String = LorebookSerializer.exportSillyTavern(data)

    override fun import(context: Context, uri: Uri): Result<Lorebook> = LorebookSerializer.import(context, uri)
}
