package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.libraryEnabled
import me.rerere.rikkihub.data.model.withLibraryMeta
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import kotlin.uuid.Uuid

/*
 * 网页端保存酒馆数据（提示词库、世界书、全局正则）的纯函数。
 * 网页端发来的都是「要改的字段」：先把最新的对象编码成酒馆 JSON，叠上请求里出现的键（显式 null 就是设成 null），
 * 再解码回对象。没出现的字段、认不得的字段原样保留，所以并发的另一端改了别的字段不会被盖掉。
 */

/** 请求体必须是 JSON 对象，不然 400。 */
internal fun requireJsonObject(element: JsonElement, what: String): JsonObject =
    element as? JsonObject ?: throw BadRequestException("$what must be a JSON object")

/** [patch] 里出现的键盖在 [base] 上（保持 [base] 的键顺序，新键排在后面）；[frozen] 里的键忽略。 */
internal fun mergeJsonPatch(base: JsonObject, patch: JsonObject, frozen: Set<String> = emptySet()): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(base)
    for ((key, value) in patch) {
        if (key in frozen) continue
        out[key] = value
    }
    return JsonObject(out)
}

// ------------------------------------------------------------------ 提示词库

/** 新建库条目：标识符一律由服务端分配（库条目被助手和会话按它选用，必须是 UUID）。 */
internal fun libraryEntryForCreate(body: JsonObject): PromptEntry {
    val parsed = PromptEntry.fromJson(JsonObject(body - "identifier"))
    return normalizeLibraryEntry(parsed.copy(identifier = Uuid.random().toString()))
}

/** 改库条目：标识符不能改。 */
internal fun libraryEntryMerged(existing: PromptEntry, patch: JsonObject): PromptEntry {
    val merged = mergeJsonPatch(existing.toJson(), patch, frozen = setOf("identifier"))
    return normalizeLibraryEntry(PromptEntry.fromJson(merged).copy(identifier = existing.identifier))
}

/**
 * 库条目是自定义条目：`system_prompt` 与 `marker` 一律为假（组装器只把已知标识符当内置条目），
 * 角色缺省为系统，`rikkihub` 元数据补齐（可选用开关、锚点）。
 */
private fun normalizeLibraryEntry(entry: PromptEntry): PromptEntry {
    val withFlags = entry.copy(systemPrompt = false, marker = false, role = entry.role ?: PromptRole.SYSTEM, enabled = true)
    val hasMeta = withFlags.extra[LibraryEntryMeta.EXTRA_KEY] is JsonObject
    return if (hasMeta) withFlags.withLibraryMeta(enabled = withFlags.libraryEnabled, anchor = withFlags.libraryAnchor)
    else withFlags.withLibraryMeta(enabled = true, anchor = LibraryEntryMeta.AFTER_SYSTEM)
}

// ------------------------------------------------------------------ 世界书

/** 全部条目的酒馆 JSON，保留各自导入时的键和写法（指纹按它算：和设置快照里编码出来的是同一份）。 */
internal fun Lorebook.entriesJson(): JsonArray = JsonArray(entries.map { WorldInfoCodec.encodeEntry(it) })

/**
 * 给网页端看的条目：字段一个不缺。酒馆导出的世界书可以只写一部分字段，原样下发的话界面读 `triggers`、`scanDepth` 会读到空；
 * 规范写法把没写的字段补成默认值，认不得的字段照样带着。
 */
internal fun WorldInfoEntry.webJson(): JsonObject = WorldInfoCodec.encodeEntry(copy(layout = null))

/**
 * 条目内容的指纹：条目 JSON 的结构哈希，内容不变就不变，和对象是构造出来还是解码出来的无关。
 * 网页端用它判断书有没有被别处改过（自己的改动回包里带同一个值）。
 */
internal fun JsonArray.revision(): String = Integer.toHexString(hashCode())

internal fun Lorebook.lorebookRevision(): String = entriesJson().revision()

