package me.rerere.rikkihub.data.datastore

import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Workspace editor typography in DisplaySetting. */
class EditorTypographyTest {
    @Test
    fun `defaults are what the editors looked like before typography settings`() {
        assertEquals(EditorTypography(EditorFontIds.JETBRAINS_MONO, 13f, 0f, 18f / 13f), EditorTypography.APP_DEFAULT)
        assertEquals(EditorTypography(EditorFontIds.SYSTEM, 14f, 0f, 1.625f), EditorTypography.WEB_DEFAULT)
    }

    @Test
    fun `settings saved before typography settings decode to the defaults`() {
        val legacy = JsonInstant.decodeFromString<DisplaySetting>("""{"fontSizeRatio":1.2,"chatFontFamily":"serif"}""")
        assertEquals(EditorTypography.APP_DEFAULT, legacy.appEditorTypography)
        assertEquals(EditorTypography.WEB_DEFAULT, legacy.webEditorTypography)
        assertEquals(ChatFontFamily.SERIF, legacy.chatFontFamily)
    }

    @Test
    fun `new chat font choices round-trip with stable names`() {
        for ((family, name) in listOf(
            ChatFontFamily.HARMONYOS_SANS_SC to "harmonyos_sans_sc",
            ChatFontFamily.LXGW_WENKAI_SCREEN to "lxgw_wenkai_screen",
        )) {
            val json = JsonInstant.encodeToString(DisplaySetting(chatFontFamily = family))
            assert("\"chatFontFamily\":\"$name\"" in json) { json }
            assertEquals(family, JsonInstant.decodeFromString<DisplaySetting>(json).chatFontFamily)
        }
    }

    @Test
    fun `out-of-range and corrupt values are clamped, unknown fonts fall back`() {
        val display = DisplaySetting(
            workspaceEditorTypography = EditorTypography("nope", 99f, -1f, Float.NaN),
            webWorkspaceEditorTypography = EditorTypography(EditorFontIds.LXGW_WENKAI_SCREEN, 2f, 5f, 0.2f),
        )
        assertEquals(
            EditorTypography(EditorFontIds.JETBRAINS_MONO, EditorTypography.MAX_FONT_SIZE, EditorTypography.MIN_LETTER_SPACING, 18f / 13f),
            display.appEditorTypography,
        )
        assertEquals(
            EditorTypography(EditorFontIds.LXGW_WENKAI_SCREEN, EditorTypography.MIN_FONT_SIZE, EditorTypography.MAX_LETTER_SPACING, EditorTypography.MIN_LINE_HEIGHT),
            display.webEditorTypography,
        )
    }

    @Test
    fun `the web font id list matches the app one`() {
        val base = java.io.File("../web-settings/web-ui/lib/editor-typography.ts").takeIf { it.isFile }
            ?: java.io.File("web-settings/web-ui/lib/editor-typography.ts")
        val source = base.readText()
        val block = source.substringAfter("export const EDITOR_FONT_IDS = [").substringBefore("]")
        val ids = Regex("\"([a-z_0-9]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(EditorFontIds.ALL, ids)
    }

    @Test
    fun `text and quote colors round-trip per theme, follow the theme by default and survive clamping`() {
        val default = EditorTypography.APP_DEFAULT
        assertEquals(EditorColorPair(), default.textColor)
        assertEquals(false, default.quoteHighlight)
        val pair = EditorColorPair().with(dark = true, argb = 0xFFE0D0B0L)
        assertEquals(null, pair.of(dark = false))
        assertEquals(0xFFE0D0B0L, pair.of(dark = true))
        assertEquals(EditorColorPair(light = 0xFF202020L, dark = 0xFFE0D0B0L), pair.with(dark = false, argb = 0xFF202020L))
        val custom = default.copy(textColor = pair, quoteHighlight = true, quoteColor = EditorColorPair(light = 0xFF8B0000L), fontSize = 99f)
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(DisplaySetting(workspaceEditorTypography = custom)))
        assertEquals(custom.copy(fontSize = EditorTypography.MAX_FONT_SIZE), decoded.appEditorTypography)
        // Typography saved before the colors existed reads them as unset
        val legacy = JsonInstant.decodeFromString<DisplaySetting>(
            """{"workspaceEditorTypography":{"font":"serif","fontSize":15,"letterSpacing":0,"lineHeight":1.5,"textBrightness":0.8}}""",
        ).appEditorTypography
        assertEquals(EditorColorPair(), legacy.textColor)
        assertEquals(false, legacy.quoteHighlight)
        assertEquals(0.8f, legacy.textBrightness)
    }
}
