package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.persistentBashSessionKey
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.LocalWorkspaceBackend
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy

/** 范围进挂载/提示词/AGENTS.md/常驻 bash 键，附件复制进所选目录。 */
class WorkspaceCwdScopeTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var manager: WorkspaceManager

    private fun repository(): WorkspaceRepository {
        manager = WorkspaceManager(
            temporary.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(temporary.newFolder("skills"), "/skills"),
                WorkspaceBindMount(temporary.newFolder("tool_outputs"), "/tool_outputs"),
                WorkspaceBindMount(temporary.newFolder("upload"), "/upload"),
            ),
        ).also { it.ensureWorkspace("root") }
        val row = WorkspaceEntity("w", "test", "root", WorkspaceShellStatus.READY.name, 0, 0)
        val dao = Proxy.newProxyInstance(WorkspaceDAO::class.java.classLoader, arrayOf(WorkspaceDAO::class.java)) { _, method, _ ->
            check(method.name == "getById") { "Unexpected DAO call: ${method.name}" }
            row
        } as WorkspaceDAO
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val settings = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(field.get(null), SettingsStore::class.java) as SettingsStore
        return WorkspaceRepository(
            dao, LocalWorkspaceBackend(manager, RootfsInstaller(manager), File("/nonexistent-native"), null),
            settings, WorkspaceRuntimeGate(),
        )
    }

    @Test
    fun `scoped mounts keep only tool outputs and confine file reads`() = runBlocking {
        val repo = repository()
        val files = manager.filesDir("root")
        File(files, "a/b").mkdirs()
        File(files, "a/b/AGENTS.md").writeText("scoped rules")
        File(files, "AGENTS.md").writeText("root rules")

        assertEquals(setOf("/tool_outputs"), repo.executionMountTargets("w", "a/b"))
        assertTrue("/skills" in repo.executionMountTargets("w"))
        assertTrue("/upload" in repo.executionMountTargets("w"))

        assertEquals("scoped rules", repo.readAgentsInstructions("w", "/workspace/a/b/AGENTS.md", "a/b"))
        assertTrue(runCatching { repo.readAgentsInstructions("w", "/workspace/AGENTS.md", "a/b") }.isFailure)
        assertTrue(runCatching { repo.statRootfs("w", "/skills", "a/b") }.getOrNull()?.exists != true)
        assertFalse(runCatching { repo.listRootfs("w", "/workspace", "a/b") }.isSuccess)
        assertEquals(1, repo.listRootfs("w", "/workspace/a/b", "a/b").size)
    }

    @Test
    fun `agents paths drop the workspace root file when scoped`() {
        assertEquals(
            listOf("/root/.agents/AGENTS.md", "/workspace/a/b/AGENTS.md"),
            workspaceAgentsPaths("/workspace/a/b", "a/b"),
        )
        assertTrue("/workspace/AGENTS.md" in workspaceAgentsPaths("/workspace/a/b"))
    }

    @Test
    fun `reminder names the confined directory`() {
        val caps = WorkspaceRequestCapabilities(
            workspaceId = "w", workspaceName = "test", harnessMinimal = false,
            availableToolNames = emptyMap(), exposedTargets = emptySet(), filesScope = "a/b",
        )
        val prompt = workspaceReminderPrompt("test", false, emptySet(), "/workspace/a/b", emptyMap(), caps)
        assertTrue(prompt, prompt.contains("You are confined to `/workspace/a/b`"))
        val unscoped = workspaceReminderPrompt("test", false, emptySet(), "/workspace/a/b", emptyMap(), caps.copy(filesScope = null))
        assertFalse(unscoped.contains("confined"))
    }

    @Test
    fun `persistent bash sessions never share a shell across scopes`() {
        assertEquals("w", persistentBashSessionKey("w", null))
        assertNotEquals(persistentBashSessionKey("w", null, "a"), persistentBashSessionKey("w", null))
        assertNotEquals(persistentBashSessionKey("w", null, "a"), persistentBashSessionKey("w", null, "b"))
        assertNotEquals(persistentBashSessionKey("w", "o", "a"), persistentBashSessionKey("w", null, "a"))
    }

    @Test
    fun `attachments are copied once and keep their metadata`() = runBlocking {
        val doc = temporary.newFile("report.pdf").apply { writeText("pdf") }
        val image = temporary.newFile("abc123.png").apply { writeText("png") }
        val imported = mutableListOf<String>()
        val parts = listOf(
            UIMessagePart.Text("hi"),
            UIMessagePart.Document(doc.toURI().toString(), "季度报告.pdf", "application/pdf"),
            UIMessagePart.Image(image.toURI().toString(), metadata = buildJsonObject { put("k", JsonPrimitive("v")) }),
            UIMessagePart.Image("https://example.com/remote.png"),
        )
        val result = copyAttachmentsIntoWorkspaceScope(parts) { name, input ->
            imported += name
            input.readBytes()
            "a/b/$name"
        }
        assertEquals(listOf("季度报告.pdf", "abc123.png"), imported)
        assertEquals("/workspace/a/b/季度报告.pdf", result[1].workspacePath)
        assertEquals("/workspace/a/b/abc123.png", result[2].workspacePath)
        assertEquals(JsonPrimitive("v"), result[2].metadata!!["k"])
        assertNull(result[3].workspacePath)

        // Already copied: never imported again (a deleted copy is not put back).
        imported.clear()
        copyAttachmentsIntoWorkspaceScope(result) { _, _ -> error("must not import again") }
        assertTrue(imported.isEmpty())

        // A failed copy leaves the part as it was.
        val failed = copyAttachmentsIntoWorkspaceScope(parts.subList(1, 2)) { _, _ -> throw IOException("disk full") }
        assertNull(failed.single().workspacePath)
    }
}
