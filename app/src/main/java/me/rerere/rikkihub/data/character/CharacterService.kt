package me.rerere.rikkihub.data.character

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.ai.tavern.ActiveCharacter
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.CharacterLocalSettings
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.tavern.card.CardFiles
import me.rerere.tavern.card.CharacterBooks
import me.rerere.tavern.card.CharacterCard
import me.rerere.tavern.worldinfo.WorldInfoBook
import me.rerere.tavern.worldinfo.WorldInfoCodec
import kotlin.uuid.Uuid

enum class CharacterExportFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JSON("json", "application/json"),
    CHARX("charx", "application/zip"),
}

class ExportedCharacter(val fileName: String, val mimeType: String, val bytes: ByteArray)

/** 卡内世界书导入后的结果。 */
sealed interface BookOutcome {
    val bookId: Uuid
    val name: String

    /** 新建了一本世界书。 */
    data class Created(override val bookId: Uuid, override val name: String, val entries: Int) : BookOutcome

    /** 已有同名且内容完全相同的书，直接用它。 */
    data class Reused(override val bookId: Uuid, override val name: String) : BookOutcome
}

/** 导入报告：这张卡带来了什么，哪些要用户点头（卡内正则）。 */
data class ImportReport(
    val container: String,
    val cardFormat: String,
    val book: BookOutcome?,
    val regexScripts: Int,
    val greetings: Int,
    val hasDepthPrompt: Boolean,
    val assets: Int,
)

class ImportedRecord(val record: CharacterRecord, val report: ImportReport)

/**
 * 角色的导入、导出和它与世界书的关系。仓库管存取；这里管卡文件的读写、卡内世界书转成 [Lorebook]
 * （重名自动编号，内容完全相同就复用已有的），以及导出时把角色书写回卡里。
 */
