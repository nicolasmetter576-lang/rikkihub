package me.rerere.rikkihub.data.ai.prompts

import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {
    private val profile = MemoryFile(MemoryFile.PROFILE_PATH, "- [stated] Name is Lin\n", updatedAt = 0)
    private val food = MemoryFile(
        path = "/topics/food.md",
        content = """
            ---
            name: food
            description: What the user eats and avoids
            aliases: [diet, 饮食]
            ---

            - [stated] Allergic to peanuts
        """.trimIndent(),
        updatedAt = 0,
    )

    private fun String.block(tag: String) = substringAfter("<$tag").substringAfter(">").substringBefore("</$tag>").trim()

    @Test
    fun `profile is injected in full with its version and other files only appear in the listing`() {
        val prompt = buildMemoryPrompt(listOf(profile, food))

        assertTrue(prompt.contains("<profile version=\"${profile.version}\">"))
        assertEquals("- [stated] Name is Lin", prompt.block("profile"))

        val listing = prompt.block("memory_listing")
        assertTrue(listing.startsWith("/topics/food.md | What the user eats and avoids | aliases: diet, 饮食 | updated: "))
        assertFalse(listing.contains(MemoryFile.PROFILE_PATH))
        assertFalse(prompt.contains("Allergic to peanuts"))
    }

    @Test
    fun `missing files are shown as empty and creatable`() {
        val prompt = buildMemoryPrompt(emptyList())

        assertTrue(prompt.contains("<preferences version=\"new\">"))
        assertEquals("(empty)", prompt.block("preferences"))
        assertEquals("(no files yet)", prompt.block("memory_listing"))
    }

    @Test
    fun `consolidation input carries the memory state and each conversation's new turns`() {
        val input = buildMemoryConsolidationInput(
            files = listOf(profile, food),
            conversations = listOf(
                MemoryConsolidationConversation(
                    id = "c1",
                    title = "Moving <to> \"Hangzhou\"",
                    recentContext = "",
                    newTurns = "[USER #4]: I moved to Hangzhou",
                ),
            ),
        )

        assertEquals("- [stated] Name is Lin", input.block("profile"))
        assertTrue(input.block("memory_listing").startsWith("/topics/food.md"))
        // 标题里的引号和尖括号不能破坏标签
        assertTrue(input.contains("<conversation id=\"c1\" title=\"Moving  to   Hangzhou\">"))
        assertEquals("(none)", input.block("recent_context"))
        assertEquals("[USER #4]: I moved to Hangzhou", input.block("new_turns"))
    }

    @Test
    fun `consolidation input says so when there is nothing new`() {
        val input = buildMemoryConsolidationInput(files = listOf(profile), conversations = emptyList())

        assertTrue(input.endsWith("(no new conversation turns)"))
        assertFalse(input.contains("<conversation"))
    }

    @Test
    fun `whole-store tidying is only asked for in a full review`() {
        assertFalse(buildMemoryConsolidationPrompt(fullReview = false).contains("## Full review"))
        assertTrue(buildMemoryConsolidationPrompt(fullReview = true).contains("## Full review"))
    }

    @Test
    fun `a custom dreaming prompt keeps the shared format rules where the placeholder is`() {
        val prompt = buildMemoryConsolidationPrompt(
            fullReview = true,
            dreamPrompt = "Be brief.\n\n$MEMORY_FORMAT_PLACEHOLDER\n\nThe end.",
            fullReviewPrompt = "Tidy everything.",
        )

        assertTrue(prompt.startsWith("Be brief."))
        assertTrue(prompt.contains("<memory_format>"))
        assertFalse(prompt.contains(MEMORY_FORMAT_PLACEHOLDER))
        assertTrue(prompt.endsWith("The end.\n\nTidy everything."))
        // 不是手动整理时不追加
        assertFalse(buildMemoryConsolidationPrompt(false, "Be brief.", "Tidy everything.").contains("Tidy"))
        // 默认提示词展开后和原来一样带着格式规则
        assertTrue(buildMemoryConsolidationPrompt(false).contains("<memory_format>"))
        assertFalse(DEFAULT_MEMORY_DREAM_PROMPT.contains("<memory_format>"))
    }

    @Test
    fun `renamed memory tools are named by their new names in the rules`() {
        val prompt = buildMemoryPrompt(
            files = emptyList(),
            toolOverrides = mapOf(BuiltinToolIds.MEMORY_READ to ToolOverride(name = "recall")),
        )

        assertTrue(prompt.contains("call recall before answering"))
        assertFalse(prompt.contains("memory_read"))
        // 标签名不是工具名，不跟着换
        assertTrue(prompt.contains("memory_listing"))
        assertTrue(prompt.contains("memory_str_replace"))
    }
}
