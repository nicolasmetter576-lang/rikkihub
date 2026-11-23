package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.db.entity.WorkspaceMode
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookToolPromptsTest {
    private val han = Regex("\\p{IsHan}")

    private fun workspace(book: Boolean, ready: Boolean = false, language: String = "zh", enablement: String = "{}") = WorkspaceEntity(
        id = "w", name = "w", root = "w",
        shellStatus = (if (ready) WorkspaceShellStatus.READY else WorkspaceShellStatus.DISABLED).name,
        createdAt = 1, updatedAt = 1, bookMode = book, bookPromptLanguage = language, toolEnablement = enablement,
    )

    @Test
    fun `every book mode tool has a prompt in both languages`() {
        for (id in WorkspaceToolEnablement.idsFor(WorkspaceMode.BOOK)) {
            val zh = BookToolPrompts.variant(BookPromptLanguage.ZH, id)
            assertNotNull(id, zh)
            assertTrue(id, han.containsMatchIn(zh!!.defaultDescription))
            val en = BookToolPrompts.variant(BookPromptLanguage.EN, id) ?: BuiltinToolCatalog.require(id)
            assertFalse(id, han.containsMatchIn(en.defaultDescription))
        }
        assertTrue(han.containsMatchIn(BookToolPrompts.reminder(BookPromptLanguage.ZH).defaultDescription))
        assertFalse(han.containsMatchIn(BookToolPrompts.reminder(BookPromptLanguage.EN).defaultDescription))
    }

    @Test
    fun `editing one language leaves the other and the standard mode alone`() {
        val id = BuiltinToolIds.WORKSPACE_READ_FILE
        val overrides = mapOf(BookToolPrompts.variantId(BookPromptLanguage.ZH, id) to ToolOverride(description = "我改的"))
        val tool = me.rerere.ai.core.Tool(name = id, description = "standard", execute = { emptyList() })
        assertEquals("我改的", tool.applyBuiltinOverride(overrides, emptyMap(), BookPromptLanguage.ZH).description)
        assertFalse(tool.applyBuiltinOverride(overrides, emptyMap(), BookPromptLanguage.EN).description.contains("我改的"))
        assertEquals(
            tool.applyBuiltinOverride(emptyMap(), emptyMap(), null).description,
            tool.applyBuiltinOverride(overrides, emptyMap(), null).description,
        )
    }

    @Test
    fun `parameter descriptions are fully localised`() {
        fun describe(value: JsonObject): List<Pair<String, String>> = value.entries.flatMap { (name, raw) ->
            val property = raw.jsonObject
            val own = property["description"]?.let { listOf(name to it.toString()) }.orEmpty()
            own + ((property["items"] as? JsonObject)?.get("properties") as? JsonObject)?.let(::describe).orEmpty()
        }
        val unsafe = Class.forName("sun.misc.Unsafe")
        val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val store = unsafe.getMethod("allocateInstance", Class::class.java)
            .invoke(instance, me.rerere.rikkihub.data.book.BookStore::class.java) as me.rerere.rikkihub.data.book.BookStore
        for (tool in createBookTools(workspace(book = true), { false }, store)) {
            val zh = BookToolPrompts.describeParameters(BookPromptLanguage.ZH, tool.name, tool.parameters()) as InputSchema.Obj
            val en = BookToolPrompts.describeParameters(BookPromptLanguage.EN, tool.name, tool.parameters()) as InputSchema.Obj
            describe(zh.properties).forEach { (name, text) -> assertTrue("${tool.name}.$name $text", han.containsMatchIn(text)) }
            describe(en.properties).forEach { (name, text) -> assertFalse("${tool.name}.$name $text", han.containsMatchIn(text)) }
        }
    }

    @Test
    fun `without Linux a book workspace declares everything except the shell`() {
        val ids = declaredWorkspaceToolIds(workspace(book = true))
        assertTrue(BuiltinToolIds.BOOK_READ in ids && BuiltinToolIds.WORKSPACE_WRITE_FILE in ids)
        assertFalse(BuiltinToolIds.WORKSPACE_SHELL in ids)
        assertTrue(BuiltinToolIds.WORKSPACE_SHELL in declaredWorkspaceToolIds(workspace(book = true, ready = true)))
        // 标准模式没装好 Rootfs 仍然什么都不声明
        assertTrue(declaredWorkspaceToolIds(workspace(book = false)).isEmpty())
        // 逐个关掉的工具照样不声明
        assertFalse(BuiltinToolIds.BOOK_UPDATE in declaredWorkspaceToolIds(workspace(book = true, enablement = """{"book_update":false}""")))
    }

    @Test
    fun `book tool names are only reserved in book workspaces`() {
        val standard = forbiddenWorkspaceToolNames(workspace(book = false, ready = true), emptyMap())
        assertTrue(WorkspaceToolEnablement.bookToolIds.none { it in standard })
        val off = forbiddenWorkspaceToolNames(workspace(book = true, enablement = """{"book_update":false}"""), emptyMap())
        assertTrue(BuiltinToolIds.BOOK_UPDATE in off && BuiltinToolIds.BOOK_READ !in off)
    }

    @Test
    fun `only book workspaces have a prompt language`() {
        assertNull(workspace(book = false).promptLanguage())
        assertEquals(BookPromptLanguage.EN, workspace(book = true, language = "en").promptLanguage())
        assertEquals(BookPromptLanguage.ZH, workspace(book = true, language = "??").promptLanguage())
    }
}
