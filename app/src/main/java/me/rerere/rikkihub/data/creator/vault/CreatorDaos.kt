package me.rerere.rikkihub.data.creator.vault

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CreatorSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CreatorSessionEntity)

    @Query("SELECT * FROM creator_sessions WHERE sessionId = :id")
    suspend fun get(id: String): CreatorSessionEntity?

    @Query("SELECT * FROM creator_sessions ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<CreatorSessionEntity>>

    @Query("UPDATE creator_sessions SET nextRunOrdinal = :ordinal WHERE sessionId = :id")
    suspend fun updateNextRunOrdinal(id: String, ordinal: Int)
}

@Dao
interface CreatorRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CreatorRunEntity)

    @Query("SELECT * FROM creator_runs WHERE runId = :id")
    suspend fun get(id: String): CreatorRunEntity?

    @Query("SELECT * FROM creator_runs WHERE sessionId = :sessionId ORDER BY ordinal")
    fun observeBySession(sessionId: String): Flow<List<CreatorRunEntity>>

    @Query(
        "UPDATE creator_runs SET status = :status, endedAtMillis = :endedAt, " +
            "terminalReason = :reason WHERE runId = :id"
    )
    suspend fun updateTerminal(id: String, status: String, endedAt: Long?, reason: String?)

    @Query("SELECT * FROM creator_runs WHERE status IN ('RUNNING','PAUSED')")
    suspend fun listOpen(): List<CreatorRunEntity>
}

@Dao
interface CreatorCallLedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CreatorCallLedgerEntity)

    @Query("SELECT * FROM creator_call_ledger WHERE hostCallInstanceId = :id")
    suspend fun get(id: String): CreatorCallLedgerEntity?

    @Query(
        "UPDATE creator_call_ledger SET state = :state, terminalAtMillis = :at, " +
            "outputSummary = :outputSummary, errorCode = :errorCode " +
            "WHERE hostCallInstanceId = :id"
    )
    suspend fun updateState(
        id: String,
        state: String,
        at: Long?,
        outputSummary: String?,
        errorCode: String?,
    )

    @Query("SELECT * FROM creator_call_ledger WHERE runId = :runId ORDER BY issuedAtMillis")
    fun observeByRun(runId: String): Flow<List<CreatorCallLedgerEntity>>

    @Query("SELECT * FROM creator_call_ledger WHERE runId = :runId AND state IN (:states)")
    suspend fun listByState(runId: String, states: List<String>): List<CreatorCallLedgerEntity>
}

@Dao
interface CreatorProposalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CreatorProposalEntity)

    @Query("SELECT * FROM creator_proposals WHERE proposalId = :id")
    suspend fun get(id: String): CreatorProposalEntity?

    @Query(
        "UPDATE creator_proposals SET state = :state, updatedAtMillis = :updatedAt " +
            "WHERE proposalId = :id AND state = :expectedState"
    )
    suspend fun transition(id: String, expectedState: String, state: String, updatedAt: Long): Int

    @Query("SELECT * FROM creator_proposals WHERE sessionId = :sessionId ORDER BY createdAtMillis")
    fun observeBySession(sessionId: String): Flow<List<CreatorProposalEntity>>

    @Query("SELECT * FROM creator_proposals WHERE state IN (:states)")
    suspend fun listByState(states: List<String>): List<CreatorProposalEntity>
}

/** 审计只追加：无 update/delete（模型与业务层都不能改删审计）。 */
@Dao
interface CreatorAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CreatorAuditEntity)

    @Query("SELECT * FROM creator_audit ORDER BY atMillis DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<CreatorAuditEntity>

    @Query("SELECT * FROM creator_audit WHERE atMillis >= :since ORDER BY atMillis")
    fun observeSince(since: Long): Flow<List<CreatorAuditEntity>>

    @Query("DELETE FROM creator_audit WHERE atMillis < :beforeMillis AND refId NOT IN (:protectedRefs)")
    suspend fun pruneBefore(beforeMillis: Long, protectedRefs: List<String>): Int
}

@Dao
interface CreatorRecycleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CreatorRecyclePayloadEntity)

    @Query("SELECT * FROM creator_recycle_payloads WHERE payloadId = :id")
    suspend fun get(id: String): CreatorRecyclePayloadEntity?

    @Query("DELETE FROM creator_recycle_payloads WHERE expiresAtMillis < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long): Int
}

@Dao
interface CreatorMetaDao {
    @Query("SELECT * FROM creator_meta WHERE `key` = :key")
    suspend fun get(key: String): CreatorMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: CreatorMetaEntity)
}

@Dao
interface CreatorGrantShadowDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CreatorGrantShadowEntity)

    @Query("SELECT * FROM creator_grant_shadow WHERE grantId = :grantId ORDER BY atMillis")
    suspend fun historyOf(grantId: String): List<CreatorGrantShadowEntity>
}
