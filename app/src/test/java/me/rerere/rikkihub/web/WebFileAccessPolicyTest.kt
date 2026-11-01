package me.rerere.rikkihub.web

import io.ktor.http.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for the `/api/files/path/{...}` admission + serve policy.
 * Route-level coverage (headers + status codes) lives in WebApiBoundaryRouteTest.
 */
class WebFileAccessPolicyTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File

    private fun prepare(): File {
        filesDir = tmp.newFolder("files")
        listOf("upload", "images", "fonts", "subagent_runs", "tool_outputs",
            "datastore", "workspaces", "skills", "restore_staging").forEach {
            File(filesDir, it).mkdirs()
        }
        return filesDir
    }

    private fun touch(dir: String, name: String, content: String = "x"): File {
        val file = File(filesDir, "$dir/$name")
        file.writeText(content)
        return file
    }

    // ---- allowed resources ----

    @Test
    fun `managed upload image resolves`() {
        prepare()
        val file = touch("upload", "abc123.png")
        val (path, resolved) = resolveManagedWebFile(filesDir, "upload/abc123.png")
        assertEquals("upload/abc123.png", path)
        assertEquals(file.canonicalFile, resolved)
    }

    @Test
    fun `unicode upload names still resolve`() {
        prepare()
        val file = touch("upload", "测试-文件.pdf")
        val (path, resolved) = resolveManagedWebFile(filesDir, "upload/测试-文件.pdf")
        assertEquals("upload/测试-文件.pdf", path)
        assertEquals(file.canonicalFile, resolved)
    }

    @Test
    fun `images and fonts subtrees resolve`() {
        prepare()
        touch("images", "a1b2.webp")
        touch("fonts", "chat_font.123.ttf")
        assertEquals("images/a1b2.webp", resolveManagedWebFile(filesDir, "images/a1b2.webp").first)
        assertEquals("fonts/chat_font.123.ttf", resolveManagedWebFile(filesDir, "fonts/chat_font.123.ttf").first)
    }

    @Test
    fun `subagent_runs and tool_outputs allow flat json txt artifacts`() {
        prepare()
        touch("subagent_runs", "run-1.json")
        touch("tool_outputs", "call_42.txt")
        touch("tool_outputs", "legacy.json")
        assertEquals("subagent_runs/run-1.json", resolveManagedWebFile(filesDir, "subagent_runs/run-1.json").first)
        assertEquals("tool_outputs/call_42.txt", resolveManagedWebFile(filesDir, "tool_outputs/call_42.txt").first)
        assertEquals("tool_outputs/legacy.json", resolveManagedWebFile(filesDir, "tool_outputs/legacy.json").first)
    }

    // ---- denied: outside allowlist ----

    @Test
    fun `datastore workspaces skills and other subtrees denied`() {
        prepare()
        touch("datastore", "settings.preferences_pb")
        touch("workspaces", "notes.txt")
        touch("skills", "skill.md")
        touch("restore_staging", "backup.db")
        for (p in listOf(
            "datastore/settings.preferences_pb",
            "workspaces/notes.txt",
            "skills/skill.md",
            "restore_staging/backup.db",
            "databases/app.db",
            "app_webview/x",
            "noBackupFilesDir/keys",
        )) {
            try {
                resolveManagedWebFile(filesDir, p)
                fail("expected deny for $p")
            } catch (e: WebFileForbiddenException) {
                assertEquals(WEB_ERROR_FILE_FORBIDDEN, e.errorCode)
            }
        }
    }

    @Test
    fun `log subtrees reject non-json-txt names`() {
        prepare()
        touch("subagent_runs", "evil.html")
        touch("subagent_runs", "notes")
        touch("tool_outputs", "dump.bin")
        for (p in listOf(
            "subagent_runs/evil.html",
            "subagent_runs/notes",
            "tool_outputs/dump.bin",
            "subagent_runs/nested/deep.json",
            "upload/nested/deep.png",
            "upload",               // bare dir
            "upload/sub/extra.png", // nested
        )) {
            try {
                resolveManagedWebFile(filesDir, p)
                fail("expected deny for $p")
            } catch (e: WebFileForbiddenException) {
                // ok
            }
        }
    }

    @Test
    fun `traversal dotfiles and control names denied`() {
        prepare()
        for (p in listOf(
            "upload/../datastore/x",
            "upload/../../etc/passwd",
            "../upload/x.png",
            "upload//x.png",
            "upload/.hidden",
            "upload/..",
            ".",
            "",
            "upload/x%00.png",
            "upload/a..b.png", // dotdot inside name
        )) {
            try {
                val r = resolveManagedWebFile(filesDir, p)
                fail("expected deny for '$p' but got ${r.first}")
            } catch (e: ApiException) {
                // WEB_FILE_* codes all fine here
            }
        }
    }

    @Test
    fun `absolute and backslash-rooted paths rejected not rewritten`() {
        prepare()
        touch("upload", "pic.png")
        // Rooted inputs must be denied outright — never silently rewritten
        // into a managed relative path.
        for (p in listOf(
            "/upload/pic.png",
            "/upload/missing.png",
            "//upload/pic.png",
            "\\upload\\pic.png",
            "\\upload/pic.png",
            " /upload/pic.png",   // whitespace-trimmed then rooted
            "/",
        )) {
            try {
                val r = resolveManagedWebFile(filesDir, p)
                fail("expected deny for rooted path '$p' but got ${r.first}")
            } catch (e: WebFileForbiddenException) {
                assertEquals(WEB_ERROR_FILE_FORBIDDEN, e.errorCode)
            }
        }
        // Legal relative forms still resolve; interior backslash stays a
        // tolerated alias for '/' as before.
        assertEquals("upload/pic.png", resolveManagedWebFile(filesDir, "upload/pic.png").first)
        assertEquals("upload/pic.png", resolveManagedWebFile(filesDir, "upload\\pic.png").first)
        assertEquals("upload/pic.png", resolveManagedWebFile(filesDir, " upload/pic.png ").first)
    }

    // ---- symlink safety ----

    @Test
    fun `leaf symlink escaping to outside denied`() {
        prepare()
        val secret = tmp.newFile("secret.txt")
        secret.writeText("canary")
        val link = File(filesDir, "upload/escape.txt")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        try {
            resolveManagedWebFile(filesDir, "upload/escape.txt")
            fail("symlink escape must be denied")
        } catch (e: WebFileSymlinkDeniedException) {
            assertEquals(WEB_ERROR_FILE_SYMLINK_DENIED, e.errorCode)
        }
    }

    @Test
    fun `leaf symlink inside allowed dir still denied`() {
        prepare()
        val real = touch("upload", "real.png")
        val link = File(filesDir, "upload/alias.png")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        try {
            resolveManagedWebFile(filesDir, "upload/alias.png")
            fail("symlink alias must be denied")
        } catch (e: WebFileSymlinkDeniedException) {
            // ok
        }
    }

    @Test
    fun `symlinked allowed directory denied`() {
        prepare()
        val outside = tmp.newFolder("outside")
        File(outside, "x.txt").writeText("x")
        val linkDir = File(filesDir, "fonts")
        // Replace real dir with a symlink.
        linkDir.delete()
        Files.createSymbolicLink(linkDir.toPath(), outside.toPath())
        try {
            resolveManagedWebFile(filesDir, "fonts/x.txt")
            fail("symlinked subtree must be denied")
        } catch (e: WebFileSymlinkDeniedException) {
            // ok
        }
    }

    // ---- not found ----

    @Test
    fun `missing file reports not found not forbidden`() {
        prepare()
        try {
            resolveManagedWebFile(filesDir, "upload/missing.png")
            fail("expected not found")
        } catch (e: WebFileNotFoundException) {
            assertEquals(WEB_ERROR_FILE_NOT_FOUND, e.errorCode)
        }
    }

    // ---- serve plan ----

    @Test
    fun `active document types forced to attachment download`() {
        for (mime in listOf("text/html", "image/svg+xml", "application/xml", "text/xml",
            "application/xhtml+xml", "text/javascript", "application/ecmascript")) {
            val plan = webFileServePlan("x.bin", mime)
            assertTrue("$mime must force attachment", plan.forceAttachment)
            assertEquals(ContentType.Application.OctetStream, plan.contentType)
        }
        // extension-derived active types also forced
        val svgPlan = webFileServePlan("icon.svg", null)
        assertTrue(svgPlan.forceAttachment)
        assertEquals(ContentType.Application.OctetStream, svgPlan.contentType)
        val htmlPlan = webFileServePlan("page.html", null)
        assertTrue(htmlPlan.forceAttachment)

        // Parameterized active mime must not bypass the check.
        val paramSvg = webFileServePlan("x.bin", "image/svg+xml; charset=utf-8")
        assertTrue(paramSvg.forceAttachment)
        assertEquals(ContentType.Application.OctetStream, paramSvg.contentType)
    }

    @Test
    fun `normal images and fonts stay inline with real mime`() {
        val png = webFileServePlan("a.png", null)
        assertFalse(png.forceAttachment)
        assertEquals(ContentType.Image.PNG, png.contentType)

        val managedPng = webFileServePlan("uuid-no-ext", "image/png")
        assertFalse(managedPng.forceAttachment)
        assertEquals(ContentType.Image.PNG, managedPng.contentType)

        val pdf = webFileServePlan("doc.pdf", "application/pdf")
        assertFalse(pdf.forceAttachment)
        assertEquals(ContentType.Application.Pdf, pdf.contentType)

        val txt = webFileServePlan("out.txt", null)
        assertFalse(txt.forceAttachment)
        assertEquals(ContentType.Text.Plain, txt.contentType)
    }
}
