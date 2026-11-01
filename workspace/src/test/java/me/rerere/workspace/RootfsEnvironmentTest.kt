package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RootfsEnvironmentTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `os-release prefers pretty name`() {
        val content = """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.24.1
            PRETTY_NAME="Alpine Linux v3.24"
            HOME_URL="https://alpinelinux.org/"
        """.trimIndent()
        assertEquals("Alpine Linux v3.24", parseOsReleaseName(content))
    }

    @Test
    fun `os-release falls back to name and version`() {
        val content = """
            # comment
            NAME='Ubuntu'
            VERSION_ID="24.04"
        """.trimIndent()
        assertEquals("Ubuntu 24.04", parseOsReleaseName(content))
    }

    @Test
    fun `os-release without a name is null`() {
        assertNull(parseOsReleaseName("ID=unknown\nnot a key value line\n"))
    }

    @Test
    fun `os-release name is sanitized and truncated`() {
        val name = parseOsReleaseName("PRETTY_NAME=\"Distro\u0007 ${"x".repeat(200)}\"")!!
        assertEquals(80, name.length)
        assertEquals("Distro x", name.take(8))
    }

    @Test
    fun `alpine rootfs reads distro through an absolute os-release link`() {
        val linux = tmp.newFolder("alpine")
        File(linux, "bin").mkdirs()
        Files.createSymbolicLink(File(linux, "bin/sh").toPath(), File("/bin/busybox").toPath())
        File(linux, "usr/lib").mkdirs()
        File(linux, "usr/lib/os-release").writeText("PRETTY_NAME=\"Alpine Linux v3.24\"\n")
        File(linux, "etc").mkdirs()
        // 宿主机上解析不到的绝对路径软链，按 Rootfs 内路径解析
        Files.createSymbolicLink(File(linux, "etc/os-release").toPath(), File("/usr/lib/os-release").toPath())

        assertEquals(RootfsEnvironment(shell = "/bin/sh", distro = "Alpine Linux v3.24"), readRootfsEnvironment(linux))
    }

    @Test
    fun `missing os-release leaves distro unknown`() {
        val linux = tmp.newFolder("bare")
        File(linux, "bin").mkdirs()
        File(linux, "bin/sh").writeText("sh")
        File(linux, "bin/bash").writeText("bash")
        assertEquals(RootfsEnvironment(shell = "/bin/bash", distro = null), readRootfsEnvironment(linux))
    }

    @Test
    fun `manager reports no environment without a rootfs`() {
        val manager = WorkspaceManager(tmp.newFolder("base"))
        assertNull(manager.rootfsEnvironment("ws"))
    }
}
