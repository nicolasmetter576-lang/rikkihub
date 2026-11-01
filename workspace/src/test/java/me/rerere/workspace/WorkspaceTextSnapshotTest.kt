package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

class WorkspaceTextSnapshotTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fs = WorkspaceFileSystem()
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    @Test fun snapshotNormalizesEditorOnlyAndNoopPreservesRawBytesInodeMtimeAndRevision() {
        val root = tmp.newFolder()
        val sources = listOf("", "plain", "\n\n", "# 中文😀\r\n\r\ntail\r\n", "a\rb\r\r", "a\nb\n", "a\r\nb\rc\n\nend", "a\r\nb\rc\n\n")
        for ((index, source) in sources.withIndex()) for (withBom in listOf(false, true)) {
            val path = "$index-$withBom.md"
            val file = File(root, path)
            val raw = (if (withBom) bom else byteArrayOf()) + source.toByteArray()
            file.writeBytes(raw)
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val snap = fs.readTextSnapshot(root, path)
            assertEquals(source.replace("\r\n", "\n").replace('\r', '\n'), snap.content)
            assertEquals(sha(raw), snap.revision)
            assertTrue(snap.revision.matches(Regex("[0-9a-f]{64}")))
            val result = fs.writeTextWithRevision(root, path, snap.content, snap.revision)
            assertArrayEquals(raw, file.readBytes())
            assertEquals(attrs.fileKey(), Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
            assertEquals(attrs.lastModifiedTime().toMillis(), file.lastModified())
            assertEquals(snap.revision, result.revision)
            assertEquals(path, result.entry.path)
            assertEquals(raw.size.toLong(), result.entry.sizeBytes)
        }
    }

    @Test fun editsRetainUniformNewlinesBomBlankLinesAndExplicitTailChanges() {
        val root = tmp.newFolder()
        for (newline in listOf("\r\n", "\r", "\n")) for (withBom in listOf(false, true)) {
            val file = File(root, "edit.md")
            file.writeBytes((if (withBom) bom else byteArrayOf()) + "a${newline}${newline}b${newline}${newline}".toByteArray())
            var snapshot = fs.readTextSnapshot(root, file.name)
            val result = fs.writeTextWithRevision(root, file.name, "changed\n\nb\n\n", snapshot.revision)
            assertArrayEquals((if (withBom) bom else byteArrayOf()) + "changed${newline}${newline}b${newline}${newline}".toByteArray(), file.readBytes())
            assertEquals(sha(file.readBytes()), result.revision)
            assertNotEquals(snapshot.revision, result.revision)
            snapshot = fs.readTextSnapshot(root, file.name)
            fs.writeTextWithRevision(root, file.name, "changed\n\nb", snapshot.revision)
            assertArrayEquals((if (withBom) bom else byteArrayOf()) + "changed${newline}${newline}b".toByteArray(), file.readBytes())
            snapshot = fs.readTextSnapshot(root, file.name)
            fs.writeTextWithRevision(root, file.name, "changed\n\nb\n\n\n", snapshot.revision)
            assertArrayEquals((if (withBom) bom else byteArrayOf()) + "changed${newline}${newline}b${newline}${newline}${newline}".toByteArray(), file.readBytes())
        }
    }

    @Test fun mixedNewlinesPreserveUnchangedPrefixSuffixAndPositionallyReplacedLines() {
        val root = tmp.newFolder()
        val file = File(root, "mixed.md")
        val cases = listOf(
            "a\r\nb\rc\nd\r\n" to ("a\nB\nc\nd\n" to "a\r\nB\rc\nd\r\n"),
            "a\r\nb\rc\n" to ("a\ninsert\nb\nc\n" to "a\r\ninsert\r\nb\rc\n"),
            "a\r\nb\rc\n" to ("a\nc\n" to "a\r\nc\n"),
            "a\r\n\r\nb\r" to ("a\n\n\nb\n" to "a\r\n\r\n\r\nb\r"),
            "a\r\nb\rc" to ("A\nB\nC" to "A\r\nB\rC"),
        )
        for ((original, edit) in cases) {
            file.writeText(original)
            val snapshot = fs.readTextSnapshot(root, file.name)
            fs.writeTextWithRevision(root, file.name, edit.first, snapshot.revision)
            assertEquals(edit.second, file.readText())
        }
    }

    @Test fun malformedUtf8AndNulAreTypedAndNeverLossilyDecoded() {
        val root = tmp.newFolder()
        val file = File(root, "binary.md")
        val malformed = listOf(byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0xE2.toByte(), 0x82.toByte()), byteArrayOf(0xFF.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
        for (bytes in malformed) {
            file.writeBytes(bytes)
            val error = assertThrows(WorkspaceTextFormatException::class.java) { fs.readTextSnapshot(root, file.name) }
            assertEquals("FILE_NOT_UTF8", error.code)
            assertFalse(error.message!!.contains(root.path))
        }
        file.writeBytes(bom + "a\u0000b".toByteArray())
        assertEquals("FILE_NOT_TEXT", assertThrows(WorkspaceTextFormatException::class.java) { fs.readTextSnapshot(root, file.name) }.code)
        file.writeText("original")
        val snapshot = fs.readTextSnapshot(root, file.name)
        for (badText in listOf("x\u0000y", "\uD800")) {
            assertThrows(WorkspaceTextFormatException::class.java) { fs.writeTextWithRevision(root, file.name, badText, snapshot.revision) }
            assertEquals("original", file.readText())
        }
    }

    @Test fun boundsCheckStatStreamGrowthAndReencodedByteSizeIncludingBom() {
        val root = tmp.newFolder()
        val file = File(root, "bounded.md")
        val limited = WorkspaceFileSystem(WorkspaceConfig(maxReadBytes = 4, maxWriteBytes = 4))
        file.writeText("12345")
        assertEquals("CONTENT_TOO_LARGE", assertThrows(WorkspaceFileOperationException::class.java) { limited.readTextSnapshot(root, file.name) }.code)
        file.writeText("1")
        assertEquals("CONTENT_TOO_LARGE", assertThrows(WorkspaceFileOperationException::class.java) {
            readBoundedWorkspaceBytes(file, 4) { ByteArrayInputStream("12345".toByteArray()) }
        }.code)
        assertArrayEquals("1234".toByteArray(), readBoundedWorkspaceBytes(file, 4) { ByteArrayInputStream("1234".toByteArray()) })
        file.writeBytes(bom + "a".toByteArray())
        val snapshot = limited.readTextSnapshot(root, file.name)
        for (edit in listOf("ab", "😀", "中文")) {
            assertEquals("CONTENT_TOO_LARGE", assertThrows(WorkspaceFileOperationException::class.java) {
                limited.writeTextWithRevision(root, file.name, edit, snapshot.revision)
            }.code)
            assertArrayEquals(bom + "a".toByteArray(), file.readBytes())
        }
    }

    @Test fun revisionValidationAndExternalChangeDeletionDirectoryNeverRecreateOrOverwrite() {
        val root = tmp.newFolder()
        val file = File(root, "target.md").apply { writeText("old") }
        val snapshot = fs.readTextSnapshot(root, file.name)
        for (bad in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64), "a".repeat(65), " ${snapshot.revision}")) {
            assertEquals("INVALID_REVISION", assertThrows(WorkspaceFileOperationException::class.java) { fs.writeTextWithRevision(root, file.name, "edit", bad) }.code)
            assertEquals("old", file.readText())
        }
        file.writeText("external")
        assertEquals("FILE_REVISION_CONFLICT", assertThrows(WorkspaceFileRevisionConflictException::class.java) { fs.writeTextWithRevision(root, file.name, "edit", snapshot.revision) }.code)
        assertEquals("external", file.readText())
        file.delete()
        assertThrows(WorkspaceFileRevisionConflictException::class.java) { fs.writeTextWithRevision(root, file.name, "edit", snapshot.revision) }
        assertFalse(file.exists())
        file.mkdir()
        assertThrows(WorkspaceFileRevisionConflictException::class.java) { fs.writeTextWithRevision(root, file.name, "edit", snapshot.revision) }
        assertTrue(file.isDirectory)
    }

    @Test fun saveMayExceedReadLimitAndReturnsRawRevisionWithoutPostSaveRereadFailure() {
        val root = tmp.newFolder()
        val file = File(root, "large.md").apply { writeText("old") }
        val limited = WorkspaceFileSystem(WorkspaceConfig(maxReadBytes = 4, maxWriteBytes = 32))
        val snapshot = limited.readTextSnapshot(root, file.name)
        val result = limited.writeTextWithRevision(root, file.name, "larger than read limit", snapshot.revision)
        assertEquals("larger than read limit", file.readText())
        assertEquals(sha(file.readBytes()), result.revision)
        assertEquals(file.length(), result.entry.sizeBytes)
        assertEquals("CONTENT_TOO_LARGE", assertThrows(WorkspaceFileOperationException::class.java) { limited.readTextSnapshot(root, file.name) }.code)
        val next = limited.writeTextWithRevision(root, file.name, "next edit still over read limit", result.revision)
        assertEquals(sha(file.readBytes()), next.revision)
        assertEquals("next edit still over read limit", file.readText())
    }

    @Test fun successfulAtomicSavePreservesPosixPermissions() {
        val root = tmp.newFolder()
        val file = File(root, "mode.md").apply { writeText("old") }
        val mode = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(file.toPath(), mode)
        val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.PosixFileAttributes::class.java)
        val aclView = Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.AclFileAttributeView::class.java)
        val acl = aclView?.acl
        val snapshot = fs.readTextSnapshot(root, file.name)
        fs.writeTextWithRevision(root, file.name, "new", snapshot.revision)
        val saved = Files.readAttributes(file.toPath(), java.nio.file.attribute.PosixFileAttributes::class.java)
        assertEquals(attributes.owner(), saved.owner())
        assertEquals(attributes.group(), saved.group())
        if (acl != null) assertEquals(acl, Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.AclFileAttributeView::class.java).acl)
        assertEquals(mode, Files.getPosixFilePermissions(file.toPath()))
        assertEquals("new", file.readText())
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".l2s.edit.") })
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
