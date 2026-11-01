package me.rerere.workspace

import com.github.junrar.Archive
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.WrongPasswordException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

/** Archive formats recognised by their leading bytes (never by file extension). */
enum class WorkspaceArchiveFormat {
    ZIP, SEVEN_ZIP, RAR, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ,
}

/** Stable extraction errors; `code` travels to the App UI and the Web API unchanged. */
class WorkspaceArchiveException(code: String, message: String, cause: Throwable? = null) :
    WorkspaceFileOperationException(code, message, cause) {
    companion object {
        const val ARCHIVE_UNSUPPORTED = "ARCHIVE_UNSUPPORTED"
        const val ARCHIVE_PASSWORD_REQUIRED = "ARCHIVE_PASSWORD_REQUIRED"
        const val ARCHIVE_WRONG_PASSWORD = "ARCHIVE_WRONG_PASSWORD"
        const val ARCHIVE_CORRUPT = "ARCHIVE_CORRUPT"
        const val ARCHIVE_ENCODING_UNSUPPORTED = "ARCHIVE_ENCODING_UNSUPPORTED"
    }
}

@kotlinx.serialization.Serializable
data class WorkspaceExtractProgress(
    val entries: Int,
    val bytes: Long,
    val currentEntry: String? = null,
)

@kotlinx.serialization.Serializable
data class WorkspaceExtractResult(
    /** Path (relative to the storage area) of the single item the archive landed as. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val format: WorkspaceArchiveFormat,
    val files: Int,
    val directories: Int,
    /** Entries left out: paths escaping the archive, links pointing outside it, unusable names. */
    val skipped: Int,
    /** Charset used for legacy (non-UTF-8-flagged) names, when the format stores raw bytes. */
    val nameEncoding: String? = null,
)

/**
 * Extracts zip / 7z / rar / tar(.gz/.bz2/.xz) / gz / bz2 / xz archives inside a storage area.
 *
 * - Output always lands next to the archive as one new item: a single top-level entry is placed
 *   directly, several are wrapped in a folder named after the archive. Existing names are never
 *   overwritten; clashes get " (n)".
 * - Extraction happens in a private staging directory ([stagingRoot], the workspace `tmp`) and is
 *   moved into place under the storage-root lock only at the end, so a long extraction neither
 *   blocks other file operations nor leaves half-written output behind on failure or cancel.
 * - Legacy zip/tar file names are raw bytes in some code page (Windows zips are usually GBK, which
 *   rootfs `unzip` shows as mojibake). Names are decoded with [ArchiveNameDecoder]; callers may
 *   force a charset with `nameEncoding`.
 */
