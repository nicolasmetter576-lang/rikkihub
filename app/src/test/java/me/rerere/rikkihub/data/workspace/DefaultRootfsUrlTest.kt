package me.rerere.rikkihub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultRootfsUrlTest {
    private fun arch(vararg abis: String) =
        defaultRootfsUrl(abis.toList()).substringAfterLast("-base-").removeSuffix(".tar.gz")

    @Test
    fun `arm64 phone gets arm64`() {
        assertEquals("arm64", arch("arm64-v8a", "armeabi-v7a", "armeabi"))
    }

    @Test
    fun `x86_64 device gets amd64`() {
        assertEquals("amd64", arch("x86_64", "x86"))
        // ChromeOS / emulators with ARM translation list x86_64 first, which is what the APK loads.
        assertEquals("amd64", arch("x86_64", "arm64-v8a"))
    }

    @Test
    fun `ABIs the APK does not ship are skipped`() {
        assertEquals("arm64", arch("armeabi-v7a", "arm64-v8a"))
        assertEquals("amd64", arch("x86", "x86_64"))
        assertEquals("arm64", arch())
    }

    @Test
    fun `url points at the ubuntu base release`() {
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz",
            defaultRootfsUrl(listOf("x86_64")),
        )
    }
}
