package me.rerere.rikkihub.ui.pages.setting

import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingSearchTest {
    private val pageFiles = mapOf<Screen, String>(
        Screen.SettingPreferencesTheme to "SettingPreferencesThemePage",
        Screen.SettingPreferencesUI to "SettingPreferencesUIPage",
        Screen.SettingPreferencesGeneral to "SettingPreferencesGeneralPage",
        Screen.SettingPreferencesNotification to "SettingPreferencesNotificationPage",
        Screen.SettingPreferencesWorkspace to "SettingPreferencesWorkspacePage",
        Screen.SettingPreferencesNetwork to "SettingPreferencesNetworkPage",
        Screen.SettingModels to "SettingModelPage",
        Screen.SettingPromptPolish to "SettingPromptPolishPage",
        Screen.SettingMemoryDream to "SettingMemoryDreamPage",
        Screen.SettingCompression to "SettingCompressionPage",
        Screen.SettingTools to "SettingToolsPage",
        Screen.SettingTuiqiao to "SettingTuiqiaoPage",
        Screen.SettingZaizao to "SettingZaizaoPage",
        Screen.SettingBook to "SettingBookPage",
        Screen.SettingQubagu to "SettingQubaguPage",
        Screen.SettingWeb to "SettingWebPage",
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir
    }

    @Test
    fun `every toggle flips exactly its own setting`() {
        val toggles = settingSearchItems.mapNotNull { it.toggle }
        val failures = mutableListOf<String>()
        settingSearchItems.forEachIndexed { index, item ->
            val toggle = item.toggle ?: return@forEachIndexed
            for (start in listOf(false, true)) {
                val base = toggle.set(Settings(), start)
                val flipped = toggle.set(base, !start)
                if (toggle.get(flipped) != !start) failures += "#$index does not read back what it wrote"
                toggles.filter { it !== toggle }.forEachIndexed { other, t ->
                    if (t.get(flipped) != t.get(base)) failures += "#$index also changed toggle #$other"
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `every item is tagged with its searchKey on its page and the page has a home entry`() {
        val homeScreens = settingBlocks.flatMap { it.entries }.map { it.screen }.toSet()
        val pageDir = File(repoRoot(), "app/src/main/java/me/rerere/rikkihub/ui/pages/setting")
        val failures = mutableListOf<String>()
        settingSearchItems.forEachIndexed { index, item ->
            if (item.screen !in homeScreens) failures += "#$index: ${item.screen} has no settings home entry"
            val file = pageFiles[item.screen]
            if (file == null) {
                failures += "#$index: no page file mapped for ${item.screen}"
                return@forEachIndexed
            }
            val source = File(pageDir, "$file.kt").readText()
            val name = me.rerere.rikkihub.R.string::class.java.fields.first { it.getInt(null) == item.title }.name
            // 去八股页的开关经本地 switchItem(title, …) 传 searchKey = title
            val tagged = "searchKey = R.string.$name" in source || "switchItem(R.string.$name," in source
            if (!tagged) failures += "#$index: $file has no row tagged $name"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `items appear once per page`() {
        val dupes = settingSearchItems.groupBy { it.screen to it.title }.filterValues { it.size > 1 }.keys
        assertTrue("duplicates: $dupes", dupes.isEmpty())
    }

    private fun c(title: String, path: String) = SettingSearchCandidate(title, path, Screen.SettingWeb)

    @Test
    fun `title prefix ranks first, then title contains, then path only`() {
        val candidates = listOf(
            c("显示模型名称", "界面"),
            c("界面", "外观与交互"),
            c("代码块自动换行", "界面"),
            c("自定义界面字体", "界面"),
        )
        assertEquals(
            listOf("界面", "自定义界面字体", "显示模型名称", "代码块自动换行"),
            matchSettingSearch(candidates, "界面").map { it.title },
        )
    }

    @Test
    fun `every word must match somewhere, case-insensitive, blank finds nothing`() {
        val candidates = listOf(c("Send on Enter", "General"), c("Auto scroll", "General"), c("Proxy", "Network"))
        assertEquals(listOf("Send on Enter"), matchSettingSearch(candidates, "general  ENTER").map { it.title })
        assertEquals(emptyList<String>(), matchSettingSearch(candidates, "   ").map { it.title })
        assertEquals(emptyList<String>(), matchSettingSearch(candidates, "proxy general").map { it.title })
    }
}
