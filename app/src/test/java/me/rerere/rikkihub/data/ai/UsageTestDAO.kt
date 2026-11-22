package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkihub.data.db.dao.UsageDayCount
import me.rerere.rikkihub.data.db.dao.UsageGroupRow
import me.rerere.rikkihub.data.db.dao.UsageRecordDAO
import me.rerere.rikkihub.data.db.entity.QuotaSnapshotEntity
import me.rerere.rikkihub.data.db.entity.UsageRecordEntity

internal class UsageTestDAO : UsageRecordDAO {
    val records = mutableListOf<UsageRecordEntity>()
    val snapshots = mutableListOf<QuotaSnapshotEntity>()
    var rows = emptyList<UsageGroupRow>()
    var accountRows: (String, Long, Long) -> List<UsageGroupRow> = { _, _, _ -> rows }
    var since = -1L
    override fun observeRevision(): Flow<Long> = flowOf(0L)
    override suspend fun insert(record: UsageRecordEntity) { records += record }
    override suspend fun getGroupedUsage(format: String, since: Long): List<UsageGroupRow> {
        this.since = since
        return rows
    }
    override suspend fun getRequestCountPerDay(source: String, since: Long) = emptyList<UsageDayCount>()
    override suspend fun getAccountUsage(accountKey: String, from: Long, to: Long, wholeRequestsOnly: Boolean) =
        accountRows(accountKey, from, to)
    override suspend fun deleteUsage(providerId: String?, modelId: String?) {
        rows = rows.filterNot { it.providerId == providerId && it.modelId == modelId }
    }
    override suspend fun deleteSnapshots(prefix: String) { snapshots.removeAll { it.accountKey.startsWith(prefix) } }
    override suspend fun insertSnapshot(snapshot: QuotaSnapshotEntity) { snapshots += snapshot }
    override suspend fun getLatestSnapshot(accountKey: String, window: String) =
        snapshots.filter { it.accountKey == accountKey && it.window == window }.maxByOrNull { it.createdAt }
    override suspend fun getSnapshots(prefix: String) =
        snapshots.filter { it.accountKey.startsWith(prefix) }.sortedBy { it.createdAt }
}
