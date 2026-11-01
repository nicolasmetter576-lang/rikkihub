package me.rerere.rikkihub.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkihub.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The production [WebServerManager] on the device: plain HTTP by default (intranet use),
 * open API while web auth is off, password required once it is on, TLS only when enabled.
 */
@RunWith(AndroidJUnit4::class)
class WebServerModesInstrumentedTest {
    private val koin get() = GlobalContext.get()
    private val manager get() = koin.get<WebServerManager>()
    private val settings get() = koin.get<SettingsStore>()

    private suspend fun startAndAwait(port: Int) {
        manager.start(port = port, localhostOnly = true)
        withTimeout(30_000) { while (!manager.state.value.isRunning) delay(100) }
    }

    private suspend fun stopAndAwait() {
        manager.stop()
        withTimeout(30_000) { while (manager.state.value.isRunning) delay(100) }
    }

    private fun status(url: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        return try { connection.responseCode } finally { connection.disconnect() }
    }

    @Test(timeout = 120_000)
    fun plainHttpOpenThenPasswordThenHttps() = runBlocking<Unit> {
        val original = settings.settingsSnapshot()
        val port = 18383
        try {
            settings.update { it.copy(webServerHttpsEnabled = false, webServerJwtEnabled = false) }
            startAndAwait(port)
            assertFalse(manager.state.value.secure)
            assertEquals(200, status("http://127.0.0.1:$port/api/health"))
            assertEquals("auth off: API open", 200, status("http://127.0.0.1:$port/api/conversations/paged"))

            settings.update { it.copy(webServerJwtEnabled = true, webServerAccessPassword = "itest-pass") }
            assertEquals("auth on: token required", 401, status("http://127.0.0.1:$port/api/conversations/paged"))
            stopAndAwait()

            settings.update { it.copy(webServerHttpsEnabled = true) }
            startAndAwait(port)
            assertTrue(manager.state.value.secure)
            assertNotNull(manager.state.value.certificateFingerprint)
            stopAndAwait()
        } finally {
            runCatching { stopAndAwait() }
            settings.update {
                it.copy(
                    webServerHttpsEnabled = original.webServerHttpsEnabled,
                    webServerJwtEnabled = original.webServerJwtEnabled,
                    webServerAccessPassword = original.webServerAccessPassword,
                )
            }
        }
    }
}
