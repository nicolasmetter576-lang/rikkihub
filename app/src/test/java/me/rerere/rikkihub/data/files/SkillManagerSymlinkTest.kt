package me.rerere.rikkihub.data.files

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 复现并钉死平台软链差异：app 进程 mount namespace 里 `/data/user/0` → `/data/data`
 * 是软链，`context.filesDir` 的字符串拼写非 canonical。此处用「真实目录 + 软链父级」
 * 模拟；修复前 `canonicalFile == absoluteFile.normalize()` 校验对主用户必炸
 * （"Unsafe user skills root"）。同时保留「拒绝软链逃逸」的安全本意。
 */
class SkillManagerSymlinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun markdown(name: String, body: String) =
        "---\nname: $name\ndescription: description\n---\n$body"

    /** (real, linkedFilesRoot)：linked = 软链父级/files，等价于 context.filesDir 拼写 */
    private fun harness(): Pair<File, File> {
        val real = tmp.newFolder("real").also { File(it, "files").mkdir() }
        val link = File(tmp.root, "link").also { Files.createSymbolicLink(it.toPath(), real.toPath()) }
        return real.resolve("files") to link.resolve("files")
    }

    private fun skill(root: File, dir: String, name: String, body: String): File =
        root.resolve(dir).also { it.mkdirs(); it.resolve("SKILL.md").writeText(markdown(name, body)) }

    private fun manager(root: File, builtin: () -> File = { File(root, "builtin_skills") }) =
        SkillManager(root, builtin) {}

    @Test
    fun `skills dir below a symlinked filesDir does not throw unsafe root`() {
        val (real, linked) = harness()
        val m = manager(linked)

        val skills = m.getSkillsDir()
        assertTrue(skills.isDirectory)
        // canonical 后落在真实目录上
        assertEquals(real.resolve("skills"), skills)
    }

    @Test
    fun `listing and reads work below a symlinked filesDir`() {
        val (real, linked) = harness()
        skill(real.resolve("skills"), "user-dir", "user", "user body")
        val m = manager(linked)

        val catalog = m.listSkills()
        assertEquals(listOf("user"), catalog.map { it.name })
        assertFalse(catalog.single().isBuiltin)
        assertEquals("user body", m.readSkillBody("user"))
        assertEquals(real.resolve("skills/user-dir"), catalog.single().skillDir)
    }

    @Test
    fun `builtin publication below a symlinked filesDir still works`() {
        val (real, linked) = harness()
        val assets = MemorySkillAssets(mapOf("creator/SKILL.md" to "body".toByteArray()))
        val published = BuiltinSkillPublisher(linked, assets).ensure("1")
        assertEquals("body", published.resolve("creator/SKILL.md").readText())
        assertEquals("1", published.resolve(".package-stamp").readText())
        // 发布落在真实目录，不经软链拼写
        assertTrue(real.resolve("builtin_skills/creator/SKILL.md").isFile)
    }

    @Test
    fun `builtin mount descriptor below a symlinked filesDir passes workspace mount checks`() {
        val (real, linked) = harness()
        val assets = MemorySkillAssets(mapOf("creator/SKILL.md" to "b".toByteArray()))
        BuiltinSkillPublisher(linked, assets).ensure("1")
        val descriptor = builtinSkillMountDescriptor(linked) { real.resolve("builtin_skills") }

        val ws = WorkspaceManager(
            baseDir = tmp.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(linked.resolve("skills").apply { mkdirs() }, "/skills"),
                WorkspaceBindMount(descriptor, "/builtin_skills", required = true),
            ),
        )
        val mounts = ws.agentBindMounts(setOf("/skills"))
        assertEquals(2, mounts.size)
        assertTrue(mounts.single { it.target == "/builtin_skills" }.source.isDirectory)
    }

    @Test
    fun `skills root being a symlink itself is still rejected`() {
        val (real, linked) = harness()
        val outside = tmp.newFolder("outside").also {
            skill(it, "evil-dir", "evil", "evil")
        }
        Files.createSymbolicLink(linked.resolve("skills").toPath(), outside.toPath())
        val m = manager(linked)

        assertThrows(IllegalArgumentException::class.java) { m.getSkillsDir() }
        assertThrows(IllegalArgumentException::class.java) { m.listSkills() }
        // 逃逸目标目录不被触碰
        assertTrue(outside.resolve("evil-dir/SKILL.md").isFile)
    }

    @Test
    fun `escaping child links below a symlinked filesDir stay rejected`() {
        val (real, linked) = harness()
        val skillsDir = real.resolve("skills").also { it.mkdirs() }
        skill(skillsDir, "good", "good", "good body")
        val outside = tmp.newFolder("outside").also {
            skill(it, "escape", "escaped", "outside")
        }
        Files.createSymbolicLink(skillsDir.resolve("linked-dir").toPath(), outside.resolve("escape").toPath())
        val m = manager(linked)

        assertEquals(listOf("good"), m.listSkills().map { it.name })
        // 技能内文件路径越界仍拒绝
        val meta = m.findSkill("good")!!
        assertNull(m.resolveSkillFile(meta, "../outside/escape/SKILL.md"))
        assertNull(SkillPaths.resolveSkillDir(skillsDir, "../outside"))
        assertNull(SkillPaths.resolveSkillDir(skillsDir, "a/b"))
    }

    @Test
    fun `deleting a user skill below a symlinked filesDir removes the real tree`() = runBlocking {
        val (real, linked) = harness()
        skill(real.resolve("skills"), "user-dir", "user", "user")
        val m = manager(linked)

        assertTrue(m.deleteSkill("user"))
        assertFalse(real.resolve("skills/user-dir").exists())
    }

    @Test
    fun `atomic save below a symlinked filesDir writes into the real tree`() {
        val (real, linked) = harness()
        val m = manager(linked)

        assertTrue(m.saveSkillFileBytesAtomically("new", mapOf("SKILL.md" to markdown("new", "body").toByteArray())))
        assertEquals("body", SkillFrontmatterParser.extractBody(real.resolve("skills/new/SKILL.md").readText()))
        assertEquals("new", m.listSkills().single().name)
    }
}
