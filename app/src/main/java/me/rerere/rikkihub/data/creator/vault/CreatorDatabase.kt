package me.rerere.rikkihub.data.creator.vault

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 独立创造 Vault（Room v1，noBackup 受保护域）。
 *
 * - 从 version 1 起步，独立于主 `AppDatabase`（主库 33→34 的迁移由 mutation
 *   owner 管；本库 schema/迁移归创造域）；
 * - 承载：会话/run/call 账本/提案/审计/回收 payload/grant 影子/库级 meta；
 *   **不**承载普通业务实体、凭据或可执行授权；
 * - WAL/SHM/journal/回收目录随库文件一起位于 `noBackupFilesDir/creator_vault/`，
 *   由 `CreatorBackupScope.excludedPaths()` 排除普通手工与系统备份。
 */
@Database(
    entities = [
        CreatorSessionEntity::class,
        CreatorRunEntity::class,
        CreatorCallLedgerEntity::class,
        CreatorProposalEntity::class,
        CreatorAuditEntity::class,
        CreatorRecyclePayloadEntity::class,
        CreatorMetaEntity::class,
        CreatorGrantShadowEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CreatorDatabase : RoomDatabase() {
    abstract fun creatorSessionDao(): CreatorSessionDao

    abstract fun creatorRunDao(): CreatorRunDao

    abstract fun creatorCallLedgerDao(): CreatorCallLedgerDao

    abstract fun creatorProposalDao(): CreatorProposalDao

    abstract fun creatorAuditDao(): CreatorAuditDao

    abstract fun creatorRecycleDao(): CreatorRecycleDao

    abstract fun creatorMetaDao(): CreatorMetaDao

    abstract fun creatorGrantShadowDao(): CreatorGrantShadowDao
}
