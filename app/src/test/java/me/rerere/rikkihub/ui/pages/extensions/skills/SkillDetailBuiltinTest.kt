package me.rerere.rikkihub.ui.pages.extensions.skills

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkihub.data.files.SkillManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillDetailBuiltinTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun markdown(body: String) = "---\nname: creator\ndescription: description\n---\n$body"
    private data class Harness(val manager: SkillManager, val builtin: File, val vm: SkillDetailVM)
    private fun harness(user: Boolean = false): Harness {
        val root = tmp.newFolder("files")
        val builtinRoot = root.resolve("builtin_skills").also { it.mkdir() }
        val builtin = builtinRoot.resolve("packaged-dir").also {
            it.mkdir(); it.resolve("SKILL.md").writeText(markdown("builtin")); it.resolve("note.txt").writeText("builtin note")
        }
        val manager = SkillManager(root, { builtinRoot }) {}
        if (user) manager.getSkillsDir().resolve("custom-dir").also {
            it.mkdir(); it.resolve("SKILL.md").writeText(markdown("user")); it.resolve("note.txt").writeText("user note")
        }
        val vm = SkillDetailVM(manager, Dispatchers.Unconfined, Dispatchers.Unconfined)
        vm.init("creator")
        return Harness(manager, builtin, vm)
    }
    private fun file(vm: SkillDetailVM, path: String) = vm.tree.value.filterIsInstance<SkillFileNode.FileNode>()
        .first { it.skillFile.relativePath == path }.skillFile

    @Test fun `builtin detail can view files but refuses save add and delete`() {
        val h = harness()
        assertTrue(h.vm.readOnly.value)
        assertTrue(h.vm.skill.value!!.isBuiltin)
        assertEquals("builtin note", h.vm.readFile(file(h.vm, "note.txt")))
        var error: String? = null
        h.vm.saveFile("note.txt", "edit") { error = it }
        assertNotNull(error)
        h.vm.saveFile("new.txt", "new") { error = it }
        assertNotNull(error)
        var deleted = true
        h.vm.deleteFile(file(h.vm, "note.txt")) { deleted = it }
        assertFalse(deleted)
        assertFalse(h.builtin.resolve("new.txt").exists())
        assertEquals("builtin note", h.builtin.resolve("note.txt").readText())
    }
    @Test fun `user detail preserves file edit add delete and name validation`() {
        val h = harness(user = true)
        assertFalse(h.vm.readOnly.value)
        var error: String? = "pending"
        h.vm.saveFile("note.txt", "edited") { error = it }
        assertNull(error)
        assertEquals("edited", h.vm.readFile(file(h.vm, "note.txt")))
        h.vm.saveFile("new.txt", "new") { error = it }
        assertNull(error)
        var deleted = false
        h.vm.deleteFile(file(h.vm, "new.txt")) { deleted = it }
        assertTrue(deleted)
        h.vm.saveFile("SKILL.md", "---\nname: renamed\ndescription: x\n---\nbody") { error = it }
        assertNotNull(error)
        assertEquals("user", h.manager.readSkillBody("creator"))
    }
    @Test fun `stale user vm never switches to modifying revealed builtin`() {
        val h = harness(user = true)
        val oldFile = file(h.vm, "note.txt")
        h.vm.skill.value!!.skillDir.deleteRecursively()
        assertTrue(h.manager.findSkill("creator")!!.isBuiltin)
        var error: String? = null
        h.vm.saveFile("note.txt", "edited") { error = it }
        assertNotNull(error)
        assertTrue(h.vm.readOnly.value)
        var deleted = true
        h.vm.deleteFile(oldFile) { deleted = it }
        assertFalse(deleted)
        assertEquals("", h.vm.readFile(oldFile))
        h.vm.loadFiles()
        assertTrue(h.vm.tree.value.isEmpty())
        assertEquals("builtin note", h.builtin.resolve("note.txt").readText())
    }
    @Test fun `tree recursion excludes escaping links and cycles but preserves normal folders`() {
        val h = harness()
        val outside = tmp.newFolder("outside").also { it.resolve("secret.txt").writeText("secret") }
        val refs = h.builtin.resolve("references").also { it.mkdir(); it.resolve("ref.txt").writeText("ref") }
        Files.createSymbolicLink(h.builtin.resolve("external-dir").toPath(), outside.toPath())
        Files.createSymbolicLink(h.builtin.resolve("external.txt").toPath(), outside.resolve("secret.txt").toPath())
        Files.createSymbolicLink(refs.resolve("cycle").toPath(), h.builtin.toPath())
        h.vm.loadFiles()
        val dirs = h.vm.tree.value.filterIsInstance<SkillFileNode.DirNode>()
        assertEquals(listOf("references"), dirs.map { it.name })
        assertEquals(listOf("references/ref.txt"), dirs.single().children.filterIsInstance<SkillFileNode.FileNode>().map { it.skillFile.relativePath })
        assertFalse(h.vm.tree.value.filterIsInstance<SkillFileNode.FileNode>().any { it.skillFile.relativePath == "external.txt" })
    }
    @Test fun `tree refuses symlink root rather than enumerating referent`() {
        val outside = tmp.newFolder("outside").also { it.resolve("secret").writeText("secret") }
        val link = tmp.root.resolve("link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(buildSkillTree(link).isEmpty())
    }
}
