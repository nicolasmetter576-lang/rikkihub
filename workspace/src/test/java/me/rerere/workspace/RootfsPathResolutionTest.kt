package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class RootfsPathResolutionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var manager: WorkspaceManager

    private val root = "test-workspace"

    private fun createManager(): WorkspaceManager {
        skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        return WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills"),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        ).also { it.ensureWorkspace(root) }
    }

    @Test
    fun readsFileWrittenThroughBindMountPath() {
        manager = createManager()
        File(skillsDir, "issue-1561").mkdirs()
        File(skillsDir, "issue-1561/SKILL.md").writeText("---\nversion: before\n---\n")

        val size = manager.rootfsFileSize(root, "/skills/issue-1561/SKILL.md")
        val buffer = ByteArrayOutputStream(size.toInt())
        manager.exportRootfsFile(root, "/skills/issue-1561/SKILL.md", buffer)

        assertEquals("---\nversion: before\n---\n", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun bindMountTargetDoesNotMatchLongerSiblingPrefix() {
        val skills = tempFolder.newFolder("skills-src")
        val skillsets = tempFolder.newFolder("skillsets-src")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skills, target = "/skills"),
                WorkspaceBindMount(source = skillsets, target = "/skillsets"),
            ),
        ).also { it.ensureWorkspace(root) }

        assertEquals(skills, manager.resolveRootfsPath(root, "/skills/a.md").rootDir)
        assertEquals(skillsets, manager.resolveRootfsPath(root, "/skillsets/a.md").rootDir)
    }

    @Test
    fun workspacePathStillResolvesToFilesArea() {
        manager = createManager()
        File(manager.filesDir(root), "notes.txt").writeText("hello")

        val location = manager.resolveRootfsPath(root, "/workspace/notes.txt")
        assertEquals(manager.filesDir(root), location.rootDir)
        assertEquals("notes.txt", location.relativePath)

        val buffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/workspace/notes.txt", buffer)
        assertEquals("hello", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun unknownAbsolutePathFallsBackToRootfsInterior() {
        manager = createManager()
        File(manager.linuxDir(root), "etc").mkdirs()
        File(manager.linuxDir(root), "etc/hostname").writeText("rikkihub\n")

        val buffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/etc/hostname", buffer)
        assertEquals("rikkihub\n", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun traversalOutOfBindMountIsRejected() {
        manager = createManager()
        tempFolder.newFile("secret.txt").writeText("secret")

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/../secret.txt")
        }
        assertTrue(error.message!!.contains("escapes workspace root"))
    }

    @Test
    fun kernelFilesystemPathIsRejectedWithHint() {
        manager = createManager()

        val error = assertThrows(IllegalStateException::class.java) {
            manager.rootfsFileSize(root, "/proc/version")
        }
        assertTrue(error.message!!.contains("workspace_shell"))
    }

    @Test
    fun missingFileReportsOriginalAbsolutePath() {
        manager = createManager()

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/missing/SKILL.md")
        }
        assertEquals("File does not exist: /skills/missing/SKILL.md", error.message)
    }

    @Test
    fun directoryPathIsNotReadableAsFile() {
        manager = createManager()
        File(skillsDir, "issue-1561").mkdirs()

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/issue-1561")
        }
        assertEquals("Path is not a file: /skills/issue-1561", error.message)
    }

    @Test
    fun rootfsStatAndWriteSupportHarnessEditorPaths() {
        manager = createManager()
        val created = manager.writeRootfsText(root, "/workspace/notes.md", "hello world\n", overwrite = false)
        assertEquals("/workspace/notes.md", created.path)
        assertEquals("notes.md", created.name)

        val info = manager.rootfsStat(root, "/workspace/notes.md")
        assertTrue(info.exists)
        assertTrue(info.isFile)
        assertFalse(info.isDirectory)

        val listed = manager.listRootfs(root, "/workspace")
        assertTrue(listed.any { it.path == "/workspace/notes.md" && !it.isDirectory })

        manager.writeRootfsText(root, "/workspace/notes.md", "hello kotlin\n", overwrite = true)
        assertEquals("hello kotlin\n", File(manager.filesDir(root), "notes.md").readText())
    }

    @Test
    fun optionalMountsFollowExposedTargets() {
        skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        val outputsDir = tempFolder.newFolder("tool_outputs")
        File(skillsDir, "demo").mkdirs()
        File(skillsDir, "demo/SKILL.md").writeText("skill\n")
        File(uploadDir, "photo.txt").writeText("upload\n")
        File(outputsDir, "call.txt").writeText("output\n")
        manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills"),
                WorkspaceBindMount(source = outputsDir, target = "/tool_outputs"),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        ).also { it.ensureWorkspace(root) }

        val isolated = manager.agentBindMounts(setOf("/tool_outputs"))
        assertEquals(listOf("/tool_outputs"), isolated.map { it.target })
        val all = manager.agentBindMounts(setOf("/skills", "/upload", "/tool_outputs"))
        assertEquals(setOf("/skills", "/upload", "/tool_outputs"), all.map { it.target }.toSet())

        val size = manager.rootfsFileSize(root, "/tool_outputs/call.txt", isolated)
        val buffer = ByteArrayOutputStream(size.toInt())
        manager.exportRootfsFile(root, "/tool_outputs/call.txt", buffer, isolated)
        assertEquals("output\n", buffer.toString(Charsets.UTF_8.name()))

        val skillError = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/demo/SKILL.md", isolated)
        }
        assertTrue(skillError.message!!.contains("does not exist"))

        val uploadError = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/upload/photo.txt", isolated)
        }
        assertTrue(uploadError.message!!.contains("does not exist"))

        assertEquals(
            skillsDir,
            manager.resolveRootfsPath(root, "/skills/demo/SKILL.md").rootDir,
        )
    }

    @Test
    fun agentsSourcesUseLinuxFilesAndNestedFilesRoots() {
        manager = createManager()
        File(manager.linuxDir(root), "root/.agents").mkdirs()
        File(manager.linuxDir(root), "root/.agents/AGENTS.md").writeText("global")
        File(manager.filesDir(root), "project").mkdirs()
        File(manager.filesDir(root), "AGENTS.md").writeText("workspace")
        File(manager.filesDir(root), "project/AGENTS.md").writeText("cwd")
        for ((path, expected) in listOf(
            "/root/.agents/AGENTS.md" to "global", "/workspace/AGENTS.md" to "workspace",
            "/workspace/project/AGENTS.md" to "cwd",
        )) {
            val output = ByteArrayOutputStream()
            manager.exportRootfsFile(root, path, output)
            assertEquals(expected, output.toString("UTF-8"))
        }
    }

    @Test
    fun agentsReadAllowsInternalSymlinkButRejectsEscape() {
        manager = createManager()
        val files = manager.filesDir(root)
        File(files, "real").mkdirs()
        File(files, "real/AGENTS.md").writeText("internal")
        java.nio.file.Files.createSymbolicLink(File(files, "project").toPath(), File(files, "real").toPath())
        val output = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/workspace/project/AGENTS.md", output)
        assertEquals("internal", output.toString("UTF-8"))
        val outside = tempFolder.newFolder("outside")
        File(outside, "AGENTS.md").writeText("secret")
        java.nio.file.Files.createSymbolicLink(File(files, "escape").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            manager.exportRootfsFile(root, "/workspace/escape/AGENTS.md", ByteArrayOutputStream())
        }
        java.nio.file.Files.createSymbolicLink(File(files, "AGENTS.md").toPath(), File(outside, "AGENTS.md").toPath())
        assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/workspace/AGENTS.md")
        }
    }

    @Test
    fun createRefusesExistingRootfsFile() {
        manager = createManager()
        manager.writeRootfsText(root, "/workspace/notes.md", "one\n", overwrite = false)
        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.writeRootfsText(root, "/workspace/notes.md", "two\n", overwrite = false)
        }
        assertTrue(error.message!!.contains("already exists"))
    }
}
