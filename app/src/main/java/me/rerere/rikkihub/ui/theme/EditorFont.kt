package me.rerere.rikkihub.ui.theme

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.EditorFontIds
import me.rerere.rikkihub.data.fonts.FontDownloadService
import me.rerere.rikkihub.data.fonts.resolveCustomFontFile
import org.koin.compose.koinInject
import java.io.File

/**
 * Font families backed by files (downloaded or imported fonts), shared by the chat and the
 * workspace editor. A 25 MB CJK font takes a noticeable moment to parse, so it is first parsed off
 * the main thread and the family is kept for the process; the key includes size and mtime so a
 * replaced file is reloaded.
 */
private object FileFontCache {
    private val loaded = HashMap<String, FontFamily>()

    fun key(file: File) = "${file.path}:${file.length()}:${file.lastModified()}"

    fun cached(key: String): FontFamily? = synchronized(loaded) { loaded[key] }

    fun load(file: File): FontFamily? {
        val key = key(file)
        cached(key)?.let { return it }
        // Parse once here (off the main thread) to reject broken files and warm the page cache, but
        // hand Compose a file Font: unlike a pre-built Typeface wrapper it lets Compose synthesize
        // bold and italic, which single-weight fonts (WenKai, HarmonyOS Regular) need for Markdown.
        return runCatching {
            android.graphics.Typeface.createFromFile(file)
            FontFamily(Font(file))
        }
            .onFailure { Log.w("FileFontCache", "cannot load ${file.name}", it) }
            .getOrNull()
            ?.also { family -> synchronized(loaded) { loaded[key] = family } }
    }
}

/** The family in [file], loaded on an IO thread; [fallback] until it is ready or when it cannot be read. */
@Composable
fun rememberFileFontFamily(file: File?, fallback: FontFamily = FontFamily.Default): FontFamily {
    val key = remember(file) { file?.takeIf { it.isFile }?.let(FileFontCache::key) }
    if (file == null || key == null) return fallback
    val family by produceState(initialValue = FileFontCache.cached(key) ?: fallback, key) {
        value = withContext(Dispatchers.IO) { FileFontCache.load(file) } ?: fallback
    }
    return family
}

/** The installed file of a downloadable font, recomposing when it is downloaded or deleted. */
@Composable
fun rememberDownloadedFontFile(id: String): File? {
    val downloads = koinInject<FontDownloadService>()
    val installed by downloads.installed.collectAsState()
    return remember(id, installed) { if (id in installed) downloads.fileOf(id) else null }
}

/** The family behind an editor font id; falls back to the system font until a file is available. */
@Composable
fun rememberEditorFontFamily(fontId: String, display: DisplaySetting): FontFamily = when (fontId) {
    EditorFontIds.SYSTEM -> FontFamily.Default
    EditorFontIds.SERIF -> FontFamily.Serif
    EditorFontIds.JETBRAINS_MONO -> JetbrainsMono
    EditorFontIds.CUSTOM -> {
        val context = LocalContext.current
        val file = remember(display.chatCustomFontPath) { resolveCustomFontFile(context.filesDir, display.chatCustomFontPath) }
        rememberFileFontFamily(file)
    }
    else -> rememberFileFontFamily(rememberDownloadedFontFile(fontId))
}