class CharacterService(
    private val repository: CharacterRepository,
    private val readSettings: () -> Settings,
    private val updateSettings: suspend ((Settings) -> Settings) -> Unit,
    /** 非 PNG 的卡面（JPEG、WebP……）转成 PNG；转不了返回 null，导出就用占位图。 */
    private val toPng: (ByteArray, String?) -> ByteArray? = { _, _ -> null },
) {
    /** 读一个卡文件并建成角色（PNG / CHARX / JSON，按内容认）。 */
    suspend fun import(bytes: ByteArray, fileName: String): ImportedRecord {
        val imported = CardFiles.read(bytes)
        val card = imported.card
        val book = card.characterBook?.takeIf { (it["entries"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true }
            ?.let { importBook(CharacterBooks.toWorldInfo(it), card) }
        val record = repository.create(
            card = card,
            avatar = imported.avatar,
            avatarExt = imported.avatarExt,
            assets = imported.assets,
            sourceName = fileName,
            local = CharacterLocalSettings(embeddedBookId = book?.bookId),
        )
        return ImportedRecord(
            record,
            ImportReport(
                container = imported.container,
                cardFormat = imported.cardFormat,
                book = book,
                regexScripts = card.regexScripts.size,
                greetings = card.greetings.size,
                hasDepthPrompt = card.depthPrompt.prompt.isNotBlank(),
                assets = imported.assets.size,
            ),
        )
    }

    /** 空白角色，只有名字。 */
    suspend fun createBlank(name: String): CharacterRecord = repository.create(CharacterCard.blank(name.trim().ifEmpty { "New character" }))

    private suspend fun importBook(book: WorldInfoBook, card: CharacterCard): BookOutcome {
        val baseName = (book.name?.takeIf { it.isNotBlank() } ?: card.name).trim().ifEmpty { "Character book" }
        val signature = book.entries.map { WorldInfoCodec.encodeEntry(it) }
        var outcome: BookOutcome? = null
        updateSettings { s ->
            val sameName = s.lorebooks.filter { it.name == baseName || it.name.matches(numbered(baseName)) }
            sameName.firstOrNull { it.entries.map { e -> WorldInfoCodec.encodeEntry(e) } == signature }?.let {
                outcome = BookOutcome.Reused(it.id, it.name)
                return@updateSettings s
            }
            val taken = s.lorebooks.mapTo(HashSet()) { it.name }
            val name = if (baseName !in taken) baseName else generateSequence(2) { it + 1 }.map { "$baseName ($it)" }.first { it !in taken }
            val created = Lorebook(
                id = Uuid.random(), name = name, description = book.description.orEmpty(), enabled = true,
                entries = book.entries, extra = book.extra,
            )
            outcome = BookOutcome.Created(created.id, name, created.entries.size)
            s.copy(lorebooks = s.lorebooks + created)
        }
        return outcome!!
    }

    private fun numbered(base: String) = Regex("^" + Regex.escape(base) + " \\(\\d+\\)$")

    /** 导出：卡里的世界书换成角色当前挂着的卡内世界书（编辑过的内容跟着走），不带“收藏”标记。 */
    suspend fun export(id: Uuid, format: CharacterExportFormat): ExportedCharacter {
        val record = repository.get(id) ?: throw NoSuchElementException("No character with id $id")
        val card = cardForExport(record)
        val avatar = repository.avatarFile(record)?.readBytes()
        val ext = record.avatarPath.substringAfterLast('.', "").lowercase().ifEmpty { null }
        val bytes = when (format) {
            CharacterExportFormat.PNG -> CardFiles.toPng(card, avatar?.let { if (ext == "png") it else toPng(it, ext) })
            CharacterExportFormat.JSON -> CardFiles.toJson(card)
            CharacterExportFormat.CHARX -> CardFiles.toCharx(card, avatar, ext, repository.assets(id))
        }
        return ExportedCharacter(safeFileName(card.name) + "." + format.extension, format.mimeType, bytes)
    }

    /** 导出用的那张卡（也给 Web 预览用）。 */
    fun cardForExport(record: CharacterRecord): CharacterCard {
        var card = record.card.withExtension("fav", JsonPrimitive(false))
        val book = record.local.embeddedBookId?.let { bid -> readSettings().lorebooks.firstOrNull { it.id == bid } }
        if (book != null) {
            val worldInfo = WorldInfoBook(entries = book.entries, name = book.name, description = book.description, extra = book.extra)
            card = card.withData("character_book", CharacterBooks.fromWorldInfo(worldInfo, book.name))
                .withExtension("world", JsonPrimitive(book.name))
        }
        return card
    }

    /** 这个角色在聊天里生效的世界书（卡内的在前，已删除或已停用的跳过）。 */
    fun activeBooks(record: CharacterRecord, settings: Settings = readSettings()): List<Lorebook> =
        record.local.bookIds.mapNotNull { id -> settings.lorebooks.firstOrNull { it.id == id && it.enabled } }

    /** 删除角色；[deleteEmbeddedBook] 为真时连同它的卡内世界书一起删（没有别的角色在用的情况下）。 */
    suspend fun delete(id: Uuid, deleteEmbeddedBook: Boolean = false): Boolean {
        val record = repository.get(id)
        val removed = repository.delete(id)
        val bookId = record?.local?.embeddedBookId
        if (removed) setRegexAllowed(id, false)
        if (removed && deleteEmbeddedBook && bookId != null) {
            val others = repository.getMany(repository.summaries().map { it.id }.filter { it != id })
            if (others.none { bookId in it.local.bookIds }) updateSettings { s -> s.copy(lorebooks = s.lorebooks.filterNot { it.id == bookId }) }
        }
        return removed
    }

    /** 放行（或收回放行）这个角色卡内的正则脚本；放行记在全局酒馆设置里，键是 `character:<id>`。 */
    suspend fun setRegexAllowed(id: Uuid, allowed: Boolean) {
        val key = ActiveCharacter.regexSource(id)
        updateSettings { s ->
            s.copy(tavern = s.tavern.copy(regexAllowed = if (allowed) s.tavern.regexAllowed + key else s.tavern.regexAllowed - key))
        }
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifEmpty { "character" }.take(80)
}
