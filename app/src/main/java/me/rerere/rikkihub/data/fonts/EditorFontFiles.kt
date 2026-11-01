package me.rerere.rikkihub.data.fonts

import android.content.Context
import me.rerere.rikkihub.BuildConfig
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.EditorFontIds
import java.io.File
import java.io.InputStream

/** A font file the Web can fetch; [version] changes whenever the bytes do (it keys the browser cache). */
class EditorFontFile(val version: String, val length: Long, val open: () -> InputStream)

/**
 * Font files behind the editor font ids, for the Web: the bundled JetBrains Mono, the
 * downloaded fonts, and the imported custom font. System and serif have no file (the browser's own).
 */
class EditorFontFiles(
    private val filesDir: File,
    val downloads: FontDownloadService,
    private val bundledVersion: String,
    private val openBundled: (id: String) -> InputStream?,
) {
    private val bundledLengths = HashMap<String, Long>()

    fun resolve(id: String, display: DisplaySetting): EditorFontFile? = when (id) {
        EditorFontIds.JETBRAINS_MONO -> bundledLength(id)?.let { length ->
            EditorFontFile("b$bundledVersion", length) { openBundled(id) ?: error("Bundled font missing: $id") }
        }
        EditorFontIds.CUSTOM -> resolveCustomFontFile(filesDir, display.chatCustomFontPath)?.takeIf { it.isFile }?.let { file ->
            EditorFontFile("c" + file.name.hashCode().toUInt().toString(16) + "-" + file.length(), file.length()) { file.inputStream() }
        }
        else -> downloads.font(id)?.let { font ->
            downloads.fileOf(id)?.let { file -> EditorFontFile(font.sha256.take(16), file.length()) { file.inputStream() } }
        }
    }

    private fun bundledLength(id: String): Long? = synchronized(bundledLengths) {
        bundledLengths[id] ?: openBundled(id)?.use { input ->
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            total
        }?.also { bundledLengths[id] = it }
    }

    companion object {
        fun forContext(context: Context, downloads: FontDownloadService) = EditorFontFiles(
            filesDir = context.filesDir,
            downloads = downloads,
            bundledVersion = BuildConfig.VERSION_CODE.toString(),
        ) { id -> if (id == EditorFontIds.JETBRAINS_MONO) context.resources.openRawResource(R.font.jetbrains_mono) else null }
    }
}

/** The imported custom font under [filesDir] (`fonts/chat_font.*`), refusing paths that leave it. */
fun resolveCustomFontFile(filesDir: File, relativePath: String): File? {
    if (relativePath.isBlank()) return null
    val root = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(root, relativePath).canonicalFile }.getOrNull() ?: return null
    return file.takeIf { it.path.startsWith("${root.path}${File.separator}") }
}
