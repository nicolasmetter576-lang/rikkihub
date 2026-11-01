package me.rerere.rikkihub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillImportServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun md(name: String, description: String? = "does $name", body: String = "body") = buildString {
        append("---\nname: ").append(name).append('\n')
        if (description != null) append("description: ").append(description).append('\n')
        append("---\n").append(body)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manager(builtin: Map<String, String> = emptyMap()): SkillManager {
        val root = tmp.newFolder()
        val builtinRoot = root.resolve("builtin_skills").also { it.mkdir() }
        builtin.forEach { (dir, content) ->
            builtinRoot.resolve(dir).also { it.mkdir(); it.resolve("SKILL.md").writeText(content) }
        }
        return SkillManager(root, { builtinRoot }) {}
    }

    @Test
    fun `single SKILL md imports and missing fields are rejected`() {
        val manager = manager()
        val service = SkillImportService(manager)
        assertEquals(listOf("alpha"), service.importFile("SKILL.md", md("alpha").toByteArray()))
        assertEquals("alpha", manager.findSkill("alpha")?.name)
        listOf(md("", description = "x"), md("beta", description = null)).forEach { bad ->
            val error = assertThrows(SkillImportException::class.java) { service.importFile("x.md", bad.toByteArray()) }
            assertEquals(SkillImportException.CODE_INVALID, error.code)
        }
    }

    @Test
    fun `zip imports nested skills separately and drops escaping paths`() {
        val manager = manager()
        val bytes = zip(
            "pack/one/SKILL.md" to md("one").toByteArray(),
            "pack/one/scripts/run.sh" to "echo one".toByteArray(),
            "pack/one/two/SKILL.md" to md("two").toByteArray(),
            "pack/one/two/data.bin" to byteArrayOf(0, 1, 2),
            "../evil/SKILL.md" to md("evil").toByteArray(),
        )
        assertEquals(listOf("one", "two"), SkillImportService(manager).importFile("pack.zip", bytes).sorted())
        val one = manager.findSkill("one")!!
        assertEquals(listOf("SKILL.md", "scripts/run.sh"), manager.listSkillFiles(one)!!.map { it.path }.sorted())
        assertEquals(listOf("SKILL.md", "data.bin"), manager.listSkillFiles(manager.findSkill("two")!!)!!.map { it.path }.sorted())
        assertNull(manager.findSkill("evil"))
    }

    @Test
    fun `web limits stop file count, entry size and zip bombs before anything is saved`() {
        val manager = manager()
        val limits = SkillImportLimits(maxTotalBytes = 1000, maxFiles = 3, maxFileBytes = 600)
        val service = SkillImportService(manager)
        val cases = listOf(
            zip("a/SKILL.md" to md("a").toByteArray(), "a/1" to byteArrayOf(1), "a/2" to byteArrayOf(2), "a/3" to byteArrayOf(3)),
            zip("b/SKILL.md" to md("b").toByteArray(), "b/big" to ByteArray(601)),
            // Compresses to a few bytes; counted after decompression.
            zip("c/SKILL.md" to md("c").toByteArray(), "c/x" to ByteArray(500), "c/y" to ByteArray(500)),
        )
        cases.forEach { bytes ->
            val error = assertThrows(SkillImportException::class.java) { service.importFile("s.zip", bytes, limits) }
            assertEquals(SkillImportException.CODE_TOO_LARGE, error.code)
        }
        assertTrue(manager.listSkills().isEmpty())
    }

    @Test
    fun `without overwrite an existing user skill is a conflict, a builtin of the same name is not`() {
        val manager = manager(builtin = mapOf("packaged" to md("shipped")))
        val service = SkillImportService(manager)
        service.importFile("SKILL.md", md("mine", body = "v1").toByteArray())

        val error = assertThrows(SkillImportException::class.java) {
            service.importFile("SKILL.md", md("mine", body = "v2").toByteArray(), overwrite = false)
        }
        assertEquals(SkillImportException.CODE_EXISTS, error.code)
        assertEquals(listOf("mine"), error.names)
        assertTrue(manager.readSkillContent("mine")!!.endsWith("v1"))

        // Overriding a builtin is allowed; overwrite = true (the app's behaviour) replaces.
        service.importFile("SKILL.md", md("shipped", body = "user").toByteArray(), overwrite = false)
        assertFalse(manager.findSkill("shipped")!!.isBuiltin)
        service.importFile("SKILL.md", md("mine", body = "v3").toByteArray())
        assertTrue(manager.readSkillContent("mine")!!.endsWith("v3"))
    }

    @Test
    fun `github import lists the directory, honours limits and maps rate limiting`() {
        val listing = """[{"type":"file","path":"skills/demo/SKILL.md","download_url":"u/skill","size":40},""" +
            """{"type":"dir","path":"skills/demo/lib"}]"""
        val sub = """[{"type":"file","path":"skills/demo/lib/a.txt","download_url":"u/a","size":3}]"""
        val responses = mapOf(
            "https://api.github.com/repos/o/r/contents/skills/demo?ref=main" to listing.toByteArray(),
            "https://api.github.com/repos/o/r/contents/skills/demo/lib?ref=main" to sub.toByteArray(),
            "u/skill" to md("demo").toByteArray(),
            "u/a" to "abc".toByteArray(),
        )
        val manager = manager()
        val service = SkillImportService(manager) { url -> responses[url] }
        assertEquals("demo", service.importGitHub("https://github.com/o/r/tree/main/skills/demo"))
        assertEquals(listOf("SKILL.md", "lib/a.txt"), manager.listSkillFiles(manager.findSkill("demo")!!)!!.map { it.path }.sorted())

        val tooBig = assertThrows(SkillImportException::class.java) {
            SkillImportService(manager()) { url -> responses[url] }
                .importGitHub("https://github.com/o/r/tree/main/skills/demo", SkillImportLimits(1000, 1, 1000))
        }
        assertEquals(SkillImportException.CODE_TOO_LARGE, tooBig.code)

        val limited = assertThrows(SkillImportException::class.java) {
            SkillImportService(manager()) { url -> throw GitHubRateLimitException(url) }.importGitHub("https://github.com/o/r")
        }
        assertEquals(SkillImportException.CODE_GITHUB_RATE_LIMIT, limited.code)
        val badUrl = assertThrows(SkillImportException::class.java) { service.importGitHub("https://example.com/x") }
        assertEquals(SkillImportException.CODE_GITHUB_URL, badUrl.code)
    }

    @Test
    fun `builtin names, file listing without symlinks and copying a builtin for editing`() {
        val manager = manager(builtin = mapOf("packaged" to md("shipped")))
        assertEquals(setOf("shipped"), manager.builtinSkillNames())
        val builtin = manager.findSkill("shipped")!!
        assertTrue(builtin.isBuiltin)

        val copy = manager.copyBuiltinToUser("shipped")
        assertNotNull(copy)
        assertFalse(copy!!.isBuiltin)
        assertEquals(setOf("shipped"), manager.builtinSkillNames())
        assertNull(manager.copyBuiltinToUser("shipped")) // already a user skill now

        val dir = copy.skillDir
        val outside = tmp.newFile("secret.txt").also { it.writeText("secret") }
        Files.createSymbolicLink(dir.resolve("link.txt").toPath(), outside.toPath())
        assertEquals(listOf("SKILL.md"), manager.listSkillFiles(copy)!!.map { it.path })
    }
}
