package me.rerere.rikkihub.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkihub.data.db.entity.QuotaSnapshotEntity
import me.rerere.rikkihub.data.db.entity.UsageRecordEntity

@Dao
interface UsageRecordDAO {
    @Query("SELECT (SELECT COUNT(*) FROM usage_record) + (SELECT COUNT(*) FROM quota_snapshot)")
    fun observeRevision(): Flow<Long>

    @Insert
    suspend fun insert(record: UsageRecordEntity)

    /**
     * 按本地时间分桶，再按提供商 / 模型 / 来源分组汇总。[format] 是 strftime 格式
     * （按天 `%Y-%m-%d`，按月 `%Y-%m`）。行数约为 桶数 × 模型数 × 来源数，其余汇总在内存里做。
     */
    @Query(
        "SELECT strftime(:format, created_at / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "provider_id, model_id, MAX(model_name) AS model_name, source, COUNT(*) AS requests, " +
            "SUM(prompt_tokens) AS prompt_tokens, SUM(completion_tokens) AS completion_tokens, " +
            "SUM(cached_tokens) AS cached_tokens, SUM(total_tokens) AS total_tokens, " +
            "SUM(CASE WHEN usage_complete = 0 THEN 1 ELSE 0 END) AS incomplete_requests " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY bucket, provider_id, model_id, source"
    )
    suspend fun getGroupedUsage(format: String, since: Long): List<UsageGroupRow>

    /** 每天（本地日期 `yyyy-MM-dd`）某类请求的次数 */
    @Query(
        "SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count " +
            "FROM usage_record WHERE source = :source AND created_at >= :since GROUP BY day"
    )
    suspend fun getRequestCountPerDay(source: String, since: Long): List<UsageDayCount>

    /** 每天（本地日期 `yyyy-MM-dd`）多类请求的合计次数 */
    suspend fun getRequestCountPerDay(sources: List<String>, since: Long): List<UsageDayCount> =
        sources.distinct()
            .flatMap { source -> getRequestCountPerDay(source, since) }
            .groupBy { it.day }
            .map { (day, entries) -> UsageDayCount(day = day, count = entries.sumOf { it.count }) }

    /** 某账号在 (from, to] 完成的用量；跨过起点的请求标为不完整，使整个区间不能用于反推。 */
    @Query(
        "SELECT NULL AS bucket, provider_id, model_id, MAX(model_name) AS model_name, '' AS source, " +
            "COUNT(*) AS requests, SUM(prompt_tokens) AS prompt_tokens, " +
            "SUM(completion_tokens) AS completion_tokens, SUM(cached_tokens) AS cached_tokens, " +
            "SUM(total_tokens) AS total_tokens, " +
            "SUM(CASE WHEN usage_complete = 0 OR (:wholeRequestsOnly = 1 AND started_at <= :from) THEN 1 ELSE 0 END) AS incomplete_requests " +
            "FROM usage_record WHERE account_key = :accountKey AND created_at > :from AND created_at <= :to " +
            "GROUP BY provider_id, model_id"
    )
    suspend fun getAccountUsage(accountKey: String, from: Long, to: Long, wholeRequestsOnly: Boolean = true): List<UsageGroupRow>

    /** 删掉一组流水（统计页里的一行模型）；两个 id 与 [getGroupedUsage] 的分组键同样可为空 */
    @Query("DELETE FROM usage_record WHERE provider_id IS :providerId AND model_id IS :modelId")
    suspend fun deleteUsage(providerId: String?, modelId: String?)

    @Query("DELETE FROM quota_snapshot WHERE account_key LIKE :prefix || '%'")
    suspend fun deleteSnapshots(prefix: String)

    @Insert
    suspend fun insertSnapshot(snapshot: QuotaSnapshotEntity)

    @Query(
        "SELECT * FROM quota_snapshot WHERE account_key = :accountKey AND window = :window " +
            "ORDER BY created_at DESC LIMIT 1"
    )
    suspend fun getLatestSnapshot(accountKey: String, window: String): QuotaSnapshotEntity?

    @Query("SELECT * FROM quota_snapshot WHERE account_key LIKE :prefix || '%' ORDER BY account_key, window, created_at")
    suspend fun getSnapshots(prefix: String): List<QuotaSnapshotEntity>
}

data class UsageGroupRow(
    @ColumnInfo("bucket") val bucket: String?,
    @ColumnInfo("provider_id") val providerId: String?,
    @ColumnInfo("model_id") val modelId: String?,
    @ColumnInfo("model_name") val modelName: String?,
    @ColumnInfo("source") val source: String,
    @ColumnInfo("requests") val requests: Long,
    @ColumnInfo("prompt_tokens") val promptTokens: Long,
    @ColumnInfo("completion_tokens") val completionTokens: Long,
    @ColumnInfo("cached_tokens") val cachedTokens: Long,
    @ColumnInfo("total_tokens") val totalTokens: Long,
    @ColumnInfo("incomplete_requests") val incompleteRequests: Long = 0,
)

data class UsageDayCount(
    val day: String,
    val count: Int,
)
