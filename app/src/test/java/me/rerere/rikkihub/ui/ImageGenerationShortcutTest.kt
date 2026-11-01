package me.rerere.rikkihub.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Resource/dispatch wiring only; actual launcher and cold/warm Activity behavior need a device. */
class ImageGenerationShortcutTest {
    private val appDir: File get() = File("src/main").takeIf { it.isDirectory }?.let { File(".") } ?: File("app")
    private val android = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(appDir, path))
    private fun Element.attr(name: String) = getAttributeNS(android, name)
    private fun shortcuts(): List<Element> = xml("src/main/res/xml/shortcuts.xml")
        .getElementsByTagName("shortcut").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    private fun route() = File(appDir, "src/main/java/me/rerere/rikkihub/RouteActivity.kt").readText()

    // 2.23.0 起桌面快捷方式换成媒体创作（旧的图片生成页即将废弃），IMAGE_GEN 动作仍然可用
    @Test fun `media creation shortcut uses fork action class enabled label and valid icon`() {
        val media = shortcuts().single { it.attr("shortcutId") == "media_creation" }
        assertEquals("true", media.attr("enabled"))
        assertEquals("@string/shortcut_media_creation", media.attr("shortcutShortLabel"))
        assertEquals("@string/shortcut_media_creation", media.attr("shortcutLongLabel"))
        assertEquals("@drawable/ic_image_generation", media.attr("icon"))
        val intent = media.getElementsByTagName("intent").item(0) as Element
        assertEquals("me.rerere.rikkihub.action.MEDIA_CREATION", intent.attr("action"))
        assertEquals("me.rerere.rikkihub.RouteActivity", intent.attr("targetClass"))
        assertEquals("vector", xml("src/main/res/drawable/ic_image_generation.xml").documentElement.tagName)
    }

    @Test fun `camera and translator shortcuts keep their original fork identities`() {
        val items = shortcuts()
        assertEquals(3, items.size)
        assertEquals(setOf("camera", "translator", "media_creation"), items.map { it.attr("shortcutId") }.toSet())
        val camera = items.single { it.attr("shortcutId") == "camera" }
            .getElementsByTagName("intent").item(0) as Element
        assertEquals("rikkihub://shortcut", camera.attr("data"))
        assertEquals("me.rerere.rikkihub.ui.activity.ShortcutHandlerActivity", camera.attr("targetClass"))
        val translator = items.single { it.attr("shortcutId") == "translator" }
            .getElementsByTagName("intent").item(0) as Element
        assertEquals("me.rerere.rikkihub.action.TRANSLATE", translator.attr("action"))
    }

    @Test fun `RouteActivity manifest advertises image action alongside translate`() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val activities = manifest.getElementsByTagName("activity")
        val route = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.attr("name") == ".RouteActivity" }
        val actions = route.getElementsByTagName("action").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).attr("name") }
        }
        assertTrue(actions.contains("me.rerere.rikkihub.action.IMAGE_GEN"))
        assertTrue(actions.contains("me.rerere.rikkihub.action.MEDIA_CREATION"))
        assertTrue(actions.contains("me.rerere.rikkihub.action.TRANSLATE"))
        assertTrue(actions.contains("android.intent.action.SEND"))
        assertTrue(actions.contains("android.intent.action.PROCESS_TEXT"))
        val metadata = route.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.app.shortcuts", metadata.attr("name"))
        assertEquals("@xml/shortcuts", metadata.attr("resource"))
    }

    @Test fun `dispatch maps image action to existing page without bypassing other destinations`() {
        val source = route()
        assertTrue(source.contains("ACTION_IMAGE_GEN = \"me.rerere.rikkihub.action.IMAGE_GEN\""))
        assertTrue(source.contains("ACTION_IMAGE_GEN -> Screen.ImageGen"))
        assertTrue(source.contains("entry<Screen.ImageGen>"))
        assertTrue(source.contains("ImageGenPage()"))
        assertTrue(source.contains("ACTION_MEDIA_CREATION -> {"))
        assertTrue(source.contains("entry<Screen.MediaCreation>"))
        assertTrue(source.contains("EXTRA_OPEN_CODEX_SETTINGS"))
        assertTrue(source.contains("EXTRA_OPEN_DEVIN_SETTINGS"))
        assertTrue(source.contains("Intent.ACTION_SEND -> Screen.ShareHandler"))
        assertTrue(source.contains("Intent.ACTION_PROCESS_TEXT -> Screen.ShareHandler"))
        assertFalse(File(appDir, "src/main/res/xml/shortcuts.xml").readText().contains("me.rerere.rikkahub"))
    }

    @Test fun `cold start pending intents and repeated warm destination guard are preserved`() {
        val source = route()
        assertTrue(source.contains("pendingIntents.addLast(intent)"))
        assertTrue(source.contains("handleIntent(pendingIntents.removeFirst())"))
        assertTrue(source.contains("destination != null && backStack.lastOrNull() != destination"))
        assertTrue(source.contains("override fun onNewIntent(intent: Intent)"))
        assertTrue(source.contains("handleIntent(intent)"))
    }
}
