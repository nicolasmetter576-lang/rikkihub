package me.rerere.rikkihub.data.files

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillManagerInvalidDirsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files: File by lazy { tmp.newFolder("files") }
    private val manager: SkillManager by lazy { SkillManager(files, { File(files, "builtin_skills") }) {} }
    private val skillsDir: File get() = manager.getSkillsDir()

    private fun dir(name: String, skillMd: String? = null): File =
        skillsDir.resolve(name).also {
            it.mkdirs()
            if (skillMd != null) it.resolve("SKILL.md").writeText(skillMd)
        }

    private fun valid(name: String) = "---\nname: $name\ndescription: does things\n---\nbody"

    @Test
    fun `empty folder and folders with unusable SKILL md are listed with the reason`() {
        dir("empty")
        dir("no-name", "---\ndescription: d\n---\nbody")
        dir("no-description", "---\nname: n\n---\nbody")
        dir("blank-name", "---\nname:   \ndescription: d\n---\nbody")
        dir("no-frontmatter", "just text")
        // SKILL.md is a folder, not a file
        dir("md-is-dir").resolve("SKILL.md").mkdirs()

        val invalid = manager.listInvalidSkillDirs().associate { it.dirName to it.reason }

        assertEquals(
            mapOf(
                "empty" to InvalidSkillReason.MissingSkillMd,
                "md-is-dir" to InvalidSkillReason.MissingSkillMd,
                "no-name" to InvalidSkillReason.MissingName,
                "blank-name" to InvalidSkillReason.MissingName,
                "no-description" to InvalidSkillReason.MissingDescription,
                "no-frontmatter" to InvalidSkillReason.MissingName,
            ),
            invalid,
        )
    }

    @Test
    fun `a folder is either a listed skill or a listed invalid folder, never both and never neither`() {
        dir("ok", valid("ok"))
        // folder name differs from the skill name: still a skill
        dir("renamed-dir", valid("renamed"))
        dir("empty")
        dir("no-description", "---\nname: n\n---\n")

        val skillDirs = manager.listSkills().filter { !it.isBuiltin }.map { it.skillDir.name }.toSet()
        val invalidDirs = manager.listInvalidSkillDirs().map { it.dirName }.toSet()

        assertEquals(setOf("ok", "renamed-dir"), skillDirs)
        assertEquals(setOf("empty", "no-description"), invalidDirs)
        assertTrue(skillDirs.intersect(invalidDirs).isEmpty())
    }

    @Test
    fun `hidden staging folders and symlinks are not listed`() {
        dir(".skill-staging-123")
        dir(".skill-backup-456", "---\nname: x\n---\n")
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(skillsDir.resolve("linked").toPath(), outside.toPath())
        // a plain file in the skills folder is not a folder
        skillsDir.resolve("note.txt").writeText("x")

        assertTrue(manager.listInvalidSkillDirs().isEmpty())
    }

    @Test
    fun `deleting an invalid folder removes it with its contents and leaves other folders alone`() = runBlocking {
        val bad = dir("bad", "---\nname: n\n---\n")
        bad.resolve("assets").mkdirs()
        bad.resolve("assets/data.txt").writeText("data")
        dir("good", valid("good"))
        dir("other-bad")

        assertTrue(manager.deleteInvalidSkillDir("bad"))

        assertFalse(bad.exists())
        assertTrue(skillsDir.resolve("good/SKILL.md").isFile)
        assertTrue(skillsDir.resolve("other-bad").isDirectory)
        assertEquals(listOf("other-bad"), manager.listInvalidSkillDirs().map { it.dirName })
    }

    @Test
    fun `a folder that became a valid skill since the list was shown is not deleted`() = runBlocking {
        val fixed = dir("being-written")
        assertEquals(listOf("being-written"), manager.listInvalidSkillDirs().map { it.dirName })

        fixed.resolve("SKILL.md").writeText(valid("being-written"))

        assertFalse(manager.deleteInvalidSkillDir("being-written"))
        assertTrue(fixed.resolve("SKILL.md").isFile)
        assertEquals(listOf("being-written"), manager.listSkills().filter { !it.isBuiltin }.map { it.name })
    }

    @Test
    fun `only direct child folders can be deleted`() = runBlocking {
        val nested = dir("parent/child")
        val outside = tmp.newFolder("outside").also { it.resolve("keep.txt").writeText("keep") }
        val link = skillsDir.resolve("linked")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertFalse(manager.deleteInvalidSkillDir("parent/child"))
        assertFalse(manager.deleteInvalidSkillDir("../outside"))
        assertFalse(manager.deleteInvalidSkillDir(".."))
        assertFalse(manager.deleteInvalidSkillDir("linked"))
        assertFalse(manager.deleteInvalidSkillDir("missing"))

        assertTrue(nested.isDirectory)
        assertTrue(outside.resolve("keep.txt").isFile)
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun `a symlink inside an invalid folder is removed without touching its target`() = runBlocking {
        val bad = dir("bad")
        val outside = tmp.newFolder("outside").also { it.resolve("keep.txt").writeText("keep") }
        Files.createSymbolicLink(bad.resolve("escape").toPath(), outside.toPath())

        assertTrue(manager.deleteInvalidSkillDir("bad"))

        assertFalse(bad.exists())
        assertTrue(outside.resolve("keep.txt").isFile)
    }
}
