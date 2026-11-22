package me.rerere.rikkihub.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every translation that exists must keep the format arguments, line breaks and markup of its English
 * source, or the formatted string breaks at run time. Every language must also translate every string:
 * a missing key silently falls back to English in the middle of a translated screen.
 */
class StringResourcesConsistencyTest {
    private val modules = listOf("app", "oauth", "search")
    private val languages = listOf("values-de", "values-ja", "values-ko-rKR", "values-ru", "values-zh", "values-zh-rTW")

    // %1$s, %d, %%, %n ... (Java Formatter) — compared as a multiset
    private val placeholder = Regex("%(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaAn%])")
    // `$<name>` is a regex named-group reference in the replacement hint, not markup
    private val tag = Regex("(?<!\\$)</?[A-Za-z][^>]*>")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not locate settings.gradle.kts above ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private fun strings(module: String, resDir: String): Map<String, String>? {
        val file = File(repoRoot(), "$module/src/main/res/$resDir/strings.xml")
        if (!file.isFile) return null
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return buildMap {
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                if (element.getAttribute("translatable") == "false") continue
                put(element.getAttribute("name"), element.textContent)
            }
        }
    }

    @Test
    fun `translations keep the arguments, line breaks and markup of the source`() {
        val problems = mutableListOf<String>()
        for (module in modules) {
            val source = strings(module, "values") ?: continue
            for (language in languages) {
                val translated = strings(module, language) ?: continue
                for ((name, text) in translated) {
                    val original = source[name]
                    if (original == null) {
                        problems += "$module/$language/$name has no English source"
                        continue
                    }
                    if (placeholder.findAll(original).map { it.value }.sorted().toList() !=
                        placeholder.findAll(text).map { it.value }.sorted().toList()
                    ) problems += "$module/$language/$name: format arguments differ"
                    if (original.split("\\n").size != text.split("\\n").size) problems += "$module/$language/$name: \\n count differs"
                    if (tag.findAll(original).map { it.value }.toList() != tag.findAll(text).map { it.value }.toList()) {
                        problems += "$module/$language/$name: markup differs"
                    }
                    if (text.isBlank()) problems += "$module/$language/$name: blank"
                }
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `every language covers every string of all three modules`() {
        val problems = mutableListOf<String>()
        for (module in modules) {
            val source = strings(module, "values")!!
            for (language in languages) {
                val translated = strings(module, language)
                if (translated == null) {
                    problems += "$module has no $language/strings.xml"
                    continue
                }
                val missing = source.keys - translated.keys
                if (missing.isNotEmpty()) problems += "$module/$language is missing ${missing.size}: ${missing.take(10)}"
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `german keeps the brand name and does not use the formal address`() {
        val german = strings("app", "values-de")!!
        assertEquals("Rikkihub", german["app_name"])
        // A capital Sie/Ihr in the middle of a sentence is the formal address; at the start of a sentence it
        // is just "she/it/they" ("Sie wird beim Senden erstellt").
        val formal = Regex("[a-zäöüß,;:] (Sie|Ihr|Ihre|Ihren|Ihrem|Ihrer|Ihres)\\b|\\bIhnen\\b")
        val offenders = german.filter { (_, text) -> formal.containsMatchIn(text) }
        assertTrue("formal address (Sie) found in: ${offenders.keys.take(10)}", offenders.isEmpty())
        val upstreamBrand = german.filter { (_, text) -> text.contains("RikkaHub", ignoreCase = true) && !text.contains("Rikkihub") }
        assertTrue("old brand spelling in: ${upstreamBrand.keys}", upstreamBrand.isEmpty())
    }
}
