package me.rerere.rikkihub.data.repository

import androidx.paging.PagingSource
import me.rerere.rikkihub.data.db.dao.TranslationHistoryDAO
import me.rerere.rikkihub.data.db.entity.TranslationHistoryEntity
import kotlin.uuid.Uuid

/** 翻译页（App 与 Web 共用）的翻译历史，只保留最近 [MAX_TRANSLATION_HISTORY] 条 */
class TranslationHistoryRepository(
    private val dao: TranslationHistoryDAO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun pagingAll(): PagingSource<Int, TranslationHistoryEntity> = dao.pagingAll()

    suspend fun page(offset: Int, limit: Int): List<TranslationHistoryEntity> = dao.page(offset, limit)

    suspend fun count(): Int = dao.count()

    /** 原文或译文为空白时不记录 */
    suspend fun add(
        sourceText: String,
        translatedText: String,
        targetLanguage: String,
        modelId: Uuid,
    ): TranslationHistoryEntity? {
        if (sourceText.isBlank() || translatedText.isBlank()) return null
        val entity = TranslationHistoryEntity(
            id = Uuid.random().toString(),
            sourceText = sourceText,
            translatedText = translatedText,
            targetLanguage = targetLanguage,
            modelId = modelId.toString(),
            createAt = now(),
        )
        dao.insertAndTrim(entity, MAX_TRANSLATION_HISTORY)
        return entity
    }

    suspend fun deleteById(id: String): Boolean = dao.deleteById(id) > 0

    suspend fun deleteAll() = dao.deleteAll()

    companion object {
        const val MAX_TRANSLATION_HISTORY = 100
    }
}
