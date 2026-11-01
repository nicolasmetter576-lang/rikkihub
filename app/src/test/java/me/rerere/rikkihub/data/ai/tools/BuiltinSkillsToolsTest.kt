package me.rerere.rikkihub.data.ai.tools

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import me.rerere.rikkihub.data.files.SkillMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinSkillsToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun metadata(directory: String, body: String, builtin: Boolean) =
        SkillMetadata("creator", "description", skillDir = tmp.newFolder(directory).also {
            it.resolve("SKILL.md").writeText("---\nname: creator\ndescription: description\n---\n$body")
        }, isBuiltin = builtin)
    private fun arguments(path: String? = null) = buildJsonObject { put("name", "creator"); path?.let { put("path", it) } }

    @Test fun `effective tool prefers user metadata independent of input order`() = runBlocking {
        val builtin = metadata("packaged", "builtin", true)
        val user = metadata("custom", "user", false)
        val tool = createSkillTools(setOf("creator"), listOf(builtin, user)).single()
        assertEquals("user", (tool.execute(arguments()).single() as UIMessagePart.Text).text)
    }
    @Test fun `builtin tool reads supporting text from actual packaged directory`() = runBlocking {
        val builtin = metadata("packaged", "builtin", true)
        builtin.skillDir.resolve("reference.txt").writeText("reference")
        val tool = createSkillTools(setOf("creator"), listOf(builtin)).single()
        assertEquals("builtin", (tool.execute(arguments()).single() as UIMessagePart.Text).text)
        assertEquals("reference", (tool.execute(arguments("reference.txt")).single() as UIMessagePart.Text).text)
    }
    @Test fun `tool refuses escaping default and supporting symlinks`() = runBlocking {
        val builtin = metadata("packaged", "builtin", true)
        val outside = tmp.newFile("outside").also { it.writeText("secret") }
        val tool = createSkillTools(setOf("creator"), listOf(builtin)).single()
        Files.createSymbolicLink(builtin.skillDir.resolve("link.txt").toPath(), outside.toPath())
        assertTrue(runCatching { tool.execute(arguments("link.txt")) }.isFailure)
        builtin.skillFile.delete()
        Files.createSymbolicLink(builtin.skillFile.toPath(), outside.toPath())
        assertTrue(runCatching { tool.execute(arguments()) }.isFailure)
    }
    @Test fun `location prompt uses actual directory and only exact exposed roots`() {
        val builtin = metadata("packaged-dir", "builtin", true)
        val visible = createSkillTools(setOf("creator"), listOf(builtin), setOf("/skills", "/builtin_skills"))
            .single().systemPrompt(Model(), emptyList())
        assertTrue(visible.contains("/builtin_skills/packaged-dir/SKILL.md"))
        assertFalse(visible.contains("/builtin_skills/creator/SKILL.md"))
        assertFalse(visible.contains(tmp.root.absolutePath))
        assertTrue(visible.contains("management policy, not an OS restriction"))
        val hidden = createSkillTools(setOf("creator"), listOf(builtin), setOf("/skills"))
            .single().systemPrompt(Model(), emptyList())
        assertFalse(hidden.contains("/builtin_skills"))
        assertFalse(hidden.contains("READ-ONLY"))
    }
    @Test fun `effective location xml escapes raw names and directories while overrides work`() {
        val dir = tmp.newFolder("x<y&z")
        val skill = SkillMetadata("a&b", "x".repeat(2048), skillDir = dir, isBuiltin = true)
        val xml = formatEffectiveSkillsXml(listOf(skill), setOf("/builtin_skills"))
        assertTrue(xml.contains("<name>a&amp;b</name>"))
        assertTrue(xml.contains("/builtin_skills/x&lt;y&amp;z/SKILL.md"))
        assertEquals(1024, xml.substringAfter("<description>").substringBefore("</description>").length)
        val overridden = createSkillTools(setOf("a&b"), listOf(skill), setOf("/builtin_skills")).single()
            .applyBuiltinOverride(mapOf(BuiltinToolIds.USE_SKILL to ToolOverride(name = "load", systemPrompt = "{{skills}}")),
                mapOf("skills" to xml))
        assertEquals("load", overridden.name)
        assertTrue(overridden.systemPrompt(Model(), emptyList()).contains("x&lt;y&amp;z"))
    }
    @Test fun `packaged creator parses and describes actual fork capabilities without identity or install api`() {
        val asset = sequenceOf(File("app/src/main/assets/builtin_skills/skill-creator/SKILL.md"),
            File("src/main/assets/builtin_skills/skill-creator/SKILL.md")).first { it.isFile }
        val content = asset.readText()
        val frontmatter = SkillFrontmatterParser.parse(content)
        assertEquals("skill-creator", frontmatter["name"])
        val description = requireNotNull(frontmatter["description"])
        assertTrue(description.isNotBlank())
        assertTrue(description.length <= MAX_SKILL_DESCRIPTION_LENGTH)
        assertTrue(content.contains("GNU AGPL-3.0"))
        assertTrue(content.contains("2267943a"))
        assertTrue(content.contains("str_replace_editor"))
        assertTrue(content.contains("workspace_write_file"))
        assertTrue(content.contains("not an OS or PRoot restriction"))
        assertTrue(content.contains("never enables it automatically"))
        assertFalse(content.contains("rikkahub://"))
        // Use the actual asset body in the real reader, not just a source-text guard.
        val directory = tmp.newFolder("creator-asset")
        directory.resolve("SKILL.md").writeText(content)
        val tool = createSkillTools(setOf("skill-creator"), listOf(
            SkillMetadata("skill-creator", description, skillDir = directory, isBuiltin = true),
        )).single()
        runBlocking {
            val result = tool.execute(buildJsonObject { put("name", "skill-creator") })
            assertEquals(SkillFrontmatterParser.extractBody(content), (result.single() as UIMessagePart.Text).text)
        }
    }
}
