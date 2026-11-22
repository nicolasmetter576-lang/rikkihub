package me.rerere.rikkihub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.rerere.rikkihub.data.db.dao.CharacterDAO
import me.rerere.rikkihub.data.db.entity.CharacterEntity
import me.rerere.rikkihub.data.db.entity.CharacterSummaryRow

internal class FakeCharacterDAO : CharacterDAO {
    private val rows = MutableStateFlow<List<CharacterEntity>>(emptyList())
    var conversationCounts = mutableMapOf<String, Int>()
    /** 测试里让下一次 updateCas 假装被别处抢先。 */
    var failNextCas = 0

    private fun summary(e: CharacterEntity) = CharacterSummaryRow(e.id, e.name, e.avatarPath, e.fav, e.tags, e.createdAt, e.updatedAt, e.revision)
    private fun sorted(list: List<CharacterEntity>) = list.sortedWith(compareBy<CharacterEntity> { it.name.lowercase() }.thenBy { it.id })

    override fun observeSummaries(): Flow<List<CharacterSummaryRow>> = rows.map { sorted(it).map(::summary) }
    override suspend fun summaries(): List<CharacterSummaryRow> = sorted(rows.value).map(::summary)

    override suspend fun searchIds(pattern: String): List<String> {
        // 只实现测试用到的 `%字面子串%`（带 \ 转义）
        val needle = pattern.removePrefix("%").removeSuffix("%").replace(Regex("""\\(.)"""), "$1")
        return rows.value.filter { it.searchText.contains(needle) }.map { it.id }
    }

    override suspend fun get(id: String): CharacterEntity? = rows.value.find { it.id == id }
    override suspend fun getMany(ids: List<String>): List<CharacterEntity> = rows.value.filter { it.id in ids }
    override suspend fun count(): Int = rows.value.size

    override suspend fun insert(entity: CharacterEntity) {
        check(rows.value.none { it.id == entity.id }) { "duplicate id" }
        rows.value = rows.value + entity
    }

    override suspend fun updateCas(
        id: String, expectedRevision: Long, name: String, avatarPath: String, cardJson: String, sourceName: String,
        fav: Boolean, tags: String, searchText: String, updatedAt: Long, localSettings: String,
    ): Int {
        if (failNextCas > 0) { failNextCas--; return 0 }
        val current = rows.value.find { it.id == id && it.revision == expectedRevision } ?: return 0
        rows.value = rows.value.map {
            if (it === current) it.copy(name = name, avatarPath = avatarPath, cardJson = cardJson, sourceName = sourceName, fav = fav,
                tags = tags, searchText = searchText, updatedAt = updatedAt, localSettings = localSettings, revision = it.revision + 1)
            else it
        }
        return 1
    }

    override suspend fun delete(id: String): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.id == id }
        return before - rows.value.size
    }

    override suspend fun conversationCount(id: String): Int = conversationCounts[id] ?: 0
}
