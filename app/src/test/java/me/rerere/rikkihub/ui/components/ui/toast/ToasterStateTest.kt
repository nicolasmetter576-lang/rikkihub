package me.rerere.rikkihub.ui.components.ui.toast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class ToasterStateTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val toaster = ToasterState(scope, maxVisibleToasts = 3)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a timed toast dismisses itself`() {
        toaster.show("done", duration = 50.milliseconds)
        assertTrue(toaster.items.single().visibleState.targetState)
        Thread.sleep(400)
        assertFalse(toaster.items.single().visibleState.targetState)
    }

    @Test
    fun `a progress toast stays until its handle is dismissed`() {
        val handle = toaster.showProgress("saving")
        Thread.sleep(400)
        assertTrue("进度提示不应自己消失", toaster.items.single().visibleState.targetState)

        handle.dismiss()
        assertFalse(toaster.items.single().visibleState.targetState)
        // 重复收起无害
        handle.dismiss()
        assertFalse(toaster.items.single().visibleState.targetState)
    }

    @Test
    fun `progress toast does not overlap the result toast once dismissed`() {
        val handle = toaster.showProgress("saving")
        handle.dismiss()
        toaster.show("saved", type = ToastType.Success, duration = 5000.milliseconds)

        val visible = toaster.items.filter { it.visibleState.targetState }
        assertTrue(visible.map { it.message } == listOf("saved"))
    }
}
