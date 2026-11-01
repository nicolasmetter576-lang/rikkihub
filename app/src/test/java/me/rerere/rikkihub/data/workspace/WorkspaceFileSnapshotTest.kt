package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceFileSnapshotTest {
    private fun entry(name: String, dir: Boolean = false, size: Long = 0) =
        WorkspaceFileEntry(path = name, name = name, isDirectory = dir, sizeBytes = size, updatedAt = 0)

    @Test fun `listing looks like ls with directories marked and sizes for files`() {
        val text = formatWorkspaceFileSnapshot("/workspace/app", listOf(entry("src", dir = true), entry("README.md", size = 2048)))
        val lines = text.lines()
        assertEquals("<workspace_files path=\"/workspace/app\">", lines.first())
        assertEquals("</workspace_files>", lines.last())
        assertTrue(text, "src/" in lines)
        assertTrue(text, lines.any { it.startsWith("README.md  ") })
    }

    @Test fun `empty and oversized directories`() {
        assertTrue(formatWorkspaceFileSnapshot("/workspace", emptyList()).contains("\n(empty)\n"))
        val many = (1..5).map { entry("f$it") }
        val capped = formatWorkspaceFileSnapshot("/workspace", many, maxEntries = 3)
        assertTrue(capped, capped.contains("\nf3  ") && !capped.contains("\nf4  ") && capped.contains("… and 2 more"))
    }

    private fun node(path: String, dir: Boolean = false, size: Long = 0) =
        WorkspaceFileEntry(path = path, name = path.substringAfterLast('/'), isDirectory = dir, sizeBytes = size, updatedAt = 0)

    @Test fun `depth one is byte-identical to the original single-level listing`() {
        val entries = listOf(entry("src", dir = true), entry("README.md", size = 2048))
        assertEquals(
            formatWorkspaceFileSnapshot("/workspace", entries),
            formatWorkspaceFileTree("/workspace", 1, entries) { error("must not list children at depth 1") },
        )
    }

    @Test fun `deeper listings indent children and stop at the requested depth`() {
        val tree = mapOf(
            "src" to listOf(node("src/main", dir = true), node("src/a.kt", size = 10)),
            "src/main" to listOf(node("src/main/deep.kt", size = 1)),
        )
        val text = formatWorkspaceFileTree("/workspace", 2, listOf(node("src", dir = true), node("b.md", size = 5))) { tree[it.path] }
        assertEquals(
            listOf(
                "<workspace_files path=\"/workspace\" depth=\"2\">",
                "Listing of /workspace, 2 levels deep, taken when this conversation started (like `ls -R`; it is not refreshed):",
                "src/",
                "  main/",
                "  a.kt  10 B",
                "b.md  5 B",
                "</workspace_files>",
            ),
            text.lines(),
        )
        val three = formatWorkspaceFileTree("/workspace", 3, listOf(node("src", dir = true))) { tree[it.path] }
        assertTrue(three, three.contains("\n    deep.kt  1 B\n"))
    }

    @Test fun `breadth first budget keeps upper levels complete and counts what was cut`() {
        val children = (1..10).map { node("big/f$it") }
        val roots = listOf(node("big", dir = true)) + (1..3).map { node("r$it") }
        val text = formatWorkspaceFileTree("/workspace", 2, roots, maxEntries = 6) { if (it.path == "big") children else null }
        val lines = text.lines()
        // all four top-level entries first, then only two of big's ten children
        assertTrue(text, listOf("r1  0 B", "r2  0 B", "r3  0 B").all { it in lines })
        assertTrue(text, "  f1  0 B" in lines && "  f2  0 B" in lines && "  f3  0 B" !in lines)
        assertTrue(text, "  … and 8 more" in lines)
    }

    @Test fun `git and node_modules are named but not expanded, unreadable dirs are just listed`() {
        val text = formatWorkspaceFileTree("/workspace", 3, listOf(node(".git", dir = true), node("node_modules", dir = true), node("locked", dir = true))) {
            if (it.path == "locked") null else error("should not expand ${it.path}")
        }
        val lines = text.lines()
        assertTrue(text, ".git/  (not expanded)" in lines && "node_modules/  (not expanded)" in lines && "locked/" in lines)
    }

    @Test fun `snapshot is stored on the first part that can carry metadata, keeping existing metadata`() {
        val parts = listOf(
            UIMessagePart.Text("hi", metadata = buildJsonObject { put("k", JsonPrimitive("v")) }),
            UIMessagePart.Text("second"),
        )
        val tagged = parts.withWorkspaceFileSnapshot("LIST")
        assertEquals("LIST", tagged[0].workspaceFileSnapshot)
        assertEquals("v", (tagged[0].metadata!!["k"] as JsonPrimitive).content)
        assertNull(tagged[1].workspaceFileSnapshot)
        assertEquals("hi", (tagged[0] as UIMessagePart.Text).text)
        val noCarrier = listOf<UIMessagePart>()
        assertSame(noCarrier, noCarrier.withWorkspaceFileSnapshot("LIST"))
    }

    @Test fun `transformer appends the frozen listing to that user message only`() = runBlocking {
        val ctx = TransformerContext(context = FakeContext(File(".")), model = Model(), assistant = Assistant(), settings = Settings())
        val first = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")).withWorkspaceFileSnapshot("LIST"))
        val second = UIMessage.user("again")
        val out = WorkspaceFileSnapshotTransformer.transform(ctx, listOf(first, UIMessage.assistant("ok"), second))
        assertEquals(listOf("hi", "\n\nLIST"), out[0].parts.map { (it as UIMessagePart.Text).text })
        assertSame(out[1], out[1])
        assertEquals(second, out[2])
        // stable across requests: same input → same output (prompt cache)
        assertEquals(out, WorkspaceFileSnapshotTransformer.transform(ctx, listOf(first, UIMessage.assistant("ok"), second)).let {
            listOf(it[0], out[1], it[2])
        })
    }
}