/**
 * 设置快照里的世界书只带摘要：几千条的书每次设置变动都整本推给所有网页端太重。
 * [entries] 是已经编码好的条目（设置快照编码时顺手有一份），不传就现编。
 */
internal fun Lorebook.summaryJson(entries: JsonArray = entriesJson()): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("name", name)
    put("description", description)
    put("enabled", enabled)
    put("entryCount", entries.size)
    put("rev", entries.revision())
}

/** 整本书的网页端形状：摘要加全部条目（酒馆世界书条目 JSON，42 个字段原样）。 */
internal fun Lorebook.fullJson(): JsonObject = buildJsonObject {
    for ((k, v) in summaryJson()) put(k, v)
    put("entries", JsonArray(entries.map { it.webJson() }))
}

/** 条目改动：uid 不能改；其余字段叠在最新的条目上。 */
internal fun worldInfoEntryMerged(existing: WorldInfoEntry, patch: JsonObject): WorldInfoEntry {
    val merged = mergeJsonPatch(existing.webJson(), patch, frozen = setOf("uid"))
    return WorldInfoCodec.decodeEntry(merged, fallbackUid = existing.uid).copy(uid = existing.uid)
}

/** 新增一条：uid 取现有最大值加一；[fields] 是新条目要带的字段（没有就是一条空条目）。 */
internal fun Lorebook.withNewEntryFrom(fields: JsonObject?): Pair<Lorebook, WorldInfoEntry> {
    val uid = (entries.maxOfOrNull { it.uid } ?: -1) + 1
    val base = WorldInfoEntry(uid = uid)
    val entry = if (fields == null || fields.isEmpty()) base else worldInfoEntryMerged(base, fields)
    return copy(entries = entries + entry) to entry
}

/** 批量启停：[uids] 为 null 是全部条目，否则只动这些 uid。 */
internal fun Lorebook.withEntriesEnabled(enabled: Boolean, uids: Set<Int>?): Lorebook =
    copy(entries = entries.map { if (uids == null || it.uid in uids) it.copy(disable = !enabled) else it })

/** 只改书自己的字段（名称、说明、总开关）；条目和其余内容保留。 */
internal fun Lorebook.withMetaPatch(patch: JsonObject): Lorebook {
    var out = this
    patch["name"]?.let { out = out.copy(name = patchString(it, "name")) }
    patch["description"]?.let { out = out.copy(description = patchString(it, "description")) }
    patch["enabled"]?.let { out = out.copy(enabled = patchBoolean(it, "enabled")) }
    return out
}

// ------------------------------------------------------------------ 全局正则

/** 新建全局正则脚本：请求里带了合法的 id 就用它（界面先显示新脚本再等回包，之后的编辑要按这个 id 找），没有就分配一个。 */
internal fun regexScriptForCreate(body: JsonObject): RegexScript {
    val requested = (body["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    val id = requested?.takeIf { it.isNotEmpty() && it.length <= MAX_REGEX_ID_CHARS } ?: Uuid.random().toString()
    return RegexScript.fromJson(body).copy(id = id)
}

private const val MAX_REGEX_ID_CHARS = 100

/** 改全局正则脚本：id 不能改。 */
internal fun regexScriptMerged(existing: RegexScript, patch: JsonObject): RegexScript =
    RegexScript.fromJson(mergeJsonPatch(existing.toJson(), patch, frozen = setOf("id"))).copy(id = existing.id)

// ------------------------------------------------------------------ 取值

internal fun patchString(element: JsonElement, name: String): String {
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || !primitive.isString) throw BadRequestException("$name must be a string")
    return primitive.content
}

internal fun patchBoolean(element: JsonElement, name: String): Boolean =
    (element as? JsonPrimitive)?.booleanOrNull ?: throw BadRequestException("$name must be a boolean")

internal fun patchInt(element: JsonElement, name: String): Int =
    (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw BadRequestException("$name must be an integer")
