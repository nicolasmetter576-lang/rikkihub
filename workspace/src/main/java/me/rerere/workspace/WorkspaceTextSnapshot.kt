package me.rerere.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileOwnerAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.security.MessageDigest
import java.util.UUID

/**
 * Editor source, not rendered/AST-serialized Markdown. One leading UTF-8 BOM is
 * omitted and CRLF / CR are normalized to LF. Revision always hashes RAW bytes.
 * writeTextWithRevision reconstructs source separators and BOM from the matching
 * latest file; an unchanged normalized content is a byte-for-byte no-op.
 */
@kotlinx.serialization.Serializable
data class WorkspaceTextSnapshot(val content: String, val revision: String)
@kotlinx.serialization.Serializable
data class WorkspaceTextWriteResult(val entry: WorkspaceFileEntry, val revision: String)

class WorkspaceFileRevisionConflictException : WorkspaceFileOperationException(
    WorkspaceFileOperationException.FILE_REVISION_CONFLICT,
    "File revision conflict",
)

class WorkspaceTextFormatException(code: String) : WorkspaceFileOperationException(
    code,
    if (code == WorkspaceFileOperationException.FILE_NOT_UTF8) "File is not valid UTF-8" else "File is not text",
)

internal fun workspaceRevision(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun validateWorkspaceRevision(revision: String) {
    if (!revision.matches(Regex("[0-9a-f]{64}"))) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.INVALID_REVISION, "Invalid file revision")
    }
}

/** Stat and stream bounds: never accept growth beyond the configured byte limit. */
internal fun readBoundedWorkspaceBytes(
    file: File,
    maxBytes: Long,
    open: (File) -> InputStream = { Files.newInputStream(it.toPath()) },
): ByteArray {
    if (!file.exists()) throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_NOT_FOUND, "File does not exist")
    if (!file.isFile) throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_NOT_FILE, "Path is not a file")
    if (file.length() > maxBytes) throw textTooLarge()
    val out = ByteArrayOutputStream()
    open(file).use { input ->
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            // Read no more than the remaining allowance plus one sentinel byte.
            val allowance = (maxBytes - count + 1).coerceAtMost(buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, allowance)
            if (read < 0) break
            if (read == 0) continue
            count += read
            if (count > maxBytes) throw textTooLarge()
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}

private fun textTooLarge() = WorkspaceFileOperationException(
    WorkspaceFileOperationException.CONTENT_TOO_LARGE, "Text content is too large",
)

internal class WorkspaceSourceText(bytes: ByteArray) {
    private val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    private val source: String = try {
        UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, if (bom) 3 else 0, bytes.size - if (bom) 3 else 0)).toString()
    } catch (_: CharacterCodingException) {
        throw WorkspaceTextFormatException(WorkspaceFileOperationException.FILE_NOT_UTF8)
    }
    init {
        if ('\u0000' in source) throw WorkspaceTextFormatException(WorkspaceFileOperationException.FILE_NOT_TEXT)
    }
    val content: String = normalize(source)

    /**
     * Never add/remove terminal newlines or blank lines: the submitted source is
     * authoritative about their count. For mixed endings, retain separators on
     * unchanged prefix/suffix lines and positionally replaced middle lines; new
     * middle lines use the source's most frequent separator (first wins ties).
     * This bounded linear policy is not a claim to infer arbitrary edit history.
     * Uniform CRLF/CR/LF sources retain their convention everywhere. Submitted
     * CRLF/CR are normalized too, as editors operate on the LF snapshot contract.
     */
    fun encode(text: String, maxBytes: Long): ByteArray {
        if ('\u0000' in text) throw WorkspaceTextFormatException(WorkspaceFileOperationException.FILE_NOT_TEXT)
        if (text.length.toLong() > maxBytes) throw textTooLarge()
        val normalized = normalize(text)
        val originalLines = content.split('\n')
        val newLines = normalized.split('\n')
        val separators = Regex("\r\n|\r|\n").findAll(source).map { it.value }.toList()
        val preferred = separators.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "\n"
        var prefix = 0
        while (prefix < minOf(originalLines.size, newLines.size) && originalLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(originalLines.size, newLines.size) - prefix &&
            originalLines[originalLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]) suffix++
        val restored = buildString {
            for (index in newLines.indices) {
                append(newLines[index])
                if (index == newLines.lastIndex) continue
                val oldIndex = when {
                    index < prefix -> index
                    index >= newLines.size - suffix -> originalLines.size - (newLines.size - index)
                    index - prefix < originalLines.size - prefix - suffix -> index
                    else -> -1
                }
                append(separators.getOrNull(oldIndex) ?: preferred)
            }
        }
        val encoded = try {
            UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(restored))
        } catch (_: CharacterCodingException) {
            throw WorkspaceTextFormatException(WorkspaceFileOperationException.FILE_NOT_UTF8)
        }
        if (encoded.remaining().toLong() + (if (bom) 3L else 0L) > maxBytes) throw textTooLarge()
        return ByteArray(encoded.remaining() + (if (bom) 3 else 0)).also {
            if (bom) { it[0] = 0xEF.toByte(); it[1] = 0xBB.toByte(); it[2] = 0xBF.toByte() }
            encoded.get(it, if (bom) 3 else 0, encoded.remaining())
        }
    }

    companion object {
        private fun normalize(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')
    }
}

