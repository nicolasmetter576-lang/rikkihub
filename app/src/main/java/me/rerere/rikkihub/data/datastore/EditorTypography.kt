package me.rerere.rikkihub.data.datastore

import kotlinx.serialization.Serializable

/**
 * Stable ids of the fonts the workspace editor can use. The Web copy lives in
 * `web-settings/web-ui/lib/editor-typography.ts` (`EDITOR_FONT_IDS`); keep both lists in sync.
 */
object EditorFontIds {
    const val SYSTEM = "system"
    const val SERIF = "serif"
    const val JETBRAINS_MONO = "jetbrains_mono"
    const val HARMONYOS_SANS_SC = "harmonyos_sans_sc"
    const val LXGW_WENKAI_SCREEN = "lxgw_wenkai_screen"

    /** The imported custom font (the same file as the custom chat font). */
    const val CUSTOM = "custom"

    val ALL = listOf(SYSTEM, SERIF, JETBRAINS_MONO, HARMONYOS_SANS_SC, LXGW_WENKAI_SCREEN, CUSTOM)
}

/**
 * Workspace editor typography. The App and the Web keep one each in [DisplaySetting]:
 * the App's [fontSize] is in sp, the Web's in CSS px. [letterSpacing] is in em, [lineHeight] is a
 * multiple of the font size. Readers always go through [normalized], so a hand-edited or corrupt
 * value is clamped instead of crashing the editor.
 */
@Serializable
data class EditorTypography(
    val font: String = EditorFontIds.JETBRAINS_MONO,
    val fontSize: Float = 13f,
    val letterSpacing: Float = 0f,
    val lineHeight: Float = 18f / 13f,
) {
    fun normalized(fallback: EditorTypography): EditorTypography = EditorTypography(
        font = font.takeIf { it in EditorFontIds.ALL } ?: fallback.font,
        fontSize = fontSize.clampOr(MIN_FONT_SIZE, MAX_FONT_SIZE, fallback.fontSize),
        letterSpacing = letterSpacing.clampOr(MIN_LETTER_SPACING, MAX_LETTER_SPACING, fallback.letterSpacing),
        lineHeight = lineHeight.clampOr(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT, fallback.lineHeight),
    )

    companion object {
        const val MIN_FONT_SIZE = 10f
        const val MAX_FONT_SIZE = 32f
        const val MIN_LETTER_SPACING = -0.05f
        const val MAX_LETTER_SPACING = 0.3f
        const val MIN_LINE_HEIGHT = 1f
        const val MAX_LINE_HEIGHT = 3f

        /** What the App editor looked like before typography settings: JetBrains Mono 13sp, 18sp lines. */
        val APP_DEFAULT = EditorTypography()

        /** What the Web editor looked like before typography settings: page font, 0.875rem, 1.625 lines. */
        val WEB_DEFAULT = EditorTypography(font = EditorFontIds.SYSTEM, fontSize = 14f, lineHeight = 1.625f)

        private fun Float.clampOr(min: Float, max: Float, fallback: Float): Float =
            if (isNaN() || isInfinite()) fallback else coerceIn(min, max)
    }
}

val DisplaySetting.appEditorTypography: EditorTypography
    get() = workspaceEditorTypography.normalized(EditorTypography.APP_DEFAULT)

val DisplaySetting.webEditorTypography: EditorTypography
    get() = webWorkspaceEditorTypography.normalized(EditorTypography.WEB_DEFAULT)
