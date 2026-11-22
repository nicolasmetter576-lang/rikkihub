package me.rerere.rikkihub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkihub.data.db.dao.CharacterDAO
import me.rerere.rikkihub.data.db.dao.escapeLike
import me.rerere.rikkihub.data.db.entity.CharacterEntity
import me.rerere.rikkihub.data.db.entity.CharacterSummaryRow
import me.rerere.rikkihub.data.model.CharacterLocalSettings
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.tavern.card.CardCodec
import me.rerere.tavern.card.CharacterCard
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/** 版本号对不上：这个角色在你读取之后被别处改过。 */
class CharacterConflictException(message: String) : Exception(message)

/**
 * 角色卡的存取：数据库里一行（卡的 V3 JSON 是唯一事实来源，名字/标签/收藏是算出来的冗余列），
 * 头像和 CHARX 带来的资源放在 [root]`/<id>/` 下。整行写用版本号做 CAS。
 */
class CharacterRepository(
    private val dao: CharacterDAO,
    /** `filesDir/characters`。 */
    private val root: File,
    private val now: () -> Instant = Instant::now,
) {
    fun observeSummaries(): Flow<List<CharacterSummary>> = dao.observeSummaries().map { rows -> rows.map(::toSummary) }

    suspend fun summaries(): List<CharacterSummary> = dao.summaries().map(::toSummary)

    suspend fun count(): Int = dao.count()

    /** 名字、作者、标签、描述开头里含有 [query] 的角色 id；空查询返回空集（调用方自己当作“全部”）。 */
    suspend fun search(query: String): Set<Uuid> {
        val q = query.trim()
        if (q.isEmpty()) return emptySet()
        return dao.searchIds(escapeLike(q)).mapNotNullTo(HashSet()) { runCatching { Uuid.parse(it) }.getOrNull() }
    }

    suspend fun get(id: Uuid): CharacterRecord? = dao.get(id.toString())?.let(::toRecord)

    suspend fun getMany(ids: Collection<Uuid>): List<CharacterRecord> =
        if (ids.isEmpty()) emptyList() else dao.getMany(ids.map { it.toString() }).mapNotNull { runCatching { toRecord(it) }.getOrNull() }

    /**
     * 新建一个角色：先写文件再插入行，插入失败就把文件清掉。[assets] 是 CHARX 里带的包内文件（路径 → 内容）。
     */
    suspend fun create(
        card: CharacterCard,
        avatar: ByteArray? = null,
        avatarExt: String? = null,
        assets: Map<String, ByteArray> = emptyMap(),
        sourceName: String = "",
        local: CharacterLocalSettings = CharacterLocalSettings(),
        id: Uuid = Uuid.random(),
    ): CharacterRecord = withContext(Dispatchers.IO) {
        val dir = directory(id)
        try {
            val avatarPath = if (avatar != null) writeAvatar(dir, avatar, avatarExt) else ""
            writeAssets(dir, assets)
            val time = now()
            val record = CharacterRecord(id, card, avatarPath, sourceName, time, time, local, revision = 0)
            dao.insert(toEntity(record))
            record
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    /**
     * 改一个角色。[expectedRevision] 给了就按它做 CAS（调用方基于某个读到的版本编辑）；
     * 没给就读最新的再写，被别处抢先时重试几次。
     */
    suspend fun update(id: Uuid, expectedRevision: Long? = null, transform: (CharacterRecord) -> CharacterRecord): CharacterRecord =
        withContext(Dispatchers.IO) {
            repeat(if (expectedRevision == null) MAX_RETRIES else 1) {
                val current = dao.get(id.toString())?.let(::toRecord) ?: throw NoSuchElementException("No character with id $id")
                if (expectedRevision != null && current.revision != expectedRevision) {
                    throw CharacterConflictException("The character was changed elsewhere")
                }
                val next = transform(current).copy(id = current.id, createdAt = current.createdAt, updatedAt = now())
                val entity = toEntity(next)
                val rows = dao.updateCas(
                    id = entity.id, expectedRevision = current.revision, name = entity.name, avatarPath = entity.avatarPath,
                    cardJson = entity.cardJson, sourceName = entity.sourceName, fav = entity.fav, tags = entity.tags,
                    searchText = entity.searchText, updatedAt = entity.updatedAt, localSettings = entity.localSettings,
                )
                if (rows > 0) return@withContext next.copy(revision = current.revision + 1)
            }
            throw CharacterConflictException("The character was changed elsewhere")
        }

    /** 换头像：写新文件、改行、删旧文件。 */
    suspend fun setAvatar(id: Uuid, bytes: ByteArray, ext: String?, expectedRevision: Long? = null): CharacterRecord {
        var oldPath = ""
        val updated = update(id, expectedRevision) { c ->
            oldPath = c.avatarPath
            c.copy(avatarPath = writeAvatar(directory(id), bytes, ext))
        }
        if (oldPath.isNotEmpty() && oldPath != updated.avatarPath) withContext(Dispatchers.IO) { File(directory(id), oldPath).delete() }
        return updated
    }

    /** 删除角色和它的文件。绑定它的会话不动（读取时按“没有角色”处理）。 */
    suspend fun delete(id: Uuid): Boolean = withContext(Dispatchers.IO) {
        val removed = dao.delete(id.toString()) > 0
        directory(id).deleteRecursively()
        removed
    }

    /** 有多少个未删除的会话绑着这个角色（删除前提示用）。 */
    suspend fun conversationCount(id: Uuid): Int = dao.conversationCount(id.toString())

    fun avatarFile(record: CharacterRecord): File? =
        record.avatarPath.takeIf { it.isNotEmpty() }?.let { File(directory(record.id), it) }?.takeIf { it.isFile }

    /** 角色目录下保存的 CHARX 资源（包内路径 → 内容），导出 CHARX 时带上。 */
    suspend fun assets(id: Uuid): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        val base = File(directory(id), ASSETS_DIR)
        if (!base.isDirectory) return@withContext emptyMap()
        base.walkTopDown().filter { it.isFile }.associate { it.relativeTo(base).invariantSeparatorsPath to it.readBytes() }
    }

    fun directory(id: Uuid): File = File(root, id.toString())

    // ---- 映射 ----

    private fun toSummary(row: CharacterSummaryRow) = CharacterSummary(
        id = Uuid.parse(row.id), name = row.name, avatarPath = row.avatarPath, fav = row.fav,
        tags = decodeStrings(row.tags),
        createdAt = Instant.ofEpochMilli(row.createdAt), updatedAt = Instant.ofEpochMilli(row.updatedAt), revision = row.revision,
    )

    private fun toRecord(e: CharacterEntity) = CharacterRecord(
        id = Uuid.parse(e.id),
        card = CardCodec.parse(e.cardJson).card,
        avatarPath = e.avatarPath,
        sourceName = e.sourceName,
        createdAt = Instant.ofEpochMilli(e.createdAt),
        updatedAt = Instant.ofEpochMilli(e.updatedAt),
        local = (runCatching { JsonInstant.parseToJsonElement(e.localSettings) as? JsonObject }.getOrNull())
            ?.let(CharacterLocalSettings::fromJson) ?: CharacterLocalSettings(),
        revision = e.revision,
    )

    private fun toEntity(r: CharacterRecord): CharacterEntity {
        val card = r.card
        return CharacterEntity(
            id = r.id.toString(),
            name = card.name,
            avatarPath = r.avatarPath,
            cardJson = card.toJson().toString(),
            sourceName = r.sourceName,
            fav = card.fav,
            tags = JsonArray(card.tags.map { JsonPrimitive(it) }).toString(),
            searchText = searchText(card),
            createdAt = r.createdAt.toEpochMilli(),
            updatedAt = r.updatedAt.toEpochMilli(),
            localSettings = r.local.toJson().toString(),
            revision = r.revision,
        )
    }

    private fun decodeStrings(raw: String): List<String> =
        (runCatching { JsonInstant.parseToJsonElement(raw) as? JsonArray }.getOrNull())?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    // ---- 文件 ----

    private fun writeAvatar(dir: File, bytes: ByteArray, ext: String?): String {
        dir.mkdirs()
        val safeExt = ext?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "png"
        val name = "avatar-${Uuid.random().toString().take(8)}.$safeExt"
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(target)) { "Cannot save the avatar" }
        return name
    }

    private fun writeAssets(dir: File, assets: Map<String, ByteArray>) {
        if (assets.isEmpty()) return
        val base = File(dir, ASSETS_DIR)
        for ((path, data) in assets) {
            val target = File(base, path)
            // CHARX 里的路径在读取时已经规整过；这里再确认一次它没跑出资源目录
            require(target.canonicalFile.startsWith(base.canonicalFile)) { "Invalid asset path: $path" }
            target.parentFile!!.mkdirs()
            target.writeBytes(data)
        }
    }

    companion object {
        const val ASSETS_DIR = "assets"
        private const val MAX_RETRIES = 4

        /** 搜索列的内容：名字、作者、标签、描述开头（小写）。 */
        fun searchText(card: CharacterCard): String =
            listOf(card.name, card.nickname, card.creator, card.tags.joinToString(" "), card.description.take(2000))
                .joinToString("\n").lowercase()
    }
}
