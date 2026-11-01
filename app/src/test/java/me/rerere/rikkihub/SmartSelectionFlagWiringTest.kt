package me.rerere.rikkihub

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring guard only; the ColorOS toolbar behavior itself needs on-device acceptance. */
class SmartSelectionFlagWiringTest {
    @Test fun `smart selection is turned off before the app composes anything`() {
        val base = File("src/main/java/me/rerere/rikkihub")
            .takeIf { it.isDirectory } ?: File("app/src/main/java/me/rerere/rikkihub")
        val onCreate = File(base, "RikkihubApp.kt").readText().substringAfter("override fun onCreate() {")
        val flag = onCreate.indexOf("ComposeFoundationFlags.isSmartSelectionEnabled = false")
        assertTrue(flag >= 0)
        assertTrue(flag < onCreate.indexOf("startKoin {"))
    }
}
