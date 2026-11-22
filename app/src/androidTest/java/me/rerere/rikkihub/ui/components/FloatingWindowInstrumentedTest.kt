package me.rerere.rikkihub.ui.components

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.petterp.floatingx.app.appHost
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import me.rerere.rikkihub.ui.components.ui.setVisible
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B12：同时存在两个 RouteActivity 实例时，两个 TTS 控制条用同一个 tag 安装悬浮窗，
 * FloatingX 会 cancel 先装的那个；旧实例随后在朗读状态变化时还会对它 show()/hide()。
 */
@RunWith(AndroidJUnit4::class)
class FloatingWindowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    private fun install(tag: String): FxControl = FloatingX.install(tag) {
        appHost(app)
        compose {}
    }

    @Test
    fun showingAControlReplacedBySameTagInstallThrows() {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            val first = install("b12_probe")
            val second = install("b12_probe")
            try {
                failure = runCatching { first.show() }.exceptionOrNull()
            } finally {
                second.cancel()
            }
        }
        assertTrue("expected show() on the replaced control to throw, got $failure", failure is IllegalStateException)
    }

    @Test
    fun setVisibleSkipsAControlReplacedBySameTagInstall() {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            val first = install("b12_guard")
            val second = install("b12_guard")
            try {
                failure = runCatching {
                    first.setVisible(true)
                    first.setVisible(false)
                }.exceptionOrNull()
            } finally {
                second.cancel()
            }
        }
        assertNull(failure)
    }
}
