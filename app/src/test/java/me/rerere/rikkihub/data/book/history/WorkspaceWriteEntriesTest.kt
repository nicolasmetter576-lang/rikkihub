package me.rerere.rikkihub.data.book.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 版本史靠每个写入口自己调 WorkspaceChanges.notify。工作区代码里每多一处直接改名、删除、写流，
 * 这里就会失败：先确认它会不会覆盖、删除或移走作品里已有的文件（会就挂上通知），再改下面的数。
 */
class WorkspaceWriteEntriesTest {
    private val rawWrite = Regex(
        """renameTo\(|Files\.move\(|deleteRecursively\(\)|\.delete\(\)|Files\.delete(?:IfExists)?\(|outputStream\(\)|newOutputStream\(|\.writeBytes\(|\.copyTo\(|copyRecursively\(""",
    )

    private val reviewed = mapOf(
        "app/src/main/java/me/rerere/rikkihub/data/book/BookReplacer.kt" to 4, // 撤销记录，在 noBackupFilesDir
        "app/src/main/java/me/rerere/rikkihub/data/book/BookStore.kt" to 3, // 导出的临时文件、索引缓存
        "app/src/main/java/me/rerere/rikkihub/data/book/history/BookHistoryStore.kt" to 14, // 历史自己；回收站放回只新建、不覆盖；锁定文件进回收站与回滚
        "app/src/main/java/me/rerere/rikkihub/data/book/history/BookLockTable.kt" to 2, // history/locks.json 与备份
        "app/src/main/java/me/rerere/rikkihub/data/book/history/BookTurnTable.kt" to 5, // history/turns/ 审阅记录
        "app/src/main/java/me/rerere/rikkihub/data/book/transfer/BookImportSource.kt" to 2, // 导入的临时文件
        "app/src/main/java/me/rerere/rikkihub/data/book/transfer/BookImporter.kt" to 4, // 只新建，同名报错
        "app/src/main/java/me/rerere/rikkihub/data/files/FileManagerService.kt" to 4, // 导出 zip 到外部
        "workspace/src/main/java/me/rerere/workspace/LocalWorkspaceBackend.kt" to 1, // 设备目录占位说明
        "workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt" to 10, // linux/
        "workspace/src/main/java/me/rerere/workspace/RootfsPatcher.kt" to 1, // linux/
        "workspace/src/main/java/me/rerere/workspace/WorkspaceArchiveExtractor.kt" to 5, // 只新建
        "workspace/src/main/java/me/rerere/workspace/WorkspaceBatchDelete.kt" to 3, // 已通知
        "workspace/src/main/java/me/rerere/workspace/WorkspaceDeviceFileAccess.kt" to 1, // 设备目录
        "workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt" to 16, // 已通知；新建与导入不覆盖
        "workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt" to 6, // 删整个工作区、临时目录
        "workspace/src/main/java/me/rerere/workspace/WorkspaceTextSnapshot.kt" to 3, // 原子保存，调用方已通知
        "workspace/src/main/java/me/rerere/workspace/WorkspaceTransfer.kt" to 3, // 已通知；拷贝只新建
        "workspace/src/main/java/me/rerere/workspace/WorkspaceTreeImport.kt" to 3, // 只新建
    )

    @Test
    fun `every raw write in workspace code has been reviewed for version history`() {
        val repo = File("").absoluteFile.let { if (File(it, "workspace").isDirectory) it else it.parentFile }
        val scanned = listOf(
            "workspace/src/main/java/me/rerere/workspace",
            "app/src/main/java/me/rerere/rikkihub/data/book",
            "app/src/main/java/me/rerere/rikkihub/data/repository/WorkspaceRepository.kt",
            "app/src/main/java/me/rerere/rikkihub/data/files/FileManagerService.kt",
            "app/src/main/java/me/rerere/rikkihub/data/provider/WorkspaceDocumentsProvider.kt",
        ).flatMap { path -> File(repo, path).walkTopDown().filter { it.extension == "kt" }.toList() }
        val found = scanned
            .associate { it.relativeTo(repo).invariantSeparatorsPath to rawWrite.findAll(it.readText()).count() }
            .filterValues { it > 0 }
        assertEquals(reviewed, found.toSortedMap())
    }
}
