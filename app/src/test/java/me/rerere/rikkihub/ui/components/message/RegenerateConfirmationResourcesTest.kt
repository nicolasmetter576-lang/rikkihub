package me.rerere.rikkihub.ui.components.message

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * regression guard: the regenerate confirmation string must state that
 * messages after the current one will be cleared, in every shipped locale.
 *
 * Pure JVM test: it parses the actual res/values{,-*}/strings.xml files
 * (located by walking up from the test working directory), so no Android
 * resource framework or Robolectric is required.
 */
class RegenerateConfirmationResourcesTest {

    private val locales: Map<String, String> = mapOf(
        "values" to "en",
        "values-zh" to "zh",
        "values-zh-rTW" to "zh-rTW",
        "values-ja" to "ja",
        "values-ko-rKR" to "ko-rKR",
        "values-ru" to "ru",
        "values-de" to "de",
    )

    // Locale-specific fragments expressing "messages below/after this one are
    // deleted/cleared". Kept loose (substring, case-insensitive) so wording
    // refinements do not break the guard while the semantics stay asserted.
    private val removalMarkers: Map<String, List<String>> = mapOf(
        "values" to listOf("will be deleted", "will be cleared", "will be removed"),
        "values-zh" to listOf("清除", "删除", "清理"),
        "values-zh-rTW" to listOf("清除", "刪除", "清理"),
        "values-ja" to listOf("削除"),
        "values-ko-rKR" to listOf("삭제"),
        "values-ru" to listOf("удален", "удалит", "удалён"),
        "values-de" to listOf("gelöscht"),
    )

    private fun moduleDir(): File {
        // Gradle runs unit tests with the module dir as working dir, but be
        // tolerant: walk upward until app/src/main/res/values/strings.xml is found.
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(dir, "src/main/res/values/strings.xml")
            if (candidate.isFile) return dir
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return File(dir, "app")
            dir = dir.parentFile
                ?: error("could not locate src/main/res/values/strings.xml above ${System.getProperty("user.dir")}")
        }
    }

    private fun stringValue(resDir: String, name: String): String? {
        val file = File(moduleDir(), "src/main/res/$resDir/strings.xml")
        assertTrue("missing resource file: ${file.path}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.getAttribute("name") == name) return el.textContent
        }
        return null
    }

    @Test
    fun `regenerate confirm key exists and is non empty in all six locales`() {
        for (resDir in locales.keys) {
            val value = stringValue(resDir, "regenerate_confirm_message")
            assertNotNull("regenerate_confirm_message missing in $resDir", value)
            assertFalse("regenerate_confirm_message empty in $resDir", value!!.isBlank())
        }
    }

    @Test
    fun `regenerate confirm warns that following messages are cleared`() {
        for ((resDir, markers) in removalMarkers) {
            val value = stringValue(resDir, "regenerate_confirm_message")
            assertNotNull("regenerate_confirm_message missing in $resDir", value)
            assertTrue(
                "$resDir must warn that messages after this one are cleared: '$value'",
                markers.any { value!!.contains(it, ignoreCase = true) }
            )
        }
    }

    @Test
    fun `regenerate confirm scopes deletion to after this message only`() {
        val value = stringValue("values", "regenerate_confirm_message")
        assertNotNull(value)
        // Must talk about messages after/below this one, not the whole chat.
        assertTrue(
            "must scope clearing to messages after/below this one: '$value'",
            value!!.contains("after this", ignoreCase = true) ||
                value.contains("below this", ignoreCase = true)
        )
        assertFalse(
            "must not imply deleting the whole conversation: '$value'",
            value.contains("all messages", ignoreCase = true) &&
                !value.contains("after", ignoreCase = true) &&
                !value.contains("below", ignoreCase = true)
        )
    }
}