/** Internal deterministic failure seams; production never supplies fallback IO. */
internal open class WorkspaceTextSaveIo {
    open fun openTemp(path: Path): FileChannel = FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    open fun preserveMetadata(target: Path, temp: Path) { preserveWorkspaceTextMetadata(target, temp) }
    open fun force(channel: FileChannel) { channel.force(true) }
    open fun beforeRename() {}
    open fun move(temp: Path, target: Path) { Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING) }
}

internal fun preserveWorkspaceTextMetadata(target: Path, temp: Path) {
    val originalPosix = Files.getFileAttributeView(target, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    if (originalPosix != null) {
        val original = originalPosix.readAttributes()
        val tempView = Files.getFileAttributeView(temp, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw IOException("Cannot preserve file permissions")
        val current = tempView.readAttributes()
        // Avoid unnecessary privileged chown/chgrp; do not silently drop either.
        if (current.owner() != original.owner()) tempView.setOwner(original.owner())
        if (current.group() != original.group()) tempView.setGroup(original.group())
        // chown can clear permission bits, so permissions are copied afterwards.
        tempView.setPermissions(original.permissions())
    } else {
        val ownerView = Files.getFileAttributeView(target, FileOwnerAttributeView::class.java, NOFOLLOW_LINKS)
        if (ownerView != null) {
            val tempView = Files.getFileAttributeView(temp, FileOwnerAttributeView::class.java, NOFOLLOW_LINKS)
                ?: throw IOException("Cannot preserve file owner")
            val owner = ownerView.owner
            if (tempView.owner != owner) tempView.setOwner(owner)
        }
    }
    val originalAcl = Files.getFileAttributeView(target, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
    if (originalAcl != null) {
        val tempView = Files.getFileAttributeView(temp, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw IOException("Cannot preserve file ACL")
        val acl = originalAcl.acl
        if (tempView.acl != acl) tempView.acl = acl
    }
}

/**
 * Same-directory exclusive temporary file, fsync, then atomic replacement ONLY.
 * No truncate/copy fallback. POSIX permissions/owner/group and ACLs are copied
 * where the filesystem exposes those views; failed preservation aborts the save.
 * Xattrs, historical timestamps and directory-fsync/crash durability are not promised.
 * Recheck CAS immediately before rename, but external processes can still change
 * the target after that check. This is not a perfect filesystem transaction.
 * Cleanup checks the claimed inode's fileKey; if unavailable, leave the temp
 * rather than risk unlinking somebody else's replacement. External hostile
 * swaps still have a check/unlink race; pathname APIs are not an FD sandbox.
 */
internal fun atomicWorkspaceTextSave(
    target: Path,
    bytes: ByteArray,
    io: WorkspaceTextSaveIo = WorkspaceTextSaveIo(),
    verifyRevision: () -> Unit,
) {
    val temp = target.resolveSibling(".l2s.edit.${UUID.randomUUID()}")
    var ownedKey: Any? = null
    var claimed = false
    try {
        io.openTemp(temp).use { channel ->
            claimed = true
            ownedKey = Files.readAttributes(temp, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            io.preserveMetadata(target, temp)
            io.force(channel)
        }
        io.beforeRename()
        verifyRevision()
        if (ownedKey != null && Files.readAttributes(temp, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != ownedKey) {
            throw IOException("Temporary file ownership changed")
        }
        io.move(temp, target)
    } catch (error: WorkspaceFileOperationException) {
        throw error
    } catch (_: AtomicMoveNotSupportedException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.ATOMIC_SAVE_UNSUPPORTED, "Atomic file replacement is not supported")
    } catch (_: UnsupportedOperationException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.ATOMIC_SAVE_UNSUPPORTED, "Atomic file replacement is not supported")
    } catch (_: NoSuchFileException) {
        // A deleted target is a conflict, never a recreate request.
        verifyRevision()
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_IO_ERROR, "File save failed")
    } catch (_: AccessDeniedException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_PERMISSION_DENIED, "File access denied")
    } catch (_: IOException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_IO_ERROR, "File save failed")
    } catch (_: SecurityException) {
        throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_PERMISSION_DENIED, "File access denied")
    } finally {
        if (claimed && ownedKey != null) {
            runCatching {
                val actual = Files.readAttributes(temp, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (actual.fileKey() == ownedKey) Files.delete(temp)
            }
        }
    }
}
