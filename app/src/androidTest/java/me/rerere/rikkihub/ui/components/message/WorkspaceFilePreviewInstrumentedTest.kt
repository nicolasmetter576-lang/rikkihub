package me.rerere.rikkihub.ui.components.message

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.RouteActivity
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class WorkspaceFilePreviewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val settings get() = GlobalContext.get().get<SettingsStore>()
    private val workspaces get() = GlobalContext.get().get<WorkspaceRepository>()
    private val conversations get() = GlobalContext.get().get<ConversationRepository>()
    private lateinit var scenario: ActivityScenario<RouteActivity>
    private lateinit var workspaceId: String
    private lateinit var file: File
    private lateinit var conversation: Conversation
    private var previousAssistantId = Uuid.NIL
    private val assistantId = Uuid.random()
    private val text = "alpha beta gamma delta\n" +
        (1..250).joinToString("\n") { "Line $it: 中文范围复制, punctuation & <source>." } +
        "\nEND_OF_PREVIEW"

    @Before
    fun prepareConversation() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        runBlocking {
            val workspace = workspaces.create("preview-itest-$assistantId")
            workspaceId = workspace.id
            val files = File(context.filesDir.canonicalFile, "workspaces/${workspace.root}/files")
            file = File(files, "notes/preview.txt").apply { parentFile!!.mkdirs(); writeText(text) }
            File(files, "other/private.txt").apply { parentFile!!.mkdirs(); writeText("OUTSIDE_SCOPE_SECRET") }
            val previous = settings.settingsSnapshot()
            previousAssistantId = previous.assistantId
            val assistant = previous.assistants.first { it.id == previousAssistantId }.copy(
                id = assistantId, name = "File preview test", workspaceId = Uuid.parse(workspace.id),
            )
            settings.update { it.copy(assistantId = assistantId, assistants = it.assistants + assistant) }
            val parts = listOf("notes/preview.txt", "other/private.txt").map { path ->
                UIMessagePart.Tool(
                    toolCallId = Uuid.random().toString(),
                    toolName = "workspace_write_file",
                    input = buildJsonObject { put("path", "/workspace/$path"); put("text", "recorded write") }.toString(),
                    output = listOf(UIMessagePart.Text("""{"ok":true}""")),
                )
            } + UIMessagePart.Text("Preview fixture ready")
            conversation = Conversation(
                assistantId = assistantId, title = "1107 file preview",
                workspaceCwd = "/workspace/notes", workspaceCwdScoped = true,
                messageNodes = listOf(MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = parts))),
            )
            conversations.insertConversation(conversation)
        }
        scenario = ActivityScenario.launch(
            Intent(context, RouteActivity::class.java).putExtra("conversationId", conversation.id.toString()),
        )
        waitFor { find { it.text?.toString() == "preview.txt" } != null }
    }

    @After
    fun cleanUp() {
        if (::scenario.isInitialized) scenario.close()
        runBlocking {
            settings.update {
                it.copy(assistantId = previousAssistantId, assistants = it.assistants.filterNot { a -> a.id == assistantId })
            }
            if (::conversation.isInitialized) conversations.deleteConversation(conversation)
            if (::workspaceId.isInitialized) workspaces.delete(workspaceId)
        }
    }

    @Test
    fun fullTextIsReadOnlyAndStableUntilReopened() {
        openPreview()
        assertEquals(text, field().text.toString())
        assertFalse(field().isEditable)
        val replacement = "Updated after opening\n中文与 emoji 🌊\nNEW_END"
        file.writeText(replacement)
        assertEquals("ongoing model writes must not reset a reader's selection", text, field().text.toString())
        click(node { it.viewIdResourceName == "workspace-file-preview-close" })
        openPreview()
        assertEquals(replacement, field().text.toString())
        assertEquals(replacement, file.readText())
        screenshot("full-text")
    }

    @Test
    fun longPressDragCopiesARangeAndSelectAllCopiesTheWholeFile() {
        openPreview()
        val bounds = bounds(field())
        gesture(bounds.left + 25f, bounds.top + 20f, bounds.left + 220f, bounds.top + 55f, hold = 650)
        waitFor { field().textSelectionStart != field().textSelectionEnd }
        val selected = field()
        val from = minOf(selected.textSelectionStart, selected.textSelectionEnd)
        val to = maxOf(selected.textSelectionStart, selected.textSelectionEnd)
        assertTrue("a drag selects a proper range", from >= 0 && to > from && to < text.length)
        assertTrue(preview() != null)
        screenshot("range-selected")
        click(node { it.text?.toString()?.lowercase() in setOf("copy", "复制") })
        assertEquals(text.substring(from, to), clipboard())

        gesture(bounds.left + 25f, bounds.top + 20f, bounds.left + 25f, bounds.top + 20f, hold = 650)
        click(node { it.text?.toString()?.lowercase() in setOf("select all", "全选") })
        waitFor { kotlin.math.abs(field().textSelectionEnd - field().textSelectionStart) == text.length }
        screenshot("select-all")
        click(node { it.text?.toString()?.lowercase() in setOf("copy", "复制") })
        assertEquals(text, clipboard())
        assertEquals(text, file.readText())
    }

    @Test
    fun scrollingDoesNotCloseThePreviewButDoubleTapDoes() {
        openPreview()
        val bounds = bounds(field())
        gesture(bounds.centerX().toFloat(), bounds.bottom - 80f, bounds.centerX().toFloat(), bounds.top + 80f)
        screenshot("scrolled")
        gesture(bounds.left + 25f, bounds.top + 20f, bounds.left + 25f, bounds.top + 20f, hold = 650)
        waitFor { minOf(field().textSelectionStart, field().textSelectionEnd) > text.indexOf("Line 5:") }
        assertTrue(preview() != null)
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        SystemClock.sleep(500)
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        SystemClock.sleep(80)
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        waitFor { preview() == null }
    }

    @Test
    fun keyboardAndMultilineInputNeverCoverThePreview() {
        openPreview()
        val before = bounds(preview()!!).height()
        val input = node { it.isEditable && it.viewIdResourceName != "workspace-file-preview-text" }
        assertTrue(bounds(preview()!!).bottom <= bounds(input).top)
        click(input)
        waitFor { bounds(preview()!!).height() < before - 100 }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "one\ntwo\nthree\nfour")
        }
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        SystemClock.sleep(500)
        val currentInput = node { it.isEditable && it.viewIdResourceName != "workspace-file-preview-text" }
        assertTrue(bounds(preview()!!).bottom <= bounds(currentInput).top)
        screenshot("keyboard-multiline")
        assertEquals(text, file.readText())
    }

    @Test
    fun missingFileCanBeRetriedAndChangingWorkspaceClosesThePreview() {
        file.delete()
        click(node { it.text?.toString() == "preview.txt" })
        node { it.text?.toString() in setOf("Retry", "重试") }
        assertTrue(find { it.viewIdResourceName == "workspace-file-preview-text" } == null)
        file.writeText("Recovered file")
        click(node { it.text?.toString() in setOf("Retry", "重试") })
        assertEquals("Recovered file", field().text.toString())
        runBlocking {
            settings.update { s -> s.copy(assistants = s.assistants.map {
                if (it.id == assistantId) it.copy(workspaceId = null) else it
            }) }
        }
        waitFor { preview() == null }
    }

    @Test
    fun fileOutsideTheConversationScopeIsNotDisplayed() {
        click(node { it.text?.toString() == "private.txt" })
        node { it.text?.toString() in setOf("Retry", "重试") }
        assertTrue(find { it.viewIdResourceName == "workspace-file-preview-text" } == null)
        assertTrue(find { it.text?.contains("OUTSIDE_SCOPE_SECRET") == true } == null)
        screenshot("scope-refused")
    }

    @Test
    fun emptyFileIsNotMistakenForALoadFailureAndOversizedFileIsNotTruncated() {
        file.writeText("")
        openPreview()
        assertEquals("", field().text.toString())
        click(node { it.viewIdResourceName == "workspace-file-preview-close" })
        file.outputStream().use { output ->
            repeat(8) { output.write(ByteArray(1024 * 1024) { 'a'.code.toByte() }) }
            output.write('x'.code)
        }
        click(node { it.text?.toString() == "preview.txt" })
        node { it.text?.toString() in setOf("Retry", "重试") }
        assertTrue(find { it.viewIdResourceName == "workspace-file-preview-text" } == null)
        assertEquals(8L * 1024 * 1024 + 1, file.length())
    }

    @Test
    fun landscapePreviewFitsTheChatPaneAndKeepsExportAndShare() {
        openPreview()
        try {
            scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitFor { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            val input = node { it.isEditable && it.viewIdResourceName != "workspace-file-preview-text" }
            assertTrue(bounds(preview()!!).bottom <= bounds(input).top)
            assertEquals(text, field().text.toString())
            screenshot("landscape")
            click(node { it.contentDescription?.toString() in setOf("More options", "更多选项") })
            node { it.text?.toString() in setOf("Export", "导出") }
            node { it.text?.toString() in setOf("Share", "分享") }
        } finally {
            scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun openPreview() {
        click(node { it.text?.toString() == "preview.txt" })
        node { it.viewIdResourceName == "workspace-file-preview-text" }
    }

    private fun preview() = find { it.viewIdResourceName == "workspace-file-preview" }
    private fun field() = node { it.viewIdResourceName == "workspace-file-preview-text" }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            current.refresh()
            if (predicate(current)) return current
            for (i in 0 until current.childCount) current.getChild(i)?.let { child ->
                visit(child)?.let { return it }
            }
            return null
        }
        for (window in automation.windows) window.root?.let { root ->
            visit(root)?.let { return it }
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        waitFor { find(predicate) != null }
        return find(predicate)!!
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun click(node: AccessibilityNodeInfo) {
        val bounds = bounds(node)
        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        instrumentation.waitForIdleSync()
    }

    private fun tap(x: Float, y: Float) = gesture(x, y, x, y, duration = 30)

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, hold: Long = 0, duration: Long = 350) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x1, y1)
        SystemClock.sleep(hold)
        for (step in 1..10) {
            SystemClock.sleep(duration / 10)
            val fraction = step / 10f
            send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction)
        }
        send(MotionEvent.ACTION_UP, x2, y2)
    }

    private fun clipboard(): String {
        var text = ""
        instrumentation.runOnMainSync {
            text = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        }
        return text
    }

    private fun screenshot(name: String) {
        val directory = File(context.filesDir, "preview-test-shots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            automation.takeScreenshot()!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        screenshot("failure-${SystemClock.uptimeMillis()}")
        assertTrue("UI condition timed out", condition())
    }
}
