package me.rerere.rikkihub.ui.components.ai

import java.io.File
import java.io.InputStream
import java.io.Serializable
import java.nio.file.Files
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only small owned-file metadata enters saved state, never image bytes or callbacks. */
internal data class CropPendingFiles(
    val directoryPath: String,
    val ownershipToken: String,
    val encoding: CropOutputEncoding? = null,
) : Serializable

private val CROP_RESULT_NAMES = setOf("result.jpg", "result.png")
private val CAMERA_NAME = Regex("camera_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg")

internal fun claimCropFiles(root: File): CropPendingFiles {
    check(!Files.isSymbolicLink(root.toPath())) { "Invalid crop cache directory" }
    check(root.isDirectory || root.mkdirs()) { "Cannot create crop cache directory" }
    val directory = Files.createTempDirectory(root.toPath(), "crop_").toFile()
    val pending = CropPendingFiles(directory.absolutePath, Uuid.random().toString())
    try {
        File(directory, ".lease").writeText(pending.ownershipToken)
        return pending
    } catch (error: Exception) {
        File(directory, ".lease").delete()
        directory.delete()
        throw error
    }
}

private fun ownedCropDirectory(root: File, pending: CropPendingFiles): File? {
    val directory = File(pending.directoryPath)
    if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(directory.toPath()) ||
        !directory.name.startsWith("crop_") || directory.canonicalFile.parentFile != root.canonicalFile
    ) return null
    val marker = File(directory, ".lease")
    if (Files.isSymbolicLink(marker.toPath()) || !marker.isFile) return null
    val expected = pending.ownershipToken.toByteArray()
    // Bound the marker read too: malformed restored state cannot make us read arbitrary files.
    if (expected.size > 4096 || marker.length() != expected.size.toLong()) return null
    val actual = marker.inputStream().use { input ->
        val bytes = ByteArray(expected.size + 1)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read == 0) {
                val one = input.read()
                if (one < 0) break
                bytes[count++] = one.toByte()
            } else count += read
        }
        bytes.copyOf(count)
    }
    return directory.takeIf { actual.contentEquals(expected) }
}

internal fun cropLeaseFile(root: File, pending: CropPendingFiles, name: String): File {
    require(name in CROP_RESULT_NAMES)
    val directory = ownedCropDirectory(root, pending) ?: error("Invalid crop file ownership")
    val file = File(directory, name)
    check(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == directory.canonicalFile) {
        "Invalid crop file path"
    }
    return file
}

internal fun ownedCameraPath(cacheDir: File, file: File?): String? {
    if (file == null || !CAMERA_NAME.matches(file.name) || Files.isSymbolicLink(file.toPath())) return null
    return file.canonicalPath.takeIf { file.canonicalFile.parentFile == cacheDir.canonicalFile }
}

internal fun deleteOwnedCameraFile(cacheDir: File, path: String?) {
    if (path == null) return
    try {
        val file = File(path)
        if (ownedCameraPath(cacheDir, file) != null) file.delete()
    } catch (_: Exception) {
        // Best effort; cleanup must not mask the original failure/cancellation.
    }
}

/** No recursive deletion and no external/input URI deletion, even with malformed restored state. */
internal fun cleanupCropFiles(root: File, pending: CropPendingFiles) {
    val directory = try { ownedCropDirectory(root, pending) } catch (_: Exception) { null } ?: return
    CROP_RESULT_NAMES.forEach { name ->
        try {
            val file = File(directory, name)
            if (!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == directory.canonicalFile) {
                file.delete()
            }
        } catch (_: Exception) { }
    }
    try {
        File(directory, ".lease").delete()
        directory.delete() // Only removes an empty owned directory; unknown files are left alone.
    } catch (_: Exception) { }
}

/** Header sniffing names a file\'s real type; it is not an opacity proof. */
internal fun cropInputExtension(input: InputStream): String {
    val bytes = ByteArray(16)
    var count = 0
    while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) {
            val one = input.read()
            if (one < 0) break
            bytes[count++] = one.toByte()
        } else count += read
    }
    fun starts(vararg prefix: Int): Boolean = count >= prefix.size &&
        prefix.indices.all { bytes[it].toInt() and 255 == prefix[it] }
    return when {
        starts(0xff, 0xd8, 0xff) -> "jpg"
        starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
        starts(0x47, 0x49, 0x46, 0x38) -> "gif"
        starts(0x42, 0x4d) -> "bmp"
        starts(0x52, 0x49, 0x46, 0x46) && count >= 12 &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
        count >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" ->
            when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
                "avif", "avis" -> "avif"
                "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1", "heif" -> "heic"
                else -> "img"
            }
        else -> "img"
    }
}

internal fun verifyCropOutput(file: File, encoding: CropOutputEncoding) {
    check(file.isFile && file.length() > 0) { "Cropped image is missing" }
    val extension = file.inputStream().use(::cropInputExtension)
    check(extension == encoding.extension) { "Crop output encoding does not match its file type" }
}

/** Await the real consumer, then clean in finally. Row/activity cancellation cannot delete early. */
internal suspend fun consumeCropOutput(
    root: File,
    pending: CropPendingFiles,
    consume: suspend (File) -> Unit,
) {
    try {
        currentCoroutineContext().ensureActive()
        val encoding = pending.encoding ?: error("Crop is not prepared")
        val output = withContext(Dispatchers.IO) {
            cropLeaseFile(root, pending, "result.${encoding.extension}").also { verifyCropOutput(it, encoding) }
        }
        consume(output)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            cleanupCropFiles(root, pending)
        }
    }
}
