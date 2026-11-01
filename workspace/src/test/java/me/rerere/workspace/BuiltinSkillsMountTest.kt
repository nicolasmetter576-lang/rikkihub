package me.rerere.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinSkillsMountTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun manager(runner: WorkspaceShellRunner = HostShellRunner()): WorkspaceManager = WorkspaceManager(
        baseDir = tmp.newFolder("workspaces"), shellRunner = runner,
        bindMounts = listOf(
            WorkspaceBindMount(tmp.newFolder("skills"), "/skills"),
            WorkspaceBindMount(tmp.newFolder("builtin_skills"), "/builtin_skills", required = true),
            WorkspaceBindMount(tmp.newFolder("upload"), "/upload"),
        ),
    )
    @Test fun `builtin target is optional policy mount not unconditional unknown target`() {
        assertTrue("/builtin_skills" in WorkspaceManager.OPTIONAL_AGENT_MOUNT_TARGETS)
        val manager = manager()
        assertTrue(manager.agentBindMounts(emptySet()).isEmpty())
        assertEquals(listOf("/upload"), manager.agentBindMounts(setOf("/upload")).map { it.target })
    }
    @Test fun `skills flag exposes both physical trees and builtin alone cannot bypass it`() {
        val manager = manager()
        assertEquals(listOf("/skills", "/builtin_skills"), manager.agentBindMounts(setOf("/skills")).map { it.target })
        assertEquals(listOf("/skills", "/builtin_skills"), manager.agentBindMounts(setOf("/skills", "/builtin_skills")).map { it.target })
        assertTrue(manager.agentBindMounts(setOf("/builtin_skills")).isEmpty())
    }
    @Test fun `noninteractive shell gets the same paired selection`() {
        var captured: WorkspaceShellContext? = null
        val manager = manager(object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                captured = context
                return WorkspaceCommandResult(0, "", "", false)
            }
        })
        manager.ensureWorkspace("w1")
        manager.executeCommand("w1", "true", exposedTargets = setOf("/skills"))
        assertEquals(listOf("/skills", "/builtin_skills"), captured!!.bindMounts.map { it.target })
        manager.executeCommand("w1", "true", exposedTargets = emptySet())
        assertTrue(captured!!.bindMounts.isEmpty())
    }
    @Test fun `shared proot argv used by command persistent bash and both terminal channels carries pair`() {
        val manager = manager()
        val files = tmp.newFolder("files")
        val linux = tmp.newFolder("linux")
        val proot = tmp.newFile("proot")
        val mounts = manager.agentBindMounts(setOf("/skills"))
        for (command in listOf(listOf("/bin/bash", "-l", "-c", "true"), listOf("/bin/bash"))) {
            val args = ProotLauncher.buildCommand(proot, files, linux, bindMounts = mounts, command = command)
            mounts.forEach { assertTrue("${it.source.absolutePath}:${it.target}" in args) }
        }
        val hidden = ProotLauncher.buildCommand(proot, files, linux, bindMounts = manager.agentBindMounts(emptySet()))
        assertFalse(hidden.any { it.endsWith(":/skills") || it.endsWith(":/builtin_skills") })
    }
    @Test fun `rootfs file mapping keeps user and packaged trees physically separate and writable`() {
        val manager = manager()
        manager.ensureWorkspace("w1")
        val mounts = manager.agentBindMounts(setOf("/skills"))
        val user = mounts.first { it.target == "/skills" }.source.resolve("creator.txt").also { it.writeText("user") }
        val builtin = mounts.first { it.target == "/builtin_skills" }.source.resolve("creator.txt").also { it.writeText("builtin") }
        val userLocation = manager.resolveRootfsPath("w1", "/skills/creator.txt", mounts)
        val builtinLocation = manager.resolveRootfsPath("w1", "/builtin_skills/creator.txt", mounts)
        assertEquals(user, File(userLocation.rootDir, userLocation.relativePath))
        assertEquals(builtin, File(builtinLocation.rootDir, builtinLocation.relativePath))
        assertNotEquals(userLocation.rootDir, builtinLocation.rootDir)
        user.writeText("edited user")
        assertEquals("builtin", builtin.readText())
        // No OS-readonly claim: this bind's host source is still writable.
        builtin.writeText("shell could edit")
        assertEquals("shell could edit", builtin.readText())
    }
    @Test fun `symlink builtin source is rejected only when that pair is exposed`() {
        val manager = manager()
        val source = manager.bindMounts.first { it.target == "/builtin_skills" }.source
        source.delete()
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(source.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { manager.agentBindMounts(setOf("/skills")) }
        assertTrue(manager.agentBindMounts(emptySet()).isEmpty())
    }
}
