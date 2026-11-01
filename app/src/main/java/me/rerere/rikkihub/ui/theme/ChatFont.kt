package me.rerere.rikkihub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import me.rerere.rikkihub.data.datastore.ChatFontFamily
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.EditorFontIds
import me.rerere.rikkihub.data.fonts.downloadableFont

val LocalChatFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun ChatFontProvider(
    displaySetting: DisplaySetting,
    content: @Composable () -> Unit,
) {
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    CompositionLocalProvider(LocalChatFontFamily provides chatFontFamily) {
        content()
    }
}

/** Downloadable and custom fonts load off the main thread; the system font shows until then. */
@Composable
fun rememberChatFontFamily(displaySetting: DisplaySetting): FontFamily {
    val family = displaySetting.chatFontFamily
    val downloadable = family.downloadableFont
    return when {
        downloadable != null -> rememberFileFontFamily(rememberDownloadedFontFile(downloadable.id))
        family == ChatFontFamily.CUSTOM -> rememberEditorFontFamily(EditorFontIds.CUSTOM, displaySetting)
        family == ChatFontFamily.SERIF -> FontFamily.Serif
        family == ChatFontFamily.MONOSPACE -> FontFamily.Monospace
        else -> FontFamily.Default
    }
}
