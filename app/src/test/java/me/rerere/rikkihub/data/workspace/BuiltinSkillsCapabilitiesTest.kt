package me.rerere.rikkihub.data.workspace

import me.rerere.workspace.LocalWorkspaceBackend
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolCaller
import me.rerere.rikkihub.data.ai.tools.createWorkspaceToolSnapshot
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinSkillsCapabilitiesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val allToolsOff = "{\"workspace_read_file\":false,\"workspace_write_file\":false,\"workspace_edit_file\":false,\"workspace_shell\":false,\"bash\":false,\"str_replace_editor\":false,\"subagent\":false}"

    @Test fun `repository paired mount table feeds parent and child snapshots in normal and minimal modes`() = runBlocking {
        // Only the /skills switch varies; the WorkspaceManager bind table is fixed.
        var row = WorkspaceEntity("w", "test", "root", WorkspaceShellStatus.READY.name, 0, 0, toolEnablement = allToolsOff,
            exposeUpload = false, exposeToolOutputs = false)
        val manager = WorkspaceManager(temporary.newFolder("workspaces"), bindMounts = listOf(
            WorkspaceBindMount(temporary.newFolder("skills"), "/skills"),
            WorkspaceBindMount(temporary.newFolder("builtin"), "/builtin_skills", required = true),
        ))
        val dao = Proxy.newProxyInstance(WorkspaceDAO::class.java.classLoader, arrayOf(WorkspaceDAO::class.java)) { _, method, _ ->
            check(method.name == "getById")
            row
        } as WorkspaceDAO
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val settings = unsafeType.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, SettingsStore::class.java) as SettingsStore
        val repository = WorkspaceRepository(dao, LocalWorkspaceBackend(manager, RootfsInstaller(manager), java.io.File("/nonexistent-native"), null), settings, WorkspaceRuntimeGate())
        for (minimal in listOf(false, true)) for (exposed in listOf(false, true)) {
            row = row.copy(harnessMinimal = minimal, exposeSkills = exposed)
            val targets = repository.executionMountTargets(row.id)
            assertEquals(if (exposed) setOf("/skills", "/builtin_skills") else emptySet<String>(), targets)
            for (caller in listOf(WorkspaceToolCaller.PARENT, WorkspaceToolCaller.SUBAGENT_CHILD)) {
                val slice = createWorkspaceToolSnapshot(row, repository, cwd = "/workspace/project", caller = caller,
                    includeSubagent = caller == WorkspaceToolCaller.PARENT, loadWorkspace = { row }, exposedTargets = targets)
                assertEquals(targets, slice.capabilities.exposedTargets)
                assertTrue(slice.tools.isEmpty())
                assertTrue(slice.capabilities.instructionsEligible)
                assertEquals("/workspace/project", slice.capabilities.instructionsCwd)
            }
        }
        // Both actual production snapshot entry points pass this repository table,
        // rather than silently reverting to the entity's old /skills-only desired set.
        fun source(path: String) = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(source("app/src/main/java/me/rerere/rikkihub/data/ai/tools/ChatToolFactory.kt")
            .contains("workspaceRepository.executionMountTargets(workspaceId, filesScope)"))
        assertTrue(source("app/src/main/java/me/rerere/rikkihub/data/ai/tools/WorkspaceTools.kt")
            .contains("exposedTargets = repo.executionMountTargets(latest.id, filesScope)"))
    }
}
