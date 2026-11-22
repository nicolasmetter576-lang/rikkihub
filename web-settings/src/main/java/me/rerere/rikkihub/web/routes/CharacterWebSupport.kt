package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.tavern.ActiveCharacter
import me.rerere.rikkihub.data.character.BookOutcome
import me.rerere.rikkihub.data.character.CardPatch
import me.rerere.rikkihub.data.character.CardPatchException
import me.rerere.rikkihub.data.character.ImportReport
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.tavern.card.CharacterCard
import me.rerere.tavern.core.PromptRole

/*
 * 网页端读写角色的纯函数。下发的形状：列表一行是摘要，详情是摘要加上卡的可编辑字段、角色书和卡内正则（只读）。
 * 写入只收白名单里的字段；卡里其余的键（assets、话痨度、第三方工具塞的未知键）原样保留，网页端碰不到也不会丢。
 */

/** 卡里以字符串存的可编辑字段（键就是卡 `data` 里的键）。 */
internal val CARD_TEXT_FIELDS get() = CardPatch.TEXT_FIELDS

/** `PUT /characters/{id}` 认的全部键（再加上 `expectedRevision`）。 */
internal val CHARACTER_PATCH_KEYS get() = CardPatch.KEYS + "expectedRevision"

/** 头像的下载地址里带文件名当版本：换头像文件名就变，浏览器可以放心长期缓存。 */
private fun avatarUrl(id: String, avatarPath: String): String? =
    avatarPath.takeIf { it.isNotEmpty() }?.let { "/api/characters/$id/avatar?v=$it" }

internal fun CharacterSummary.webJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("name", name)
    put("avatarUrl", avatarUrl(id.toString(), avatarPath)?.let { JsonPrimitive(it) } ?: JsonNull)
    put("fav", fav)
    put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
    put("createdAt", createdAt.toEpochMilli())
    put("updatedAt", updatedAt.toEpochMilli())
    put("revision", revision)
}

internal fun CharacterRecord.webJson(settings: Settings): JsonObject = buildJsonObject {
    val idText = id.toString()
    put("id", idText)
    put("name", name)
    put("avatarUrl", avatarUrl(idText, avatarPath)?.let { JsonPrimitive(it) } ?: JsonNull)
    put("fav", card.fav)
    put("tags", JsonArray(card.tags.map { JsonPrimitive(it) }))
    put("createdAt", createdAt.toEpochMilli())
    put("updatedAt", updatedAt.toEpochMilli())
    put("revision", revision)
    put("sourceName", sourceName)
    put("fields", card.editableFields())
    put("assetCount", card.assets.size)

    put("regexAllowed", ActiveCharacter.regexSource(id) in settings.tavern.regexAllowed)
    put("regexScripts", JsonArray(card.regexScripts.map { script ->
        buildJsonObject {
            put("id", script.id)
            put("name", script.scriptName)
            put("findRegex", script.findRegex)
            put("replaceString", script.replaceString)
            put("disabled", script.disabled)
        }
    }))

    put("embeddedBookId", local.embeddedBookId?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
    put("extraBookIds", JsonArray(local.extraBookIds.map { JsonPrimitive(it.toString()) }))
    // 已经不存在的书（被删了）不列出来；挂着的 id 还在，书回来时仍然生效
    put("books", JsonArray(local.bookIds.mapNotNull { bookId ->
        settings.lorebooks.firstOrNull { it.id == bookId }?.let { book ->
            buildJsonObject {
                put("id", book.id.toString())
                put("name", book.name)
                put("enabled", book.enabled)
                put("embedded", bookId == local.embeddedBookId)
                put("entryCount", book.entries.size)
            }
        }
    }))
}

/** 网页端编辑用的字段：全部文字字段、标签、备选开场白、深度提示词。 */
internal fun CharacterCard.editableFields(): JsonObject = CardPatch.editableFields(this)

/** 把网页端发来的改动叠在卡上；规则同创造助手（[CardPatch]），不合规的 400。 */
internal fun CharacterCard.withWebPatch(patch: JsonObject): CharacterCard = try {
    CardPatch.apply(this, JsonObject(patch - "expectedRevision"))
} catch (e: CardPatchException) {
    throw BadRequestException(e.message ?: "Invalid character field")
}

/** 请求里的 `expectedRevision`：缺省 null（按最新的写），给了必须是整数。 */
internal fun expectedRevisionOf(patch: JsonObject): Long? {
    val value = patch["expectedRevision"] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
    return primitive?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw BadRequestException("expectedRevision must be an integer")
}

internal fun ImportReport.webJson(): JsonObject = buildJsonObject {
    put("container", container)
    put("cardFormat", cardFormat)
    put("book", when (val outcome = book) {
        null -> JsonNull
        is BookOutcome.Created -> buildJsonObject {
            put("outcome", "created")
            put("bookId", outcome.bookId.toString())
            put("name", outcome.name)
            put("entries", outcome.entries)
        }
        is BookOutcome.Reused -> buildJsonObject {
            put("outcome", "reused")
            put("bookId", outcome.bookId.toString())
            put("name", outcome.name)
        }
    })
    put("regexScripts", regexScripts)
    put("greetings", greetings)
    put("hasDepthPrompt", hasDepthPrompt)
    put("assets", assets)
}

/** 头像图片的类型：只收常见位图（按文件头认，不信请求头），返回扩展名；认不出返回 null。 */
internal fun imageExtension(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
    bytes.size >= 6 && (String(bytes, 0, 6, Charsets.ISO_8859_1) == "GIF87a" || String(bytes, 0, 6, Charsets.ISO_8859_1) == "GIF89a") -> "gif"
    else -> null
}

internal fun imageContentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}

/** 下载文件名：ASCII 兜底写法加 UTF-8 的 `filename*`，文件名里有中文也能正确保存。 */
internal fun attachmentHeader(fileName: String): String {
    val ascii = fileName.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
    val utf8 = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$utf8"
}
