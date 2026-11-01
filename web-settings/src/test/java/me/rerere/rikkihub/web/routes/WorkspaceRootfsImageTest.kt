package me.rerere.rikkihub.web.routes

import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET /workspaces/{id}/rootfs-file`: what the chat page may ask for and how the answer is labelled. */
class WorkspaceRootfsImageTest {
    @Test
    fun `an absolute path to a picture is accepted and typed by its extension`() {
        val png = parseRootfsImageRequest("/workspace/out/chart.png", null)
        assertEquals("/workspace/out/chart.png", png.path)
        assertEquals("image/png", png.contentType)
        assertNull(png.filesScope)

        assertEquals("image/jpeg", parseRootfsImageRequest("/tmp/a.JPG", null).contentType)
        assertEquals("image/jpeg", parseRootfsImageRequest("/tmp/a.jpeg", null).contentType)
        assertEquals("image/webp", parseRootfsImageRequest("/upload/a.webp", null).contentType)
        assertEquals("image/gif", parseRootfsImageRequest("/upload/a.gif", null).contentType)
    }

    @Test
    fun `a path keeps its spaces and non-ascii characters as sent`() {
        assertEquals("/workspace/图 表.png", parseRootfsImageRequest("/workspace/图 表.png", null).path)
    }

    @Test
    fun `only pictures a browser can draw are served`() {
        for (path in listOf(
            "/workspace/a.html", "/workspace/a.txt", "/workspace/a.pdf", "/workspace/a.heic",
            "/workspace/a.png.exe", "/workspace/noextension", "/workspace/.png/", "/workspace/image",
        )) {
            assertThrows(path, BadRequestException::class.java) { parseRootfsImageRequest(path, null) }
        }
    }

    @Test
    fun `the extension is taken from the file name, not from a directory`() {
        assertThrows(BadRequestException::class.java) { parseRootfsImageRequest("/workspace/a.png/readme", null) }
    }

    @Test
    fun `relative, empty and control characters are rejected`() {
        for (path in listOf(null, "", "  ", "out.png", "workspace/out.png", "/workspace/a\u0000.png")) {
            assertThrows(path, BadRequestException::class.java) { parseRootfsImageRequest(path, null) }
        }
    }

    @Test
    fun `svg is served as an image but must never be navigated to`() {
        val svg = parseRootfsImageRequest("/workspace/diagram.svg", null)
        assertEquals("image/svg+xml", svg.contentType)
        assertTrue(svg.forceAttachment)
        assertFalse(parseRootfsImageRequest("/workspace/a.png", null).forceAttachment)
    }

    @Test
    fun `the working directory is accepted as a guest path or a files-relative one`() {
        assertEquals("a/b", parseRootfsImageRequest("/workspace/a/b/x.png", "/workspace/a/b").filesScope)
        assertEquals("a/b", parseRootfsImageRequest("/workspace/a/b/x.png", "a/b").filesScope)
        assertEquals("a/b", parseRootfsImageRequest("/workspace/a/b/x.png", " /workspace/a//b/ ").filesScope)
    }

    @Test
    fun `no working directory or the workspace root means not confined`() {
        assertNull(parseRootfsImageRequest("/workspace/x.png", null).filesScope)
        assertNull(parseRootfsImageRequest("/workspace/x.png", "").filesScope)
        assertNull(parseRootfsImageRequest("/workspace/x.png", "   ").filesScope)
        assertNull(parseRootfsImageRequest("/workspace/x.png", "/workspace").filesScope)
    }

    @Test
    fun `an invalid working directory is a bad request, never a wider view`() {
        for (scope in listOf("/etc", "/workspace/../x", "a/../b", "a\\b", "a\u0000b")) {
            assertThrows(scope, BadRequestException::class.java) { parseRootfsImageRequest("/workspace/x.png", scope) }
        }
    }

    @Test
    fun `the etag changes when the picture is rewritten`() {
        val first = rootfsImageEtag(1000, 1_700_000_000_000)
        assertEquals(first, rootfsImageEtag(1000, 1_700_000_000_000))
        assertFalse(first == rootfsImageEtag(1001, 1_700_000_000_000))
        assertFalse(first == rootfsImageEtag(1000, 1_700_000_000_001))
    }

    @Test
    fun `conditional requests match strong, weak and listed tags`() {
        val etag = rootfsImageEtag(10, 20)
        assertTrue(rootfsImageEtagMatches(etag, etag))
        assertTrue(rootfsImageEtagMatches("W/$etag", etag))
        assertTrue(rootfsImageEtagMatches("\"other\", $etag", etag))
        assertTrue(rootfsImageEtagMatches("*", etag))
        assertFalse(rootfsImageEtagMatches("\"other\"", etag))
        assertFalse(rootfsImageEtagMatches("", etag))
        assertFalse(rootfsImageEtagMatches(null, etag))
    }
}
