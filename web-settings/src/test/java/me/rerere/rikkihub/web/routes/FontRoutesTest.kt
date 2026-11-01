package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.EditorFontIds
import me.rerere.rikkihub.data.datastore.EditorTypography
import me.rerere.rikkihub.data.fonts.DOWNLOADED_FONTS_DIR
import me.rerere.rikkihub.data.fonts.EditorFontFiles
import me.rerere.rikkihub.data.fonts.FontDownloadService
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** `/api/fonts` projection, `PUT /api/display/workspace-editor-typography` and `PUT /api/display/workspace-editor`. */
class FontRoutesTest {
    private fun body(json: String): JsonObject = JsonInstant.parseToJsonElement(json).jsonObject

    @Test
    fun `typography patch only changes the given keys`() {
        val current = EditorTypography.WEB_DEFAULT
        assertEquals(current, mergeEditorTypography(current, body("{}")))
        assertEquals(
            current.copy(font = EditorFontIds.LXGW_WENKAI_SCREEN, fontSize = 18f),
            mergeEditorTypography(current, body("""{"font":"lxgw_wenkai_screen","fontSize":18}""")),
        )
        assertEquals(
            current.copy(letterSpacing = 0.05f, lineHeight = 2f),
            mergeEditorTypography(current, body("""{"letterSpacing":0.05,"lineHeight":2}""")),
        )
    }

    @Test
    fun `typography patch rejects unknown keys, wrong types and out-of-range values`() {
        listOf(
            """{"color":"red"}""",
            """{"font":"comic_sans"}""",
            """{"font":3}""",
            """{"fontSize":"14"}""",
            """{"fontSize":9}""",
            """{"fontSize":33}""",
            """{"letterSpacing":0.5}""",
            """{"lineHeight":0.5}""",
            """{"lineHeight":null}""",
        ).forEach { json ->
            assertThrows(json, BadRequestException::class.java) { mergeEditorTypography(EditorTypography.WEB_DEFAULT, body(json)) }
        }
    }

    @Test
    fun `editor switches patch only what is given and reject anything but a boolean`() {
        val current = WorkspaceEditorDisplayDto(autoPair = true)
        assertEquals(current, mergeWorkspaceEditorDisplay(current, body("{}")))
        assertEquals(WorkspaceEditorDisplayDto(false), mergeWorkspaceEditorDisplay(current, body("""{"autoPair":false}""")))
        listOf("""{"autoPair":"false"}""", """{"autoPair":0}""", """{"autoPair":null}""", """{"autoClose":true}""").forEach { json ->
            assertThrows(json, BadRequestException::class.java) { mergeWorkspaceEditorDisplay(current, body(json)) }
        }
    }

    @Test
    fun `the web switch leaves the App editor's own switch alone`() {
        val display = DisplaySetting(workspaceEditorAutoPair = true, webWorkspaceEditorAutoPair = true)
        val off = patchWebEditorDisplay(display, body("""{"autoPair":false}"""))
        assertEquals(false, off.webWorkspaceEditorAutoPair)
        assertEquals(true, off.workspaceEditorAutoPair)
        val appOff = display.copy(workspaceEditorAutoPair = false)
        assertEquals(appOff.copy(webWorkspaceEditorAutoPair = false), patchWebEditorDisplay(appOff, body("""{"autoPair":false}""")))
        assertEquals(appOff, patchWebEditorDisplay(appOff, body("{}")))
    }

    @Test
    fun `font list reports installed state and the files the browser can load`() {
        val filesDir = Files.createTempDirectory("files").toFile()
        try {
            val fontsRoot = File(filesDir, "fonts")
            val downloads = FontDownloadService(fontsRoot, CoroutineScope(SupervisorJob() + Dispatchers.IO), { error("no fetch") }) { _, _ -> }
            val bundled = ByteArray(1234)
            val files = EditorFontFiles(filesDir, downloads, "207") { id ->
                if (id == EditorFontIds.JETBRAINS_MONO) ByteArrayInputStream(bundled) else null
            }
            val empty = projectWebFonts(files, DisplaySetting())
            assertEquals(listOf("harmonyos_sans_sc", "lxgw_wenkai_screen"), empty.fonts.map { it.id })
            assertTrue(empty.fonts.none { it.installed })
            assertEquals(mapOf(EditorFontIds.JETBRAINS_MONO to "b207"), empty.files)
            assertNull(empty.customName)
            assertEquals(1234L, files.resolve(EditorFontIds.JETBRAINS_MONO, DisplaySetting())!!.length)
            assertNull(files.resolve(EditorFontIds.SYSTEM, DisplaySetting()))

            File(fontsRoot, "chat_font.1.ttf").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
            val display = DisplaySetting(chatCustomFontPath = "fonts/chat_font.1.ttf", chatCustomFontName = "Mine.ttf")
            val withCustom = projectWebFonts(files, display)
            assertEquals("Mine.ttf", withCustom.customName)
            assertTrue(EditorFontIds.CUSTOM in withCustom.files)
            // A path that escapes filesDir is never served.
            assertNull(files.resolve(EditorFontIds.CUSTOM, display.copy(chatCustomFontPath = "../etc/passwd")))
            assertTrue(File(fontsRoot, DOWNLOADED_FONTS_DIR).let { !it.exists() || it.isDirectory })
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
