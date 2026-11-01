package me.rerere.rikkihub.ui.pages.extensions.skills

import java.io.File
import java.util.Locale
import me.rerere.rikkihub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSearchTest {
    private val first = SkillMetadata("Review-Code", "Inspect pull requests", "Android", File("/not-read/first"))
    private val second = SkillMetadata("writer", "Create release notes", null, File("/not-read/second"))
    private val third = SkillMetadata("技能", "中文测试与审查", null, File("/not-read/third"))
    private val skills = listOf(first, second, third)

    @Test fun `empty and whitespace queries return the original catalogue`() {
        listOf("", " ", "\t\n").forEach { assertSame(skills, filterSkills(skills, it)) }
    }
    @Test fun `names are matched case insensitively`() {
        assertEquals(listOf(first), filterSkills(skills, "review-CODE"))
    }
    @Test fun `description is searchable independently of name`() {
        assertEquals(listOf(second), filterSkills(skills, "RELEASE"))
    }
    @Test fun `surrounding whitespace is not an accidental literal search term`() {
        assertEquals(listOf(first), filterSkills(skills, "  pull requests  "))
    }
    @Test fun `non Latin names and descriptions are retained literally`() {
        assertEquals(listOf(third), filterSkills(skills, "技能"))
        assertEquals(listOf(third), filterSkills(skills, "审查"))
    }
    @Test fun `filter retains original order objects paths and metadata`() {
        val latest = listOf(second, first, third)
        val result = filterSkills(latest, "re")
        assertEquals(listOf(second, first), result)
        assertSame(second, result[0]); assertSame(first, result[1])
        assertEquals(listOf(second, first, third), latest)
        assertEquals("Android", first.compatibility)
    }
    @Test fun `no matches do not mutate catalogue or search compatibility file paths`() {
        assertTrue(filterSkills(skills, "missing").isEmpty())
        assertTrue(filterSkills(skills, "Android").isEmpty())
        assertTrue(filterSkills(skills, "not-read").isEmpty())
        assertEquals(listOf(first, second, third), skills)
        assertFalse(first.skillDir.exists())
    }
    @Test fun `metadata added removed or edited is searched from the latest catalogue`() {
        assertEquals(emptyList<SkillMetadata>(), filterSkills(listOf(second), "review"))
        assertEquals(listOf(first), filterSkills(listOf(second, first), "review"))
        val edited = first.copy(description = "Updated summary")
        assertEquals(listOf(edited), filterSkills(listOf(edited), "summary"))
        assertTrue(filterSkills(listOf(edited), "pull requests").isEmpty())
    }
    @Test fun `case behavior is independent of device default Turkish locale`() {
        val prior = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf(first), filterSkills(skills, "INSPECT"))
        } finally { Locale.setDefault(prior) }
    }
    @Test fun `page only saves a bounded query while retaining metadata identity and empty states`() {
        val base = File("src/main/java/me/rerere/rikkihub").takeIf { it.isDirectory }
            ?: File("app/src/main/java/me/rerere/rikkihub")
        val page = File(base, "ui/pages/extensions/skills/SkillsPage.kt").readText()
        assertEquals(256, SKILL_SEARCH_QUERY_MAX_CHARS)
        assertTrue(page.contains("it.take(SKILL_SEARCH_QUERY_MAX_CHARS)"))
        assertTrue(page.contains("remember(skills, searchQuery) { filterSkills(skills, searchQuery) }"))
        assertTrue(page.contains("items(filteredSkills, key = { it.skillDir.absolutePath })"))
        assertTrue(page.contains("if (skills.isEmpty())"))
        assertTrue(page.contains("skills.isNotEmpty() && filteredSkills.isEmpty()"))
        assertTrue(page.contains("onClick = { navController.navigate(Screen.SkillDetail(skill.name)) }"))
        assertTrue(page.contains("onDelete = { deleteTarget = skill }"))
    }
}
