package me.rerere.rikkihub.service

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkihub.RouteActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/**
 * A release and a new acquire can be queued together (one generation ends while another starts on a
 * background thread). Handling the release used to call stopSelf() unconditionally: with the acquire's
 * startForegroundService() still waiting for startForeground(), the system crashes the app
 * ("Context.startForegroundService() did not then call Service.startForeground()").
 */
@RunWith(AndroidJUnit4::class)
class ChatGenerationForegroundServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<RouteActivity>
    private val acquired = mutableListOf<Uuid>()

    @Before
    fun setUp() {
        // Foreground app: background service starts are not allowed.
        scenario = ActivityScenario.launch(RouteActivity::class.java)
        waitUntil(10_000) { serviceState() == null }
    }

    @After
    fun tearDown() {
        acquired.forEach { ChatGenerationForegroundService.release(context, it) }
        waitUntil(10_000) { serviceState() == null }
        scenario.close()
    }

    @Test
    fun releaseQueuedWithAPendingAcquireKeepsTheServiceInForeground() {
        val conversationId = Uuid.random()
        val next = Uuid.random()
        acquired += next
        instrumentation.runOnMainSync {
            // Both start requests are registered before the service handles the first one.
            ChatGenerationForegroundService.release(context, Uuid.random())
            ChatGenerationForegroundService.acquire(context, next, conversationId)
        }

        assertTrue("service should enter the foreground", waitUntil(5_000) { serviceState() == true })
        // The crash (and the foreground start timeout) would hit within this window.
        Thread.sleep(11_000)
        assertTrue("service should still be in the foreground", serviceState() == true)
    }

    @Test
    fun releasingTheLastGenerationStopsTheService() {
        val generation = Uuid.random()
        instrumentation.runOnMainSync {
            ChatGenerationForegroundService.acquire(context, generation, Uuid.random())
        }
        assertTrue(waitUntil(5_000) { serviceState() == true })

        instrumentation.runOnMainSync { ChatGenerationForegroundService.release(context, generation) }

        assertTrue("service should stop", waitUntil(5_000) { serviceState() == null })
    }

    /** null = not running, otherwise whether it is in the foreground. */
    @Suppress("DEPRECATION")
    private fun serviceState(): Boolean? {
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getRunningServices(Int.MAX_VALUE)
            .firstOrNull {
                it.service.packageName == context.packageName &&
                    it.service.className == ChatGenerationForegroundService::class.java.name
            }
            ?.foreground
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
