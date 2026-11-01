package me.rerere.rikkihub.data.fonts

import me.rerere.rikkihub.data.datastore.ChatFontFamily
import me.rerere.rikkihub.data.datastore.EditorFontIds

/**
 * A font the user downloads on demand: not shipped in the APK, fetched from a public
 * mirror and kept only if both [sizeBytes] and [sha256] match. [urls] are tried in order.
 * [family] is the font's own family name, used by the Web as `local("…")` before the phone copy.
 */
data class DownloadableFont(
    val id: String,
    val displayName: String,
    val family: String,
    val sizeBytes: Long,
    val sha256: String,
    val urls: List<String>,
    val license: String,
    val licenseUrl: String,
)

object DownloadableFonts {
    val HARMONYOS_SANS_SC = DownloadableFont(
        id = EditorFontIds.HARMONYOS_SANS_SC,
        displayName = "HarmonyOS Sans SC",
        family = "HarmonyOS Sans SC",
        sizeBytes = 8_261_128,
        sha256 = "297b088424be212207df2ce8b98e335468b782aa6b96832af0b8b773d711e2b1",
        urls = listOf(
            "https://cdn.jsdelivr.net/npm/@fontpkg/harmony-os-sans-sc@1.0.3/HarmonyOS_Sans_SC_Regular.ttf",
            "https://fastly.jsdelivr.net/npm/@fontpkg/harmony-os-sans-sc@1.0.3/HarmonyOS_Sans_SC_Regular.ttf",
            "https://unpkg.com/@fontpkg/harmony-os-sans-sc@1.0.3/HarmonyOS_Sans_SC_Regular.ttf",
        ),
        license = "HarmonyOS Sans Fonts License",
        licenseUrl = "https://developer.huawei.com/consumer/cn/design/resource/",
    )

    val LXGW_WENKAI_SCREEN = DownloadableFont(
        id = EditorFontIds.LXGW_WENKAI_SCREEN,
        displayName = "霞鹜文楷 屏幕阅读版",
        family = "LXGW WenKai Screen",
        sizeBytes = 25_673_994,
        sha256 = "cd1a6fa39c4ea42fd8f4e289945789b0e510cf7016435640f8893cdad9b220f3",
        urls = listOf(
            "https://github.com/lxgw/LxgwWenKai-Screen/releases/download/v1.522/LXGWWenKaiScreen.ttf",
        ),
        license = "SIL Open Font License 1.1",
        licenseUrl = "https://github.com/lxgw/LxgwWenKai-Screen",
    )

    val ALL = listOf(HARMONYOS_SANS_SC, LXGW_WENKAI_SCREEN)

    fun byId(id: String): DownloadableFont? = ALL.firstOrNull { it.id == id }
}

/** The downloadable font a chat font choice needs, or null for built-in / custom choices. */
val ChatFontFamily.downloadableFont: DownloadableFont?
    get() = when (this) {
        ChatFontFamily.HARMONYOS_SANS_SC -> DownloadableFonts.HARMONYOS_SANS_SC
        ChatFontFamily.LXGW_WENKAI_SCREEN -> DownloadableFonts.LXGW_WENKAI_SCREEN
        else -> null
    }
