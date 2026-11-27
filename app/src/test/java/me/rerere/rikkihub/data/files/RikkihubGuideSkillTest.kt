package me.rerere.rikkihub.data.files

import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.ui.pages.setting.settingBlocks
import me.rerere.rikkihub.ui.pages.setting.settingSearchItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 内置使用指南技能（assets/builtin_skills/rikkihub-guide）不能和 App 脱节：链接、篇幅、写到的按钮名、
 * 自动生成的设置地图、适用版本都在这里核对。每类检查把全部问题列出来再失败。
 */
class RikkihubGuideSkillTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val skillDir = File(root, "app/src/main/assets/builtin_skills/${CreatorAssistantDefinition.GUIDE_SKILL}")
    private val files = skillDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
    private fun rel(file: File) = file.relativeTo(skillDir).invariantSeparatorsPath

    /** 工具输出超过 32K 字符会被截成 4K 预览（GenerationLoop.MAX_TOOL_OUTPUT_CHARS），留出余量。 */
    private val maxChars = 24_000

    private val locales = linkedMapOf(
        "zh" to "values-zh", "zh-TW" to "values-zh-rTW", "en" to "values", "ja" to "values-ja",
        "ko" to "values-ko-rKR", "ru" to "values-ru", "de" to "values-de",
    )

    private fun strings(resDir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "app/src/main/res/$resDir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name") to unescape(element.textContent)
        }
    }

    private fun unescape(text: String): String = text.trim().let { if (it.length > 1 && it.startsWith('"') && it.endsWith('"')) it.drop(1).dropLast(1) else it }
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", " ").replace("\\@", "@").replace("\\?", "?")

    private val resourceNames: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun settingsMap(language: String): String {
        val local = strings(locales.getValue(language))
        val english = strings("values")
        fun label(id: Int) = resourceNames.getValue(id).let { local[it] ?: english.getValue(it) }
        return buildString {
            appendLine("# rikkihub — ${label(R.string.settings)} ($language)")
            appendLine()
            appendLine("Generated from the app's settings catalog; do not edit by hand. Labels are exactly as this language's UI shows them.")
            appendLine("A path reads: ${label(R.string.settings)} › <page> › <item>. `##` headings are the groups on the settings home page; [switch] marks an on/off switch.")
            settingBlocks.forEach { block ->
                appendLine()
                appendLine("## ${label(block.title)}")
                appendLine()
                block.entries.forEach { entry ->
                    appendLine("- ${label(entry.title)}")
                    settingSearchItems.filter { it.screen == entry.screen }.forEach { item ->
                        appendLine("  - ${label(item.title)}${if (item.toggle != null) " [switch]" else ""}")
                    }
                }
            }
        }
    }

    @Test
    fun `the skill is named and versioned so the app can find it and new features cannot skip it`() {
        val front = SkillFrontmatterParser.parse(File(skillDir, "SKILL.md").readText())
        assertEquals(CreatorAssistantDefinition.GUIDE_SKILL, front["name"])
        assertTrue("description is required", !front["description"].isNullOrBlank())
        val guide = front["rikkihub-version"].orEmpty().split('.').map { it.toInt() }
        val app = Regex("""versionName = "(\d+)\.(\d+)""").find(File(root, "app/build.gradle.kts").readText())!!
            .groupValues.drop(1).map { it.toInt() }
        // 加功能必升次版本：App 的“主.次”比指南新，就是这个版本的改动还没核对进指南
        assertTrue(
            "the guide says ${guide.joinToString(".")}, the app is ${app.joinToString(".")}: review the guide for this release, then raise rikkihub-version",
            compareValuesBy(app, guide, { it[0] }, { it[1] }) <= 0,
        )
    }

    @Test
    fun `every file is linked from SKILL_md and every link resolves`() {
        val failures = mutableListOf<String>()
        val reached = mutableSetOf("SKILL.md")
        val queue = ArrayDeque(listOf(File(skillDir, "SKILL.md")))
        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            Regex("""\]\(([^)\s#]+)(#[^)]*)?\)""").findAll(file.readText()).map { it.groupValues[1] }
                .filterNot { it.startsWith("http") }
                .forEach { link ->
                    val target = File(file.parentFile, link).canonicalFile
                    if (!target.isFile || !target.path.startsWith(skillDir.canonicalPath)) failures += "${rel(file)} links to missing $link"
                    else if (reached.add(rel(target))) queue += target
                }
        }
        files.map(::rel).filterNot { it in reached }.forEach { failures += "$it is not reachable from SKILL.md" }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `no file is long enough to be cut off when the model reads it`() {
        val failures = files.filter { it.readText().length > maxChars }.map { "${rel(it)}: ${it.readText().length} chars > $maxChars" }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `settings paths and button names match the Chinese UI and the web page`() {
        val zh = strings("values-zh").values.toSet()
        val pages = settingBlocks.flatMap { it.entries }.map { strings("values-zh").getValue(resourceNames.getValue(it.title)) }.toSet()
        val web = File(root, "web-ui/app").walkTopDown().filter { it.isFile && it.extension in setOf("json", "tsx", "ts") }
            .joinToString("\n") { it.readText() }
        val failures = mutableListOf<String>()
        files.filter { it.extension == "md" && !rel(it).startsWith("settings-map/") }.forEach { file ->
            val text = file.readText()
            Regex("`设置 › ([^`]+)`").findAll(text).forEach { match ->
                val segments = match.groupValues[1].split(" › ")
                if (segments.first() !in pages) failures += "${rel(file)}: `设置 › ${match.groupValues[1]}` — “${segments.first()}” is not a settings page"
                segments.drop(1).filterNot { it in zh }.forEach { failures += "${rel(file)}: `设置 › ${match.groupValues[1]}` — no Chinese string “$it”" }
            }
            Regex("「([^」]+)」").findAll(text).map { it.groupValues[1] }.filterNot { it in zh }
                .forEach { failures += "${rel(file)}: no Chinese string 「$it」" }
            Regex("`Web › ([^`]+)`").findAll(text).forEach { match ->
                match.groupValues[1].split(" › ").filterNot { it in web }
                    .forEach { failures += "${rel(file)}: `Web › ${match.groupValues[1]}` — “$it” is not in the web UI" }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `settings maps are generated from the current settings catalog`() {
        val failures = mutableListOf<String>()
        val out = File(root, "app/build/guide-settings-map")
        for (language in locales.keys) {
            val expected = settingsMap(language)
            val file = File(skillDir, "settings-map/$language.md")
            if (file.isFile && file.readText() == expected) continue
            out.mkdirs()
            File(out, "$language.md").writeText(expected)
            failures += "settings-map/$language.md is out of date: copy ${File(out, "$language.md")} over it"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `only the creator assistant has the guide switched on by default`() {
        assertTrue(Assistant().enabledSkills.isEmpty())
        assertTrue(Settings().assistants.filterNot { CreatorAssistantDefinition.isCreator(it.id) }.none { CreatorAssistantDefinition.GUIDE_SKILL in it.enabledSkills })
        assertTrue(CreatorAssistantDefinition.GUIDE_SKILL in CreatorAssistantDefinition.defaultAssistant().enabledSkills)
    }
}
