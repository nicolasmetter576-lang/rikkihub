package me.rerere.rikkihub.data.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillManagerBuiltinTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun markdown(name: String, body: String) = "---\nname: $name\ndescription: description\n---\n$body"
    private class Harness(val root: File, val builtin: File) {
        var enabled = setOf("creator", "user", "ghost")
        val manager = SkillManager(root, { builtin }) { names -> enabled = enabled.filterTo(linkedSetOf()) { it in names } }
    }
    private fun harness(): Harness {
        val root = tmp.newFolder("files")
        return Harness(root, root.resolve("builtin_skills").also { it.mkdir() })
    }
    private fun skill(root: File, dir: String, name: String, body: String): File =
        root.resolve(dir).also { it.mkdirs(); it.resolve("SKILL.md").writeText(markdown(name, body)) }

    @Test fun `builtin metadata defaults to marked origin while existing metadata defaults user`() {
        val h = harness()
        skill(h.builtin, "packaged-dir", "creator", "builtin")
        val selected = h.manager.listSkills().single()
        assertTrue(selected.isBuiltin)
        assertEquals("packaged-dir", selected.skillDir.name)
        assertFalse(SkillMetadata("x", "d", skillDir = h.root).isBuiltin)
    }
    @Test fun `effective reads use metadata name and actual origin directory`() {
        val h = harness()
        val dir = skill(h.builtin, "packaged-directory", "creator", "builtin body")
        dir.resolve("reference.txt").writeText("reference")
        assertEquals("builtin body", h.manager.readSkillBody("creator"))
        assertEquals(dir, h.manager.getSkillDir("creator"))
        assertEquals("reference", h.manager.resolveSkillFile("creator", "reference.txt")!!.readText())
        assertNull(h.manager.readSkillBody("packaged-directory"))
    }
    @Test fun `user name shadows builtin even with different physical directory`() {
        val h = harness()
        skill(h.builtin, "packaged-dir", "creator", "builtin")
        val user = skill(h.manager.getSkillsDir(), "custom-dir", "creator", "user body")
        val catalog = h.manager.listSkills()
        assertEquals(1, catalog.size)
        assertFalse(catalog.single().isBuiltin)
        assertEquals(user, catalog.single().skillDir)
        assertEquals("user body", h.manager.readSkillBody("creator"))
    }
    @Test fun `deleting user override reveals builtin and retains enabled name`() = runBlocking {
        val h = harness()
        skill(h.builtin, "packaged-dir", "creator", "builtin")
        skill(h.manager.getSkillsDir(), "custom-dir", "creator", "user")
        assertTrue(h.manager.deleteSkill("creator", h.manager.findSkill("creator")))
        assertEquals(setOf("creator"), h.enabled)
        assertTrue(h.manager.findSkill("creator")!!.isBuiltin)
        assertEquals("builtin", h.manager.readSkillBody("creator"))
    }
    @Test fun `deleting ordinary user removes enabled name but never enables others`() = runBlocking {
        val h = harness()
        skill(h.builtin, "packaged-dir", "new-disabled", "builtin")
        skill(h.manager.getSkillsDir(), "user-dir", "user", "user")
        assertTrue(h.manager.deleteSkill("user"))
        assertEquals(emptySet<String>(), h.enabled)
        assertTrue(h.manager.findSkill("new-disabled")!!.isBuiltin)
    }
    @Test fun `pruning uses merged available names not only user directories`() = runBlocking {
        val h = harness()
        skill(h.builtin, "packaged-dir", "creator", "builtin")
        skill(h.manager.getSkillsDir(), "user-dir", "user", "user")
        assertEquals(2, h.manager.pruneOrphanedEnabledSkills().size)
        assertEquals(setOf("creator", "user"), h.enabled)
    }
    @Test fun `builtin management mutations are refused`() = runBlocking {
        val h = harness()
        val dir = skill(h.builtin, "packaged-dir", "creator", "builtin")
        dir.resolve("note.txt").writeText("original")
        val selected = h.manager.findSkill("creator")!!
        assertFalse(h.manager.canModify(selected))
        assertFalse(h.manager.saveSkillFile("creator", "note.txt", "edited", selected))
        assertFalse(h.manager.deleteSkillFile("creator", "note.txt", selected))
        assertFalse(h.manager.deleteSkill("creator", selected))
        assertEquals("original", dir.resolve("note.txt").readText())
    }
    @Test fun `stale user selection cannot mutate builtin revealed later`() = runBlocking {
        val h = harness()
        val builtin = skill(h.builtin, "packaged-dir", "creator", "builtin")
        builtin.resolve("note.txt").writeText("builtin note")
        val user = skill(h.manager.getSkillsDir(), "custom-dir", "creator", "user")
        val stale = h.manager.findSkill("creator")!!
        user.deleteRecursively()
        assertFalse(h.manager.isCurrentSelection(stale))
        assertFalse(h.manager.saveSkillFile("creator", "note.txt", "edited", stale))
        assertFalse(h.manager.deleteSkillFile("creator", "note.txt", stale))
        assertFalse(h.manager.deleteSkill("creator", stale))
        assertNull(h.manager.resolveSkillFile(stale, "note.txt"))
        assertEquals("builtin note", builtin.resolve("note.txt").readText())
    }
    @Test fun `stale selection cannot delete a different user directory with same name`() = runBlocking {
        val h = harness()
        val old = skill(h.manager.getSkillsDir(), "old-dir", "user", "old")
        val stale = h.manager.findSkill("user")!!
        old.deleteRecursively()
        val current = skill(h.manager.getSkillsDir(), "new-dir", "user", "new")
        assertFalse(h.manager.deleteSkill("user", stale))
        assertTrue(current.exists())
    }
    @Test fun `explicit import creates user override without modifying builtin or enabled settings`() {
        val h = harness()
        val builtin = skill(h.builtin, "packaged-dir", "creator", "builtin")
        val previous = h.enabled
        val bytes = byteArrayOf(0, 0xff.toByte(), 0x80.toByte())
        assertTrue(h.manager.saveSkillFileBytesAtomically("creator", mapOf(
            "SKILL.md" to markdown("creator", "imported").toByteArray(), "asset.bin" to bytes,
        )))
        assertFalse(h.manager.findSkill("creator")!!.isBuiltin)
        assertArrayEquals(bytes, h.manager.resolveSkillFile("creator", "asset.bin")!!.readBytes())
        assertEquals("builtin", SkillFrontmatterParser.extractBody(builtin.resolve("SKILL.md").readText()))
        assertEquals(previous, h.enabled)
    }
    @Test fun `atomic single file editing keeps actual user directory and concurrent complete writes`() = runBlocking {
        val h = harness()
        val dir = skill(h.manager.getSkillsDir(), "actual-dir", "user", "old")
        val selection = h.manager.findSkill("user")!!
        val a = "a".repeat(8192)
        val b = "b".repeat(8192)
        // Existing directory isolates atomic replacement from unrelated mkdir races.
        check(dir.resolve("notes").mkdir())
        val results = listOf(async(Dispatchers.IO) { h.manager.saveSkillFile("user", "notes/n.txt", a, selection) },
            async(Dispatchers.IO) { h.manager.saveSkillFile("user", "notes/n.txt", b, selection) }).map { it.await() }
        assertTrue(results.all { it })
        assertTrue(dir.resolve("notes/n.txt").readText() in setOf(a, b))
        assertEquals(listOf("n.txt"), dir.resolve("notes").list()!!.toList())
        assertFalse(h.manager.getSkillsDir().resolve("user").exists())
    }
    @Test fun `unsafe single file edits refuse escaping path without damaging old content`() = runBlocking {
        val h = harness()
        val dir = skill(h.manager.getSkillsDir(), "actual-dir", "user", "old")
        val outside = tmp.newFile("outside").also { it.writeText("safe") }
        Files.createSymbolicLink(dir.resolve("link.txt").toPath(), outside.toPath())
        assertFalse(h.manager.saveSkillFile("user", "link.txt", "bad"))
        assertFalse(h.manager.saveSkillFile("user", "../outside", "bad"))
        assertEquals("safe", outside.readText())
        assertEquals("old", h.manager.readSkillBody("user"))
    }
    @Test fun `effective catalog skips hidden staging escaping links and escaped skill md`() {
        val h = harness()
        skill(h.builtin, "normal", "creator", "builtin")
        skill(h.builtin, ".hidden", "hidden", "hidden")
        val outside = tmp.newFolder("outside")
        val escaped = skill(outside, "escape", "escaped", "outside")
        Files.createSymbolicLink(h.builtin.resolve("linked-dir").toPath(), escaped.toPath())
        val linkedMd = h.manager.getSkillsDir().resolve("linked-md").also { it.mkdir() }
        Files.createSymbolicLink(linkedMd.resolve("SKILL.md").toPath(), escaped.resolve("SKILL.md").toPath())
        assertEquals(listOf("creator"), h.manager.listSkills().map { it.name })
    }
    @Test fun `builtin symlink root cannot leak outside metadata`() {
        val h = harness()
        h.builtin.delete()
        val outside = tmp.newFolder("outside")
        skill(outside, "external", "secret", "secret")
        Files.createSymbolicLink(h.builtin.toPath(), outside.toPath())
        assertTrue(h.manager.listSkills().isEmpty())
    }
    @Test fun `cancelled lazy ensure propagates instead of selecting an old catalog`() {
        val h = harness()
        skill(h.builtin, "packaged-dir", "creator", "builtin")
        val manager = SkillManager(h.root, { throw CancellationException("cancelled") }) {}
        assertThrows(CancellationException::class.java) { manager.listSkills() }
        assertEquals("builtin", SkillFrontmatterParser.extractBody(h.builtin.resolve("packaged-dir/SKILL.md").readText()))
    }
    @Test fun `failed lazy ensure is retried and existing catalog remains usable`() {
        val h = harness()
        skill(h.builtin, "packaged-dir", "creator", "builtin")
        var calls = 0
        val manager = SkillManager(h.root, {
            calls++
            if (calls == 1) throw IOException("publication failed")
            h.builtin
        }) {}
        assertEquals("builtin", manager.readSkillBody("creator"))
        assertTrue(calls > 1)
        assertEquals("builtin", manager.readSkillBody("creator"))
    }
}
