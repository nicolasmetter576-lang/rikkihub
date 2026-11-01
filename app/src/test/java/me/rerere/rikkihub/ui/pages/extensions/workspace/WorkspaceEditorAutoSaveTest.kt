package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 编辑器自动保存触发器 + 「还原到打开时的版本」的纯 JVM 回归. 触发器只决定
 * "何时调用 save"; CAS/dirty/暂停判定在页面与设备 VM 的 save 里, 依赖 Compose/设备运行时.
 */
class WorkspaceEditorAutoSaveTest {

    private val quiet = 60L

    private fun TextFieldState.type(text: String) {
        edit { append(text) }
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `burst of edits saves once after input pauses`() = runBlocking {
        val state = TextFieldState("a")
        val saves = AtomicInteger()
        val job = launch { runWorkspaceEditorAutoSave(state, awaitIdle = {}, save = { saves.incrementAndGet() }, delayMillis = quiet) }
        delay(10)
        repeat(4) {
            state.type("x")
            delay(quiet / 3)
        }
        assertEquals(0, saves.get())
        delay(quiet * 4)
        assertEquals(1, saves.get())
        job.cancel()
    }

    @Test
    fun `selection only change does not trigger save`() = runBlocking {
        val state = TextFieldState("abc")
        val saves = AtomicInteger()
        val job = launch { runWorkspaceEditorAutoSave(state, awaitIdle = {}, save = { saves.incrementAndGet() }, delayMillis = quiet) }
        delay(quiet * 4)
        val afterInitial = saves.get()
        state.edit { selection = TextRange(1) }
        Snapshot.sendApplyNotifications()
        delay(quiet * 4)
        assertEquals(afterInitial, saves.get())
        job.cancel()
    }

    @Test
    fun `save waits for in flight save to finish`() = runBlocking {
        val state = TextFieldState("")
        val idle = CompletableDeferred<Unit>()
        val saves = AtomicInteger()
        val job = launch {
            runWorkspaceEditorAutoSave(state, awaitIdle = { idle.await() }, save = { saves.incrementAndGet() }, delayMillis = quiet)
        }
        delay(10)
        state.type("typed during save")
        delay(quiet * 4)
        assertEquals(0, saves.get())
        idle.complete(Unit)
        delay(quiet)
        assertEquals(1, saves.get())
        job.cancel()
    }

    @Test
    fun `revert is one undoable edit back to opened text`() {
        val opened = "opened\nversion"
        val state = TextFieldState(opened)
        state.edit { append(" + autosaved edits") }
        val edited = state.text.toString()
        assertTrue(workspaceEditorCanRevert(opened, state.text))

        state.revertWorkspaceEditorTo(opened)
        assertEquals(opened, state.text.toString())
        assertEquals(TextRange(opened.length), state.selection)
        assertFalse(workspaceEditorCanRevert(opened, state.text))

        state.undoState.undo()
        assertEquals(edited, state.text.toString())
        state.undoState.redo()
        assertEquals(opened, state.text.toString())
    }

    @Test
    fun `revert unavailable without opened text`() {
        assertFalse(workspaceEditorCanRevert(null, "anything"))
        assertFalse(workspaceEditorCanRevert("same", "same"))
        assertTrue(workspaceEditorCanRevert("", "x"))
    }
}
