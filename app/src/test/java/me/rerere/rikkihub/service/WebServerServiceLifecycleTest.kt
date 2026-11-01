package me.rerere.rikkihub.service

import me.rerere.rikkihub.web.WebServerState
import org.junit.Assert.assertEquals
import org.junit.Test

class WebServerServiceLifecycleTest {
    private val idle = WebServerState()
    private val loading = WebServerState(isLoading = true)
    private val running = WebServerState(isRunning = true, port = 9000)
    private val failed = WebServerState(port = 9000, error = "Class NioServerSocketChannel does not have a public non-arg constructor")

    @Test
    fun `failed start stops the service`() {
        val lifecycle = WebServerServiceLifecycle()
        assertEquals(WebServerServiceLifecycle.Action.NONE, lifecycle.onState(idle))
        assertEquals(WebServerServiceLifecycle.Action.NONE, lifecycle.onState(loading))
        assertEquals(WebServerServiceLifecycle.Action.STOP, lifecycle.onState(failed))
    }

    @Test
    fun `stale error from an earlier attempt does not stop a new start`() {
        val lifecycle = WebServerServiceLifecycle()
        assertEquals(WebServerServiceLifecycle.Action.NONE, lifecycle.onState(failed))
        assertEquals(WebServerServiceLifecycle.Action.NONE, lifecycle.onState(loading))
        assertEquals(WebServerServiceLifecycle.Action.SHOW_RUNNING, lifecycle.onState(running))
    }

    @Test
    fun `stop after running ends the service`() {
        val lifecycle = WebServerServiceLifecycle()
        assertEquals(WebServerServiceLifecycle.Action.SHOW_RUNNING, lifecycle.onState(running))
        assertEquals(WebServerServiceLifecycle.Action.NONE, lifecycle.onState(running.copy(isRunning = false, isLoading = true)))
        assertEquals(WebServerServiceLifecycle.Action.STOP, lifecycle.onState(idle))
    }

    @Test
    fun `notification url follows https mode`() {
        assertEquals("http://localhost:9000", running.copy(localhostOnly = true).notificationUrl())
        assertEquals("https://localhost:9000", running.copy(localhostOnly = true, secure = true).notificationUrl())
        assertEquals("http://192.168.1.5:9000", running.copy(address = "192.168.1.5").notificationUrl())
        assertEquals("https://192.168.1.5:9000", running.copy(address = "192.168.1.5", secure = true).notificationUrl())
        assertEquals("http://localhost:9000", running.notificationUrl())
    }
}
