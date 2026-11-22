package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceUrlHandoffTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val authUrl = "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=1-x.apps" +
        ".googleusercontent.com&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&response_type=code" +
        "&scope=openid+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcloud-platform&state=abc"

    private fun xdgOpen(linuxDir: File) = File(linuxDir, "usr/local/bin/xdg-open")

    @Test
    fun `installs an executable xdg-open and the mount point into a fresh rootfs`() {
        val linuxDir = tmp.newFolder("rootfs")
        WorkspaceUrlHandoff.install(linuxDir)
        assertTrue(File(linuxDir, WorkspaceUrlHandoff.GUEST_DIR.trimStart('/')).isDirectory)
        val script = xdgOpen(linuxDir)
        assertEquals(WorkspaceUrlHandoff.XDG_OPEN_SCRIPT, script.readText())
        assertTrue(script.canExecute())
    }

    @Test
    fun `refreshes an older copy of its own script`() {
        val linuxDir = tmp.newFolder("rootfs")
        val script = xdgOpen(linuxDir).apply { parentFile.mkdirs() }
        script.writeText("#!/bin/sh\n# rikkihub-xdg-open: old version\nexit 0\n")
        WorkspaceUrlHandoff.install(linuxDir)
        assertEquals(WorkspaceUrlHandoff.XDG_OPEN_SCRIPT, script.readText())
        assertTrue(script.canExecute())
    }

    @Test
    fun `leaves an xdg-open the user put there alone`() {
        val linuxDir = tmp.newFolder("rootfs")
        val script = xdgOpen(linuxDir).apply { parentFile.mkdirs() }
        script.writeText("#!/bin/sh\necho mine\n")
        WorkspaceUrlHandoff.install(linuxDir)
        assertEquals("#!/bin/sh\necho mine\n", script.readText())

        val linked = tmp.newFolder("linked")
        val link = xdgOpen(linked).apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), File("/usr/bin/xdg-open").toPath())
        WorkspaceUrlHandoff.install(linked)
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun `parse accepts only a single http link`() {
        assertEquals(authUrl, WorkspaceUrlHandoff.parseRequest("$authUrl\n"))
        assertEquals("HTTP://example.com", WorkspaceUrlHandoff.parseRequest("HTTP://example.com"))
        assertNull(WorkspaceUrlHandoff.parseRequest("file:///etc/passwd"))
        assertNull(WorkspaceUrlHandoff.parseRequest("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(WorkspaceUrlHandoff.parseRequest("https://"))
        assertNull(WorkspaceUrlHandoff.parseRequest("https://a.example\nhttps://b.example"))
        assertNull(WorkspaceUrlHandoff.parseRequest("https://a.example/x y"))
        assertNull(WorkspaceUrlHandoff.parseRequest(""))
    }

    /** Runs the real script on the host with the guest directory swapped for a temp one. */
    private fun runScript(dir: File, vararg args: String): Int {
        val script = tmp.newFile().apply {
            writeText(WorkspaceUrlHandoff.XDG_OPEN_SCRIPT.replace(WorkspaceUrlHandoff.GUEST_DIR, dir.absolutePath))
        }
        return ProcessBuilder(listOf("/bin/sh", script.absolutePath) + args).inheritIO().start().waitFor()
    }

    @Test
    fun `script hands the url over as one complete request file`() {
        val dir = tmp.newFolder("open-url")
        assertEquals(0, runScript(dir, authUrl))
        val files = dir.listFiles().orEmpty().toList()
        assertEquals(1, files.size)
        val request = files.single()
        assertTrue(request.name, request.name.endsWith(WorkspaceUrlHandoff.REQUEST_SUFFIX) && !request.name.startsWith("."))
        assertEquals(authUrl, WorkspaceUrlHandoff.parseRequest(request.readText()))
    }

    @Test
    fun `script refuses non http targets and a missing handoff directory`() {
        val dir = tmp.newFolder("open-url")
        assertEquals(1, runScript(dir, "/workspace/readme.md"))
        assertEquals(1, runScript(dir))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertEquals(3, runScript(File(dir, "missing"), authUrl))
    }
}