class WorkspaceArchiveExtractor(
    private val fileSystem: WorkspaceFileSystem = WorkspaceFileSystem(),
    private val preferredLocale: () -> Locale = { Locale.getDefault() },
) {
    suspend fun extract(
        areaRoot: File,
        stagingRoot: File,
        archivePath: String,
        password: CharArray? = null,
        nameEncoding: String? = null,
        onProgress: (WorkspaceExtractProgress) -> Unit = {},
    ): WorkspaceExtractResult {
        val archive = fileSystem.resolve(areaRoot, archivePath)
        if (!archive.isFile) {
            throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_NOT_FOUND, "Archive not found: $archivePath")
        }
        val forcedCharset = nameEncoding?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }?.let { name ->
            runCatching { Charset.forName(name) }.getOrElse {
                throw WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_ENCODING_UNSUPPORTED, "Unsupported name encoding: $name")
            }
        }
        val format = detectFormat(archive)
        stagingRoot.mkdirs()
        val staging = File(stagingRoot, "extract-" + UUID.randomUUID().toString())
        require(staging.mkdirs()) { "Failed to create staging directory" }
        try {
            val sink = Sink(staging, onProgress)
            val decoder = ArchiveNameDecoder(forcedCharset, preferredLocale())
            val usedCharset = try {
                when (format) {
                    WorkspaceArchiveFormat.ZIP -> extractZip(archive, password, decoder, sink)
                    WorkspaceArchiveFormat.SEVEN_ZIP -> { extractSevenZip(archive, password, sink, containerName(archive.name)); null }
                    WorkspaceArchiveFormat.RAR -> { extractRar(archive, password, sink); null }
                    WorkspaceArchiveFormat.TAR -> archive.inputStream().use { extractTar(it, decoder, sink) }
                    WorkspaceArchiveFormat.TAR_GZ, WorkspaceArchiveFormat.TAR_BZ2, WorkspaceArchiveFormat.TAR_XZ ->
                        openDecompressed(archive, format).use { extractTar(it, decoder, sink) }
                    WorkspaceArchiveFormat.GZ, WorkspaceArchiveFormat.BZ2, WorkspaceArchiveFormat.XZ -> {
                        openDecompressed(archive, format).use { sink.file(listOf(singleFileName(archive.name)), it, archive.lastModified(), false) }
                        null
                    }
                }
            } catch (error: WorkspaceArchiveException) {
                throw error
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw classifyFailure(error, archive, format, password)
            }
            sink.finishLinks()
            currentCoroutineContext().ensureActive()
            val placed = place(areaRoot, archive, staging)
            return WorkspaceExtractResult(
                path = placed.path,
                name = placed.name,
                isDirectory = placed.isDirectory,
                format = format,
                files = sink.files,
                directories = sink.directories,
                skipped = sink.skipped,
                nameEncoding = usedCharset?.name(),
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- formats

    private suspend fun extractZip(archive: File, password: CharArray?, decoder: ArchiveNameDecoder, sink: Sink): Charset? {
        ZipFile(archive, password).use { zip ->
            // ISO-8859-1 maps bytes 1:1, so legacy names can be recovered as raw bytes and decoded here.
            zip.charset = StandardCharsets.ISO_8859_1
            val headers = try {
                zip.fileHeaders
            } catch (error: ZipException) {
                throw WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_CORRUPT, error.message ?: "Corrupt zip", error)
            }
            if (password == null && headers.any { it.isEncrypted }) {
                throw WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED, "Archive is password protected")
            }
            val legacyNames = headers.filter { !it.isFileNameUTF8Encoded && unicodePathOf(it) == null }
                .map { it.fileName.toByteArray(StandardCharsets.ISO_8859_1) }
            val charset = decoder.chooseFor(legacyNames)
            for (header in headers) {
                currentCoroutineContext().ensureActive()
                val name = when {
                    header.isFileNameUTF8Encoded -> header.fileName
                    else -> unicodePathOf(header) ?: decoder.decode(header.fileName.toByteArray(StandardCharsets.ISO_8859_1), charset)
                }
                val segments = sink.segments(name) ?: continue
                val mode = zipUnixMode(header)
                when {
                    header.isDirectory || name.endsWith("/") || name.endsWith("\\") -> sink.directory(segments, header.lastModifiedTimeEpoch)
                    mode != null && mode and S_IFMT == S_IFLNK -> {
                        val target = try {
                            zip.getInputStream(header).use { String(it.readBytes(), StandardCharsets.UTF_8) }
                        } catch (error: ZipException) {
                            throw zipFailure(error, header.isEncrypted)
                        }
                        sink.symlink(segments, target)
                    }
                    else -> try {
                        zip.getInputStream(header).use { input ->
                            sink.file(segments, input, header.lastModifiedTimeEpoch, mode != null && mode and EXECUTABLE_BITS != 0)
                        }
                    } catch (error: ZipException) {
                        throw zipFailure(error, header.isEncrypted)
                    }
                }
            }
            return charset.takeIf { legacyNames.isNotEmpty() }
        }
    }

    private fun zipFailure(error: ZipException, encrypted: Boolean): Throwable = when {
        error.type == ZipException.Type.WRONG_PASSWORD ->
            WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, "Wrong password", error)
        // ZipCrypto only checks one byte up front; a wrong password usually surfaces as a CRC mismatch.
        encrypted && error.type == ZipException.Type.CHECKSUM_MISMATCH ->
            WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, "Wrong password", error)
        error.type == ZipException.Type.UNSUPPORTED_ENCRYPTION || error.type == ZipException.Type.UNKNOWN_COMPRESSION_METHOD ->
            WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_UNSUPPORTED, error.message ?: "Unsupported zip entry", error)
        else -> WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_CORRUPT, error.message ?: "Corrupt zip", error)
    }

    private suspend fun extractSevenZip(archive: File, password: CharArray?, sink: Sink, unnamed: String) {
        val builder = SevenZFile.builder().setFile(archive)
        if (password != null) builder.setPassword(password)
        builder.get().use { sevenZ ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = sevenZ.nextEntry ?: break
                if (entry.isAntiItem) continue
                // Single-stream 7z files made from stdin carry no name.
                val segments = sink.segments(entry.name ?: unnamed) ?: continue
                val modified = if (entry.hasLastModifiedDate) entry.lastModifiedTime.toMillis() else null
                // 7-Zip keeps the unix mode in the high 16 bits when bit 0x8000 is set.
                val mode = if (entry.hasWindowsAttributes && entry.windowsAttributes and 0x8000 != 0) entry.windowsAttributes ushr 16 else null
                when {
                    entry.isDirectory -> sink.directory(segments, modified)
                    mode != null && mode and S_IFMT == S_IFLNK ->
                        sink.symlink(segments, String(readSevenZEntry(sevenZ, entry.size), StandardCharsets.UTF_8))
                    else -> sink.file(segments, SevenZEntryStream(sevenZ, if (entry.hasStream()) entry.size else 0L), modified, mode != null && mode and EXECUTABLE_BITS != 0)
                }
            }
        }
    }

    private fun readSevenZEntry(sevenZ: SevenZFile, size: Long): ByteArray =
        SevenZEntryStream(sevenZ, size).readBytes()

    private suspend fun extractRar(archive: File, password: CharArray?, sink: Sink) {
        Archive(archive, password?.let { String(it) }).use { rar ->
            if (password == null && (rar.isEncrypted || rar.isPasswordProtected)) {
                throw WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED, "Archive is password protected")
            }
            for (header in rar.fileHeaders) {
                currentCoroutineContext().ensureActive()
                if (header.isSplitBefore) continue
                if (header.redirection != null) { sink.skipped++; continue }
                val segments = sink.segments(header.fileName ?: continue) ?: continue
                val modified = header.mTime?.time
                if (header.isDirectory) {
                    sink.directory(segments, modified)
                    continue
                }
                if (password == null && header.isEncrypted) {
                    throw WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED, "Archive is password protected")
                }
                // extractFile (not getInputStream, which pipes through a thread) keeps password/CRC errors typed.
                sink.write(segments, modified, false) { out -> rar.extractFile(header, out) }
            }
        }
    }

    private suspend fun extractTar(input: InputStream, decoder: ArchiveNameDecoder, sink: Sink): Charset? {
        var legacy: Charset? = null
        TarArchiveInputStream(input, StandardCharsets.ISO_8859_1.name()).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val (name, charset) = decodeTarName(entry.name, decoder)
                if (charset != null) legacy = charset
                val segments = sink.segments(name) ?: continue
                val modified = entry.modTime?.time
                when {
                    entry.isDirectory -> sink.directory(segments, modified)
                    entry.isSymbolicLink -> sink.symlink(segments, decodeTarName(entry.linkName, decoder).first)
                    entry.isLink -> sink.hardlink(segments, decodeTarName(entry.linkName, decoder).first)
                    entry.isFile -> sink.file(segments, tar, modified, entry.mode and EXECUTABLE_BITS != 0, closeInput = false)
                    else -> sink.skipped++ // devices, fifos
                }
            }
        }
        return legacy
    }

    /** PAX names are already UTF-16 strings (chars above U+00FF); classic ustar names are raw bytes. */
    private fun decodeTarName(name: String, decoder: ArchiveNameDecoder): Pair<String, Charset?> {
        if (name.any { it.code > 0xFF }) return name to null
        val bytes = name.toByteArray(StandardCharsets.ISO_8859_1)
        if (bytes.all { it >= 0 }) return name to null
        val charset = decoder.chooseFor(listOf(bytes))
        return decoder.decode(bytes, charset) to charset
    }

    private fun openDecompressed(archive: File, format: WorkspaceArchiveFormat): InputStream {
        val raw = BufferedInputStream(archive.inputStream(), COPY_BUFFER_SIZE)
        return try {
            when (format) {
                WorkspaceArchiveFormat.TAR_GZ, WorkspaceArchiveFormat.GZ -> GzipCompressorInputStream(raw, true)
                WorkspaceArchiveFormat.TAR_BZ2, WorkspaceArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw, true)
                WorkspaceArchiveFormat.TAR_XZ, WorkspaceArchiveFormat.XZ -> XZCompressorInputStream(raw, true)
                else -> error("Not a compressed stream: $format")
            }
        } catch (error: Throwable) {
            raw.close()
            throw error
        }
    }

    // ---------------------------------------------------------------- failures

    private fun classifyFailure(error: Throwable, archive: File, format: WorkspaceArchiveFormat, password: CharArray?): Throwable {
        val passwordProblem = when (error) {
            is PasswordRequiredException -> if (password == null) WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED else WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD
            // RAR5 with encrypted headers cannot even be listed without one.
            is WrongPasswordException, is UnsupportedRarEncryptedException -> if (password == null) WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED else WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD
            is CrcErrorException -> if (password != null) WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD else null
            else -> null
        }
        if (passwordProblem != null) return WorkspaceArchiveException(passwordProblem, error.message ?: passwordProblem, error)
        // A wrong 7z password just yields garbage that fails a checksum or the decoder; ask the
        // archive whether it needs a password at all before calling it corrupt.
        if (password != null && format == WorkspaceArchiveFormat.SEVEN_ZIP && sevenZipNeedsPassword(archive)) {
            return WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, "Wrong password", error)
        }
        if (error is IOException && error.message.orEmpty().let { it.contains("ENOSPC") || it.contains("No space left") }) {
            return WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_IO_ERROR, error.message ?: "No space left", error)
        }
        if (error is RarException || error is IOException || error is IllegalStateException || error is IllegalArgumentException) {
            return WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_CORRUPT, error.message ?: "Corrupt archive", error)
        }
        return error
    }

    private fun sevenZipNeedsPassword(archive: File): Boolean = try {
        SevenZFile.builder().setFile(archive).get().use { sevenZ ->
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                if (entry.hasStream() && entry.size > 0) {
                    SevenZEntryStream(sevenZ, minOf(entry.size, 1L)).readBytes()
                    break
                }
            }
        }
        false
    } catch (_: PasswordRequiredException) {
        true
    } catch (_: Throwable) {
        false
    }

    // ---------------------------------------------------------------- placement

    private class Placed(val path: String, val name: String, val isDirectory: Boolean)

    private fun place(areaRoot: File, archive: File, staging: File): Placed =
        WorkspaceFileMutations.withLock(areaRoot) {
            val rootCanonical = areaRoot.canonicalFile
            val parent = archive.canonicalFile.parentFile ?: rootCanonical
            val children = staging.listFiles().orEmpty()
            val (source, baseName) = when (children.size) {
                1 -> children[0] to children[0].name
                else -> staging to containerName(archive.name)
            }
            val isDirectory = Files.isDirectory(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            var name = baseName
            var n = 1
            while (Files.exists(File(parent, name).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                name = WorkspaceNames.fitConflictName(baseName, n, isDirectory)
                n++
            }
            val target = File(parent, name)
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                // Different filesystem (not expected: staging lives in the same workspace dir).
                Files.move(source.toPath(), target.toPath())
            }
            if (source == staging) staging.mkdirs() // keep the finally{} cleanup harmless
            val relative = target.relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
            Placed(relative, name, isDirectory)
        }

    // ---------------------------------------------------------------- sink

    /**
     * Writes entries under the staging root. Links are created only after all regular entries,
     * so no write can ever pass through a link; link targets must stay inside the archive.
     */
    private class Sink(
        private val root: File,
        private val onProgress: (WorkspaceExtractProgress) -> Unit,
    ) {
        var files = 0
        var directories = 0
        var skipped = 0
        private var entries = 0
        private var bytes = 0L
        private val links = ArrayList<Triple<List<String>, String, Boolean>>()
        private val createdDirs = HashSet<String>()

        /** Archive path → safe segments; null (and counted as skipped) when it would leave the root. */
        fun segments(raw: String): List<String>? {
            val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) {
                if (parts.isNotEmpty()) skipped++
                return null
            }
            return try {
                parts.map { WorkspaceNames.sanitize(it) }
            } catch (_: IllegalArgumentException) {
                skipped++
                null
            }
        }

        fun directory(segments: List<String>, modified: Long?) {
            val dir = ensureDir(segments) ?: return
            modified?.let { dir.setLastModified(it) }
            tick(segments)
        }

        suspend fun file(segments: List<String>, input: InputStream, modified: Long?, executable: Boolean, closeInput: Boolean = true) {
            try {
                val context = currentCoroutineContext()
                write(segments, modified, executable) { out ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                    }
                }
            } finally {
                if (closeInput) input.close()
            }
        }

        fun write(segments: List<String>, modified: Long?, executable: Boolean, writer: (java.io.OutputStream) -> Unit) {
            val parent = ensureDir(segments.dropLast(1)) ?: return
            val target = File(parent, segments.last())
            if (target.isDirectory) { skipped++; return }
            target.outputStream().use(writer)
            if (executable) target.setExecutable(true, false)
            modified?.takeIf { it > 0 }?.let { target.setLastModified(it) }
            files++
            tick(segments)
        }

        fun symlink(segments: List<String>, target: String) {
            links += Triple(segments, target, false)
        }

        fun hardlink(segments: List<String>, target: String) {
            links += Triple(segments, target, true)
        }

        fun finishLinks() {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            for ((segments, rawTarget, hard) in links) {
                val parent = ensureDir(segments.dropLast(1)) ?: continue
                val linkFile = File(parent, segments.last())
                if (Files.exists(linkFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { skipped++; continue }
                val target = rawTarget.replace('\\', '/')
                if (hard) {
                    // Tar hard links name another member by its archive path.
                    val sourceSegments = segments(target) ?: continue
                    val source = File(root, sourceSegments.joinToString("/"))
                    if (!source.isFile || Files.isSymbolicLink(source.toPath())) { skipped++; continue }
                    source.copyTo(linkFile)
                    files++
                    continue
                }
                val resolved = if (target.startsWith("/")) null else parent.toPath().toAbsolutePath().resolve(target).normalize()
                if (target.isEmpty() || resolved == null || !resolved.startsWith(rootPath)) { skipped++; continue }
                try {
                    Files.createSymbolicLink(linkFile.toPath(), Paths.get(target))
                    files++
                } catch (_: Exception) {
                    skipped++
                }
            }
        }

        private fun ensureDir(segments: List<String>): File? {
            var dir = root
            for ((index, segment) in segments.withIndex()) {
                dir = File(dir, segment)
                val key = segments.subList(0, index + 1).joinToString("/")
                if (key in createdDirs) continue
                if (dir.isFile) { skipped++; return null }
                if (!dir.isDirectory) {
                    if (!dir.mkdir()) throw IOException("Failed to create directory: $key")
                    directories++
                }
                createdDirs += key
            }
            return dir
        }

        private fun tick(segments: List<String>) {
            entries++
            onProgress(WorkspaceExtractProgress(entries, bytes, segments.joinToString("/")))
        }
    }

    /** Bounded view over the current 7z entry (SevenZFile reads the active entry directly). */
    private class SevenZEntryStream(private val sevenZ: SevenZFile, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = sevenZ.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val read = sevenZ.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val S_IFMT = 0xF000
        private const val S_IFLNK = 0xA000
        private const val EXECUTABLE_BITS = 0x49 // 0111
        private const val UNIX_HOST = 3

        private val ARCHIVE_SUFFIXES = listOf(
            ".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz",
            ".zip", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz",
        )
        private val PART_SUFFIX = Regex("\\.part0*1$", RegexOption.IGNORE_CASE)

        /** UI hint only (what to offer "Extract" on); extraction itself sniffs the content. */
        fun isArchiveName(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return ARCHIVE_SUFFIXES.any { lower.endsWith(it) && lower.length > it.length }
        }

        /** "photos.tar.gz" → "photos"; "a.part1.rar" → "a"; falls back to "archive". */
        fun containerName(archiveName: String): String {
            val lower = archiveName.lowercase(Locale.ROOT)
            val suffix = ARCHIVE_SUFFIXES.firstOrNull { lower.endsWith(it) && lower.length > it.length }
            var base = if (suffix != null) archiveName.dropLast(suffix.length) else archiveName.substringBeforeLast('.', archiveName)
            base = base.replace(PART_SUFFIX, "")
            return runCatching { WorkspaceNames.sanitize(base) }.getOrDefault("archive")
        }

        /** "data.json.gz" → "data.json"; a name without a compression suffix gets "_extracted". */
        internal fun singleFileName(archiveName: String): String {
            val lower = archiveName.lowercase(Locale.ROOT)
            val suffix = listOf(".gz", ".bz2", ".xz").firstOrNull { lower.endsWith(it) && lower.length > it.length }
            val base = if (suffix != null) archiveName.dropLast(suffix.length) else archiveName + "_extracted"
            return runCatching { WorkspaceNames.sanitize(base) }.getOrDefault("extracted")
        }

        fun detectFormat(file: File): WorkspaceArchiveFormat {
            val head = ByteArray(512)
            val read = file.inputStream().use { readFully(it, head) }
            fun at(offset: Int, vararg bytes: Int) =
                read >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
            return when {
                at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) || at(0, 0x50, 0x4B, 0x07, 0x08) -> WorkspaceArchiveFormat.ZIP
                at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> WorkspaceArchiveFormat.SEVEN_ZIP
                at(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) || at(0, 0x52, 0x45, 0x7E, 0x5E) -> WorkspaceArchiveFormat.RAR
                at(0, 0x1F, 0x8B) -> if (innerIsTar(file, WorkspaceArchiveFormat.GZ)) WorkspaceArchiveFormat.TAR_GZ else WorkspaceArchiveFormat.GZ
                at(0, 0x42, 0x5A, 0x68) -> if (innerIsTar(file, WorkspaceArchiveFormat.BZ2)) WorkspaceArchiveFormat.TAR_BZ2 else WorkspaceArchiveFormat.BZ2
                at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> if (innerIsTar(file, WorkspaceArchiveFormat.XZ)) WorkspaceArchiveFormat.TAR_XZ else WorkspaceArchiveFormat.XZ
                looksLikeTar(head, read) -> WorkspaceArchiveFormat.TAR
                at(0, 0x28, 0xB5, 0x2F, 0xFD) -> throw WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_UNSUPPORTED, "zstd archives are not supported")
                else -> throw WorkspaceArchiveException(WorkspaceArchiveException.ARCHIVE_UNSUPPORTED, "Unsupported archive format")
            }
        }

        private fun innerIsTar(file: File, format: WorkspaceArchiveFormat): Boolean = try {
            val raw = BufferedInputStream(file.inputStream())
            val stream: InputStream = when (format) {
                WorkspaceArchiveFormat.GZ -> GzipCompressorInputStream(raw, true)
                WorkspaceArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw, true)
                else -> XZCompressorInputStream(raw, true)
            }
            stream.use {
                val head = ByteArray(512)
                val read = readFully(it, head)
                looksLikeTar(head, read)
            }
        } catch (_: Exception) {
            false
        }

        /** ustar/GNU magic, or a pre-POSIX header whose checksum adds up. */
        private fun looksLikeTar(head: ByteArray, read: Int): Boolean {
            if (read < 512) return false
            if (TarArchiveInputStream.matches(head, read)) return true
            val stored = String(head, 148, 8, StandardCharsets.US_ASCII).trim { it <= ' ' }.toIntOrNull(8) ?: return false
            var sum = 0
            for (i in 0 until 512) sum += if (i in 148 until 156) 0x20 else head[i].toInt() and 0xFF
            return head[0] != 0.toByte() && sum == stored
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            return total
        }

        private fun zipUnixMode(header: net.lingala.zip4j.model.FileHeader): Int? {
            if (header.versionMadeBy shr 8 != UNIX_HOST) return null
            val attrs = header.externalFileAttributes ?: return null
            if (attrs.size < 4) return null
            val mode = (attrs[2].toInt() and 0xFF) or ((attrs[3].toInt() and 0xFF) shl 8)
            return mode.takeIf { it != 0 }
        }

        /** Info-ZIP Unicode Path extra field (0x7075): UTF-8 name, valid only if its CRC matches the raw name. */
        private fun unicodePathOf(header: net.lingala.zip4j.model.FileHeader): String? {
            val record = header.extraDataRecords?.firstOrNull { it.header == 0x7075L } ?: return null
            val data = record.data ?: return null
            if (data.size < 6 || data[0].toInt() != 1) return null
            val crc = (data[1].toLong() and 0xFF) or ((data[2].toLong() and 0xFF) shl 8) or
                ((data[3].toLong() and 0xFF) shl 16) or ((data[4].toLong() and 0xFF) shl 24)
            val raw = header.fileName.toByteArray(StandardCharsets.ISO_8859_1)
            if (CRC32().apply { update(raw) }.value != crc) return null
            return String(data, 5, data.size - 5, StandardCharsets.UTF_8)
        }
    }
}

