package me.rerere.rikkihub.workspace

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceTransferMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * 跨工作区移动/复制在真机存储上的行为：App 的 Repository 入口与系统文件管理器走的
 * DocumentsProvider（moveDocument / copyDocument 跨 root）都落到同一套实现，重名自动改名、不覆盖。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceTransferInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get() = GlobalContext.get().get<WorkspaceRepository>()
    private val authority get() = context.packageName + ".documents"

    private lateinit var a: WorkspaceEntity
    private lateinit var b: WorkspaceEntity

    private fun files(workspace: WorkspaceEntity) = File(context.filesDir.canonicalFile, "workspaces/${workspace.root}/files")

    private fun doc(workspace: WorkspaceEntity, path: String = ""): Uri =
        DocumentsContract.buildDocumentUri(authority, if (path.isEmpty()) "ws/${workspace.root}" else "ws/${workspace.root}/$path")

    @Before
    fun setUp() = runBlocking {
        val stamp = System.currentTimeMillis()
        a = repo.create("itest-transfer-a-$stamp")
        b = repo.create("itest-transfer-b-$stamp")
        File(files(a), "notes").mkdirs()
        File(files(a), "notes/第一章.md").writeText("新")
        File(files(a), "notes/sub").mkdirs()
        File(files(a), "notes/sub/x.txt").writeText("X")
        File(files(a), "keep.txt").writeText("K")
        File(files(b), "archive").mkdirs()
        File(files(b), "archive/第一章.md").writeText("旧")
    }

    @After
    fun tearDown() = runBlocking {
        repo.delete(a.id)
        repo.delete(b.id)
        Unit
    }

    @Test
    fun repositoryMovesAcrossWorkspacesAndRenamesOnClash() = runBlocking {
        val result = repo.transferFiles(a.id, listOf("notes/第一章.md"), b.id, "archive", WorkspaceTransferMode.MOVE)

        val item = result.items.single()
        assertEquals("archive/第一章 (1).md", item.target)
        assertTrue(item.renamed)
        assertFalse(File(files(a), "notes/第一章.md").exists())
        assertEquals("旧", File(files(b), "archive/第一章.md").readText())
        assertEquals("新", File(files(b), "archive/第一章 (1).md").readText())
    }

    @Test
    fun repositoryCopiesAFolderTreeAndKeepsTheSource() = runBlocking {
        val result = repo.transferFiles(a.id, listOf("notes"), b.id, "", WorkspaceTransferMode.COPY)

        assertEquals("notes", result.items.single().target)
        assertEquals("X", File(files(a), "notes/sub/x.txt").readText())
        assertEquals("X", File(files(b), "notes/sub/x.txt").readText())
    }

    @Test
    fun documentsProviderMoveAcrossRootsUsesTheSameRules() {
        val resolver = context.contentResolver
        val moved = DocumentsContract.moveDocument(
            resolver,
            doc(a, "notes/第一章.md"),
            doc(a, "notes"),
            doc(b, "archive"),
        )

        assertEquals(doc(b, "archive/第一章 (1).md"), moved)
        assertFalse(File(files(a), "notes/第一章.md").exists())
        assertEquals("新", File(files(b), "archive/第一章 (1).md").readText())
    }

    @Test
    fun documentsProviderCopyAcrossRootsKeepsTheSource() {
        val resolver = context.contentResolver
        val copied = DocumentsContract.copyDocument(resolver, doc(a, "notes"), doc(b, "archive"))

        assertEquals(doc(b, "archive/notes"), copied)
        assertEquals("新", File(files(a), "notes/第一章.md").readText())
        assertEquals("X", File(files(b), "archive/notes/sub/x.txt").readText())
    }
}
