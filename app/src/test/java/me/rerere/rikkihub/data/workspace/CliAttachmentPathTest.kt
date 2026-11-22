package me.rerere.rikkihub.data.workspace

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.transformers.boundDocumentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CliAttachmentPathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun withWorkspacePath(path: String) = buildJsonObject {
        put(WORKSPACE_PATH_METADATA_KEY, path)
    }

    @Test
    fun `resolveCliGuestPath keeps valid workspacePath when unscoped`() {
        val part = UIMessagePart.Image(
            url = "file:///data/user/0/app/files/upload/pic.png",
            metadata = withWorkspacePath("/workspace/notes/pic.png"),
        )
        assertEquals("/workspace/notes/pic.png", resolveCliGuestPath(part, filesScope = null, exposeUpload = false))
    }

    @Test
    fun `resolveCliGuestPath enforces filesScope containment and denies cross-scope leaks`() {
        val inside = UIMessagePart.Document(
            url = "file:///data/user/0/app/files/upload/report.pdf",
            fileName = "report.pdf",
            metadata = withWorkspacePath("/workspace/proj/sub/report.pdf"),
        )
        val exactScope = UIMessagePart.Document(
            url = "file:///data/user/0/app/files/upload/a.txt",
            fileName = "a.txt",
            metadata = withWorkspacePath("/workspace/proj"),
        )
        val prefixCollision = UIMessagePart.Image(
            url = "file:///data/user/0/app/files/upload/x.png",
            metadata = withWorkspacePath("/workspace/proj2/x.png"),
        )
        val sibling = UIMessagePart.Image(
            url = "file:///data/user/0/app/files/upload/y.png",
            metadata = withWorkspacePath("/workspace/other/y.png"),
        )
        val traversal = UIMessagePart.Image(
            url = "file:///data/user/0/app/files/upload/z.png",
            metadata = withWorkspacePath("/workspace/../etc/passwd"),
        )

        assertEquals("/workspace/proj/sub/report.pdf", resolveCliGuestPath(inside, filesScope = "proj", exposeUpload = true))
        assertEquals("/workspace/proj/sub/report.pdf", resolveCliGuestPath(inside, filesScope = "/workspace/proj/", exposeUpload = false))
        assertEquals("/workspace/proj", resolveCliGuestPath(exactScope, filesScope = "proj", exposeUpload = false))
        assertNull(resolveCliGuestPath(prefixCollision, filesScope = "proj", exposeUpload = true))
        assertNull(resolveCliGuestPath(sibling, filesScope = "proj", exposeUpload = true))
        assertNull(resolveCliGuestPath(traversal, filesScope = null, exposeUpload = true))
        assertNull(resolveCliGuestPath(traversal, filesScope = "proj", exposeUpload = true))
        assertNull(resolveCliGuestPath(inside, filesScope = "../bad", exposeUpload = true))
    }

    @Test
    fun `resolveCliGuestPath maps upload files only when unscoped and exposeUpload is true`() {
        val image = UIMessagePart.Image(url = "file:///data/user/0/pkg/files/upload/photo%201.png")
        val document = UIMessagePart.Document(url = "file:///data/user/0/pkg/files/upload/spec.md", fileName = "spec.md")
        val video = UIMessagePart.Video(url = "file:///data/user/0/pkg/files/upload/clip.mp4")
        val audio = UIMessagePart.Audio(url = "file:///data/user/0/pkg/files/upload/voice.m4a")

        assertEquals("/upload/photo 1.png", resolveCliGuestPath(image, filesScope = null, exposeUpload = true))
        assertEquals("/upload/spec.md", resolveCliGuestPath(document, filesScope = null, exposeUpload = true))
        assertEquals("/upload/clip.mp4", resolveCliGuestPath(video, filesScope = null, exposeUpload = true))
        assertEquals("/upload/voice.m4a", resolveCliGuestPath(audio, filesScope = null, exposeUpload = true))

        assertNull(resolveCliGuestPath(image, filesScope = null, exposeUpload = false))
        assertNull(resolveCliGuestPath(image, filesScope = "proj", exposeUpload = true))
        assertNull(resolveCliGuestPath(UIMessagePart.Text("hello"), filesScope = null, exposeUpload = true))
    }

    @Test
    fun `resolveCliGuestPath rejects traversal, non-file schemes and non-upload directories`() {
        val candidates = listOf(
            "https://example.com/upload/a.png",
            "content://media/external/images/1",
            "file:///data/user/0/pkg/files/skills/a.png",
            "file:///data/user/0/pkg/files/upload/nested/a.png",
            "file:///data/user/0/pkg/files/upload/../upload/a.png",
            "file:///data/user/0/pkg/files/upload/%2e%2e/secret.txt",
            "file:///data/user/0/pkg/files/upload/..",
            "file:///data/user/0/pkg/files/upload/.",
            "file:///data/user/0/pkg/files/upload/",
            "file:///data/user/0/pkg/files/upload/upload/",
            "/data/user/0/pkg/files/upload/a.png",
        )
        candidates.forEach { url ->
            assertNull(url, resolveCliGuestPath(UIMessagePart.Image(url), filesScope = null, exposeUpload = true))
        }
    }

    @Test
    fun `resolveSafeLocalFile permits files under allowedDirs and rejects traversal, prefixes and symlinks`() {
        val root = tmp.newFolder("files")
        val upload = File(root, "upload").apply { mkdirs() }
        val media = File(root, "media_creation").apply { mkdirs() }
        val evilPrefix = File(root, "upload_evil").apply { mkdirs() }

        val valid = File(upload, "nested/doc.txt").apply {
            parentFile!!.mkdirs()
            writeText("ok")
        }
        val inMedia = File(media, "img.png").apply { writeBytes(byteArrayOf(1, 2)) }
        val inEvil = File(evilPrefix, "trap.txt").apply { writeText("evil") }
        val outside = tmp.newFile("outside.txt").apply { writeText("secret") }

        val allowed = listOf(upload, media)

        val resolved = resolveSafeLocalFile(valid.toURI().toString(), allowed)
        assertNotNull(resolved)
        assertEquals(valid.canonicalFile, resolved)
        assertEquals(inMedia.canonicalFile, resolveSafeLocalFile(inMedia.toURI().toString(), allowed))

        assertNull(resolveSafeLocalFile(inEvil.toURI().toString(), allowed))
        assertNull(resolveSafeLocalFile(outside.toURI().toString(), allowed))
        assertNull(resolveSafeLocalFile("file:///etc/passwd", allowed))
        assertNull(resolveSafeLocalFile("file://${upload.absolutePath}/../outside.txt", allowed))
        assertNull(resolveSafeLocalFile(upload.toURI().toString(), allowed))
        assertNull(resolveSafeLocalFile(File(upload, "missing.txt").toURI().toString(), allowed))
        assertNull(resolveSafeLocalFile("https://example.com/doc.txt", allowed))
        assertNull(resolveSafeLocalFile("content://provider/doc.txt", allowed))
        assertNull(resolveSafeLocalFile(valid.toURI().toString(), emptyList()))

        val symlink = File(upload, "escape.txt")
        try {
            Files.createSymbolicLink(symlink.toPath(), outside.toPath())
            assertNull(resolveSafeLocalFile(symlink.toURI().toString(), allowed))
        } catch (_: UnsupportedOperationException) {
            // Host filesystem without symlink support.
        }
    }

    @Test
    fun `boundDocumentText preserves short text and truncates overlong content with notice`() {
        assertEquals("hello", boundDocumentText("hello", maxChars = 10))
        val bounded = boundDocumentText("x".repeat(50), maxChars = 20)
        assertTrue(bounded.startsWith("x".repeat(20)))
        assertTrue(bounded, bounded.contains("truncated"))
        assertTrue(bounded, bounded.contains("30"))
    }
}