/**
 * Decodes legacy archive names. Valid UTF-8 wins (many tools write UTF-8 without setting the
 * flag); otherwise the first East-Asian code page that decodes every name strictly, with the
 * user's locale tried first; CP437 (the zip spec default, always decodable) last.
 */
internal class ArchiveNameDecoder(private val forced: Charset?, locale: Locale) {
    private val candidates: List<Charset> = buildList {
        add(StandardCharsets.UTF_8)
        val preferred = when {
            locale.language == "zh" && (locale.country == "TW" || locale.country == "HK" || locale.script == "Hant") -> "Big5"
            locale.language == "ja" -> "Shift_JIS"
            locale.language == "ko" -> "EUC-KR"
            else -> "GB18030"
        }
        (listOf(preferred) + listOf("GB18030", "Big5", "Shift_JIS", "EUC-KR")).distinct()
            .mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }
            .forEach { add(it) }
        add(runCatching { Charset.forName("IBM437") }.getOrDefault(StandardCharsets.ISO_8859_1))
    }

    fun chooseFor(names: List<ByteArray>): Charset {
        forced?.let { return it }
        val nonAscii = names.filter { bytes -> bytes.any { it < 0 } }
        if (nonAscii.isEmpty()) return StandardCharsets.UTF_8
        return candidates.firstOrNull { charset -> nonAscii.all { decodesStrictly(it, charset) } } ?: candidates.last()
    }

    fun decode(bytes: ByteArray, charset: Charset): String = String(bytes, charset)

    private fun decodesStrictly(bytes: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
