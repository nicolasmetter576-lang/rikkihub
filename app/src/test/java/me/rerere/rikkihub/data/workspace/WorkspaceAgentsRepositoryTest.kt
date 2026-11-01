package me.rerere.rikkihub.data.workspace

import me.rerere.workspace.LocalWorkspaceBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class WorkspaceAgentsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gate = WorkspaceRuntimeGate()
    private var row: WorkspaceEntity? = WorkspaceEntity("w", "test", "root", WorkspaceShellStatus.READY.name, 0, 0)
    private var observedFileOperation = false
    private lateinit var manager: WorkspaceManager

    private fun repository(): WorkspaceRepository {
        manager = WorkspaceManager(temporary.newFolder("workspaces")).also { it.ensureWorkspace("root") }
        val dao = Proxy.newProxyInstance(WorkspaceDAO::class.java.classLoader, arrayOf(WorkspaceDAO::class.java)) { _, method, _ ->
            check(method.name == "getById") { "Unexpected DAO call: ${method.name}" }
            if ((gate.snapshot("w").activeOperationsByKind[WorkspaceOperationKind.FILE_OPERATION] ?: 0) > 0) {
                observedFileOperation = true
            }
            row
        } as WorkspaceDAO
        // This reader never accesses settings; match existing repository-test detached fixture.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val settings = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(field.get(null), SettingsStore::class.java) as SettingsStore
        return WorkspaceRepository(dao, LocalWorkspaceBackend(manager, RootfsInstaller(manager), java.io.File("/nonexistent-native"), null), settings, gate)
    }

    @Test fun realReaderUsesFilesAndLinuxRootsAndGate() = runBlocking {
        val repo = repository()
        File(manager.linuxDir("root"), "root/.agents").mkdirs()
        File(manager.linuxDir("root"), "root/.agents/AGENTS.md").writeText("global")
        File(manager.filesDir("root"), "AGENTS.md").writeText("workspace")
        assertEquals("global", repo.readAgentsInstructions("w", "/root/.agents/AGENTS.md"))
        assertEquals("workspace", repo.readAgentsInstructions("w", "/workspace/AGENTS.md"))
        assertTrue(observedFileOperation)
        assertEquals(0, gate.snapshot("w").activeOperations)
    }

    @Test fun realReaderEnforcesSizeAndMissingFile() = runBlocking {
        val repo = repository()
        val file = File(manager.filesDir("root"), "AGENTS.md")
        file.writeBytes(ByteArray(MAX_AGENTS_BYTES) { 65 })
        assertEquals(MAX_AGENTS_BYTES, repo.readAgentsInstructions("w", "/workspace/AGENTS.md").toByteArray().size)
        file.appendBytes(byteArrayOf(65))
        assertTrue(runCatching { repo.readAgentsInstructions("w", "/workspace/AGENTS.md") }.exceptionOrNull() is IllegalArgumentException)
        file.delete()
        assertTrue(runCatching { repo.readAgentsInstructions("w", "/workspace/AGENTS.md") }.isFailure)
    }

    @Test fun missingNotReadyAndClosedAreNotAdmitted() = runBlocking {
        val repo = repository()
        row = null
        assertTrue(runCatching { repo.readAgentsInstructions("w", "/workspace/AGENTS.md") }.exceptionOrNull() is WorkspaceUnavailableException)
        row = WorkspaceEntity("w", "test", "root", WorkspaceShellStatus.DISABLED.name, 0, 0)
        assertTrue(runCatching { repo.readAgentsInstructions("w", "/workspace/AGENTS.md") }.exceptionOrNull() is WorkspaceUnavailableException)
        gate.closeWorkspace("w")
        assertTrue(runCatching { repo.readAgentsInstructions("w", "/workspace/AGENTS.md") }.exceptionOrNull() is WorkspaceUnavailableException)
        assertFalse(observedFileOperation)
    }

    @Test fun structuralCloseCancelsInstructionOperationRatherThanReturningPartialPrompt() = runBlocking {
        withTimeout(5_000) {
            supervisorScope {
                val started = CompletableDeferred<Unit>()
                val read = async {
                    gate.withOperation("w", WorkspaceOperationKind.FILE_OPERATION, handleClass = WorkspaceHandleClass.FILE_OPERATION) {
                        workspaceAgentsInstructions(null) {
                            readBoundedAgentsText(1) { output ->
                                output.write(65)
                                started.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    }
                }
                started.await()
                gate.closeWorkspace("w")
                assertTrue(runCatching { read.await() }.exceptionOrNull() is WorkspacePolicyCancellationException)
            }
        }
    }
}
