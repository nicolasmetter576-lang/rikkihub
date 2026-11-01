package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.text.TextRange
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 兼容边界: 编辑器草稿落盘器 (WorkspaceEditorDraftStore) + VM 绑定语义的纯 JVM 回归.
 * Bundle 只存 UUID token; 全文+选区写 token 文件; restore 严格 UUID 防穿越;
 * fileKey 不匹配只拒绝不删除; VM 只清理自己绑定的 token 文件.
 */
class WorkspaceEditorDraftStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var draftDir: File
    private val errors = mutableListOf<Throwable>()

    private fun newStore(): WorkspaceEditorDraftStore {
        draftDir = File(tmp.root, "workspace_editor_drafts")
        return WorkspaceEditorDraftStore(draftDir, onError = { errors.add(it) })
    }

    private val key = "w1\u0000FILES\u0000docs/note.md"

    @Test
    fun `save restore roundtrip with huge text and selection survives restart`() {
        val store = newStore()
        // >2MB 全文, 含多行、unicode 与转义 NUL, 覆盖二进制读写与长度头路径
        val huge = buildString(2 * 1024 * 1024) {
            var i = 0
            while (length < 2 * 1024 * 1024) {
                append("line").append(i++).append("\n中文\u0000")
            }
        }
        val selStart = 1234
        val selEnd = 4321
        val token = store.save(key, huge, selStart, selEnd)
        assertNotNull(token)
        assertTrue(store.isValidToken(token))
        assertTrue(File(draftDir, "$token.draft").isFile)
        assertTrue(draftDir.listFiles()!!.none { it.name.endsWith(".tmp") })

        // 模拟进程死亡 + 重启: 全新 store, 只凭 Bundle 中的 token 恢复
        val restored = newStore().restore(token!!)
        assertNotNull(restored)
        assertEquals(key, restored!!.fileKey)
        assertEquals(huge, restored.text)
        // 选区随全文一起恢复
        assertEquals(selStart, restored.selStart)
        assertEquals(selEnd, restored.selEnd)
    }

    @Test
    fun `restore rejects invalid and traversal tokens`() {
        val store = newStore()
        val invalid = listOf(
            "", " ", "..", "../evil", "../../etc/passwd", "a/b/c", "x/../y",
            "not-a-uuid",
            "ffffffffffffffffffffffffffffffff",
            "ffffffff-ffff-ffff-ffff-fffffffffffg",
            "ffffffff-ffff-ffff-ffff-fffffffffff",
            "ffffffff-ffff-ffff-ffff-ffffffffffff/extra",
        )
        for (token in invalid) {
            assertFalse("should reject: $token", store.isValidToken(token))
            assertNull("should not touch disk: $token", store.restore(token))
        }
        assertNull(draftDir.listFiles()) // 非法 token 不碰盘, 目录都没建
    }

    @Test
    fun `restore returns bound fileKey and mismatch must not delete draft`() {
        val store = newStore()
        val token = store.save(key, "draft of A", 0, 5)!!
        val restored = store.restore(token)!!
        assertEquals(key, restored.fileKey)
        // 页面侧比较: 别的 fileKey 不匹配 → 只拒绝, 文件必须保留 (可能属别的页面)
        assertNotEquals("w2\u0000FILES\u0000other.md", restored.fileKey)
        assertTrue(store.hasDraft(token))
    }

    @Test
    fun `corrupt drafts return null and are removed`() {
        val store = newStore()
        fun corrupt(content: ByteArray): String {
            draftDir.mkdirs()
            val token = store.newToken()
            File(draftDir, "$token.draft").writeBytes(content)
            return token
        }
        val cases = listOf(
            "garbage".toByteArray(),
            byteArrayOf(0x52, 0x4B, 0x44),
            ByteArray(0),
            validPlusJunk(store),
            truncatedValid(store),
            outOfRangeSelection(store),
        )
        for (bytes in cases) {
            val token = corrupt(bytes)
            assertNull(store.restore(token))
            assertFalse(File(draftDir, "$token.draft").exists())
        }
    }

    private fun validPlusJunk(store: WorkspaceEditorDraftStore): ByteArray {
        val token = store.save(key, "x", 0, 0)!!
        val valid = File(draftDir, "$token.draft").readBytes()
        store.delete(token)
        return valid + byteArrayOf(1, 2, 3)
    }

    private fun truncatedValid(store: WorkspaceEditorDraftStore): ByteArray {
        val token = store.save(key, "some longer draft text", 0, 4)!!
        val valid = File(draftDir, "$token.draft").readBytes()
        store.delete(token)
        return valid.copyOf(valid.size / 2)
    }

    /** 手工拼一个 "结构合法但选区越界" 的损坏草稿 (selEnd > text.length). */
    private fun outOfRangeSelection(store: WorkspaceEditorDraftStore): ByteArray {
        val token = store.save(key, "short", 0, 5)!!
        val bytes = File(draftDir, "$token.draft").readBytes()
        store.delete(token)
        // 最后 4 字节是 selEnd, 改成越界值
        val end = bytes.size
        bytes[end - 4] = 0x7F
        return bytes
    }

    @Test
    fun `restore missing file returns null without error`() {
        val store = newStore()
        assertNull(store.restore(store.newToken()))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `save failure returns null and reports error without throwing`() {
        // 草稿目录路径被同名文件占用 -> 写盘失败, 返回 null + 记录, 不抛异常毁 Activity
        val blocked = tmp.newFile("blocked")
        val store = WorkspaceEditorDraftStore(blocked, onError = { errors.add(it) })
        assertNull(store.save(key, "content", 0, 0))
        assertTrue(errors.isNotEmpty())
        assertTrue(blocked.isFile)
        assertEquals(0L, blocked.length())
    }

    @Test
    fun `delete removes only the bound token files`() {
        val store = newStore()
        val t1 = store.save(key, "draft-1", 0, 0)!!
        val t2 = store.save(key, "draft-2", 0, 0)!!
        store.delete(t1)
        assertFalse(store.hasDraft(t1))
        assertTrue(store.hasDraft(t2))
        assertEquals("draft-2", store.restore(t2)!!.text)
        // 非法/未知 token 一律不碰盘
        store.delete(null)
        store.delete("not-uuid")
        store.delete("../x")
        store.delete(store.newToken()) // 合法但不存在
        assertTrue(store.hasDraft(t2))
    }

    @Test
    fun `vm cleanup removes only its own bound draft`() {
        val store = newStore()
        val vm1 = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        val vm2 = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        val t1 = store.save(key, "vm1 draft", 0, 0)!!
        val t2 = store.save(key, "vm2 draft", 0, 0)!!
        vm1.bindDraftToken(t1, "fp1", selection(0, 0))
        vm2.bindDraftToken(t2, "fp2", selection(0, 0))

        vm1.discardBoundDraft()
        assertNull(vm1.draftToken)
        assertFalse(store.hasDraft(t1))
        assertTrue(store.hasDraft(t2))
    }

    @Test
    fun `vm rebinding deletes superseded draft file`() {
        val store = newStore()
        val vm = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        val t1 = store.save(key, "v1", 0, 0)!!
        val t2 = store.save(key, "v2", 0, 0)!!
        vm.bindDraftToken(t1, "fp1", selection(0, 0))
        vm.bindDraftToken(t2, "fp2", selection(0, 0))
        assertEquals(t2, vm.draftToken)
        assertFalse(store.hasDraft(t1))
        assertTrue(store.hasDraft(t2))
        vm.bindDraftToken(t2, "fp2", selection(0, 0))
        assertTrue(store.hasDraft(t2))
    }

    @Test
    fun `adoptRestoredDraft sets text selection and loaded marker together`() {
        val store = newStore()
        val vm = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        val text = "recovered text"
        val token = store.save(key, text, 3, 11)!!
        vm.adoptRestoredDraft(text, selStart = 3, selEnd = 11, token = token, fileKey = key)
        assertTrue(vm.hasLiveDraft(key))
        assertEquals(text, vm.textState.text.toString())
        // 合法范围内的选区被恢复, 且参与后续 save 去重判定
        assertEquals(selection(3, 11), vm.textState.selection)
        assertEquals(selection(3, 11), vm.persistedSelection)
        assertEquals(token, vm.draftToken)

        val fresh = WorkspaceEditorDraftViewModel()
        assertNull(fresh.loadedFileKey)
        assertFalse(fresh.hasLiveDraft(key))
    }

    @Test
    fun `adoptRestoredDraft keeps live memory draft and drops other stale disk draft`() {
        val store = newStore()
        val vm = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        vm.textState.edit { append("memory edit") }
        vm.loadedFileKey = key
        val stale = store.save(key, "stale disk draft", 0, 0)!!
        vm.adoptRestoredDraft("stale disk draft", 0, 0, stale, key)
        assertEquals("memory edit", vm.textState.text.toString())
        assertFalse(store.hasDraft(stale))
    }

    @Test
    fun `adoptRestoredDraft never deletes own still-valid token file`() {
        val store = newStore()
        val vm = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        // VM 已有内存草稿且 token == 当前绑定 token: 那是自己的仍有效文件, 不能删
        val own = store.save(key, "own draft", 0, 0)!!
        vm.textState.edit { append("memory draft") }
        vm.loadedFileKey = key
        vm.bindDraftToken(own, "fp", selection(0, 0))
        vm.adoptRestoredDraft("own draft", 0, 0, own, key)
        assertEquals("memory draft", vm.textState.text.toString())
        assertTrue(store.hasDraft(own)) // 自己的草稿文件必须还在
    }

    @Test
    fun `draft files never touch workspace original`() {
        val store = newStore()
        // 工作区原件在草稿目录之外, 全程读写删除后字节不变
        val workspaceDir = tmp.newFolder("workspace")
        val original = File(workspaceDir, "note.md")
        original.writeText("original content")
        val token = store.save(key, "edited draft", 0, 0)!!
        store.restore(token)
        store.delete(token)
        assertEquals("original content", original.readText())
        // 草稿只落在注入目录内且命名严格 token.draft/token.tmp (此时目录应为空)
        draftDir.listFiles()?.forEach {
            assertTrue(it.name.endsWith(".draft") || it.name.endsWith(".tmp"))
        }
    }

    @Test
    fun `stable token keeps earlier saved bundles valid after snapshot updates`() {
        val store = newStore()
        val token = store.save(key, "old text", 2, 2)!!
        assertEquals(token, store.save(key, "new text", 7, 1, token))
        val restarted = newStore().restore(token)!!
        assertEquals("new text", restarted.text)
        assertEquals(7, restarted.selStart)
        assertEquals(1, restarted.selEnd)
        assertNull(store.save("other-file", "overwrite", 0, 0, token))
        assertEquals("new text", store.restore(token)!!.text)
        assertEquals(1, draftDir.listFiles()!!.count { it.name.endsWith(".draft") })
    }

    @Test
    fun `saved token survives activity view model store clearing`() {
        val store = newStore()
        val token = store.save(key, "unsaved text", 8, 2)!!
        val vm = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        vm.adoptRestoredDraft("unsaved text", 8, 2, token, key)
        val owner = ViewModelStore()
        owner.put("editor", vm)
        owner.clear() // Activity reclaim may clear this store while the system retains its Bundle.
        val restored = store.restore(token)!!
        val recreated = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        recreated.adoptRestoredDraft(restored.text, restored.selStart, restored.selEnd, token, key)
        assertEquals("unsaved text", recreated.textState.text.toString())
        assertEquals(TextRange(8, 2), recreated.textState.selection)
    }

    @Test
    fun `reversed selection survives process death recovery`() {
        val store = newStore()
        val token = store.save(key, "hello", 4, 1)!!
        val draft = store.restore(token)!!
        val vm = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        vm.adoptRestoredDraft(draft.text, draft.selStart, draft.selEnd, token, key)
        assertEquals("hello", vm.textState.text.toString())
        assertEquals(TextRange(4, 1), vm.textState.selection)
        assertTrue(vm.hasLiveDraft(key))
    }

    @Test
    fun `content fingerprint is sha256 and survives classic rolling hash collision`() {
        assertEquals(
            workspaceEditorDraftFingerprint("same text"),
            workspaceEditorDraftFingerprint("same text"),
        )
        // "Aa"/"BB" 是 31 滚动 hash 的经典碰撞对, SHA-256 指纹必须区分,
        // 否则已修改草稿会错误复用旧 token 丢内容
        assertNotEquals(
            workspaceEditorDraftFingerprint("Aa"),
            workspaceEditorDraftFingerprint("BB"),
        )
        assertNotEquals(
            workspaceEditorDraftFingerprint("same text"),
            workspaceEditorDraftFingerprint("same text!"),
        )
        assertEquals(64, workspaceEditorDraftFingerprint("x").length) // hex 64 位
    }

    @Test
    fun `opened text roundtrips so revert survives process death`() {
        val store = newStore()
        val rev = "a".repeat(64)
        val token = store.save(key, "edited", 1, 2, originalText = "autosaved", revision = rev, openedText = "opened\r\n中文")!!
        val restored = newStore().restore(token)!!
        assertEquals("edited", restored.text)
        assertEquals("autosaved", restored.originalText)
        assertEquals(rev, restored.revision)
        assertEquals("opened\r\n中文", restored.openedText)
        assertEquals(1, restored.selStart)
        assertEquals(2, restored.selEnd)

        val withoutOpened = store.save(key, "x", 0, 0)!!
        assertNull(store.restore(withoutOpened)!!.openedText)

        val vm = WorkspaceEditorDraftViewModel().apply { draftStore = store }
        vm.adoptRestoredDraft(restored.text, restored.selStart, restored.selEnd, token, key,
            restored.originalText, restored.revision, restored.openedText)
        assertEquals("opened\r\n中文", vm.openedText)
    }

    @Test
    fun `legacy v2 draft still restores without opened text`() {
        val store = newStore()
        draftDir.mkdirs()
        val token = store.newToken()
        val rev = "b".repeat(64)
        val bytes = java.io.ByteArrayOutputStream().also { buffer ->
            java.io.DataOutputStream(buffer).use { out ->
                out.writeInt(0x524B4431)
                out.writeInt(2)
                key.toByteArray().let { out.writeInt(it.size); out.write(it) }
                "v2 text".toByteArray().let { out.writeLong(it.size.toLong()); out.write(it) }
                out.writeBoolean(true)
                "v2 original".toByteArray().let { out.writeLong(it.size.toLong()); out.write(it) }
                rev.toByteArray().let { out.writeInt(it.size); out.write(it) }
                out.writeInt(2)
                out.writeInt(3)
            }
        }.toByteArray()
        File(draftDir, "$token.draft").writeBytes(bytes)
        val restored = store.restore(token)!!
        assertEquals("v2 text", restored.text)
        assertEquals("v2 original", restored.originalText)
        assertEquals(rev, restored.revision)
        assertNull(restored.openedText)
        assertEquals(2, restored.selStart)
        assertEquals(3, restored.selEnd)
    }

    private fun selection(start: Int, end: Int) = TextRange(start, end)
}
