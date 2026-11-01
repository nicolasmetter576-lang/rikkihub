package me.rerere.rikkihub.data.creator.vault

import android.content.Context
import java.io.File

/**
 * 普通备份的排除面：Vault 库 + WAL/SHM + journal + 回收/transcript
 * 目录一律不进普通手工备份与系统备份；普通 BackupManager 在打包文件清单时
 * 消费 [excludedPaths]（接入点是 mutation owner 的备份收口窗口）。
 *
 * 注意：`noBackupFilesDir` 本身已在系统备份排除域；这里的清单针对**应用内
 * 手工备份/导出**（它们会主动遍历 filesDir/noBackup 指定子树）。
 */
object CreatorBackupScope {
    /** 需要被普通备份显式排除的相对路径前缀（相对 `noBackupFilesDir`）。 */
    val EXCLUDED_NOBACKUP_PREFIXES: Set<String> = setOf(
        "creator_vault",
        "creator_vault.db",
    )

    /** Vault 相关文件的判定（库/WAL/SHM/journal/回收同目录）。 */
    fun isVaultPath(file: File): Boolean {
        val name = file.name
        return name == CreatorDatabaseFactory.DATABASE_NAME ||
            name.startsWith("${CreatorDatabaseFactory.DATABASE_NAME}-") || // -wal / -shm / -journal
            file.absolutePath.contains("/${CreatorDatabaseFactory.VAULT_DIR_NAME}/")
    }

    /** 供备份打包过滤的绝对路径前缀集。 */
    fun excludedPaths(context: Context): Set<String> {
        val dir = CreatorDatabaseFactory.vaultDir(context).absolutePath
        return setOf(dir, "$dir/")
    }
}
