package me.rerere.rikkihub.data.creator.vault

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * CreatorVault 数据库工厂。
 *
 * 位置：`context.noBackupFilesDir/creator_vault/creator_vault.db`——
 * 受保护域，不进自动备份；WAL/SHM 与库同目录，由 [CreatorBackupScope]
 * 一并排除普通手工备份。
 *
 * 刻意**不**复用主库的 openHelperFactory（Requery + jieba 扩展）：
 * 独立方言面更小、创建/迁移行为可单独验证；Vault 无 FTS 需求。
 */
object CreatorDatabaseFactory {
    const val VAULT_DIR_NAME = "creator_vault"
    const val DATABASE_NAME = "creator_vault.db"

    fun vaultDir(context: Context): File =
        File(context.noBackupFilesDir, VAULT_DIR_NAME)

    fun vaultFile(context: Context): File =
        File(vaultDir(context), DATABASE_NAME)

    fun create(context: Context): CreatorDatabase {
        val file = vaultFile(context)
        file.parentFile?.mkdirs()
        return Room.databaseBuilder(context, CreatorDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // 无 destructive migration：损坏/版本不符显式失败，由恢复流程处理。
            .build()
    }

    /** 测试/恢复用：在指定路径建库（仍走同一 schema 与 WAL 配置）。 */
    fun createAt(context: Context, file: File): CreatorDatabase {
        file.parentFile?.mkdirs()
        return Room.databaseBuilder(context, CreatorDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
