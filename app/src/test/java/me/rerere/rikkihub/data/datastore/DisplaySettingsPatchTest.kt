package me.rerere.rikkihub.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DisplaySettingsPatchTest {
    @Test fun markdownSizeEffectsAreOffForNewAndLegacySettings() {
        assertFalse(DisplaySetting().workspaceMarkdownHeadingSizes)
        assertFalse(Json.decodeFromString<DisplaySetting>("{}").workspaceMarkdownHeadingSizes)
    }

    @Test fun enabledSizeSettingSurvivesSerialization() {
        val setting = DisplaySetting(workspaceMarkdownHeadingSizes = true)
        assertEquals(setting, Json.decodeFromString<DisplaySetting>(Json.encodeToString(setting)))
    }

    @Test fun enablingSizesPreservesConcurrentDisplayFields() {
        val previous = DisplaySetting()
        val latest = previous.copy(fontSizeRatio = 1.5f, showModelName = false, codeBlockAutoWrap = true)
        val next = applyDisplaySettingsPatch(latest, previous, previous.copy(workspaceMarkdownHeadingSizes = true))
        assertTrue(next.workspaceMarkdownHeadingSizes)
        assertEquals(1.5f, next.fontSizeRatio)
        assertFalse(next.showModelName)
        assertTrue(next.codeBlockAutoWrap)
    }

    @Test fun otherDisplayActionsDoNotResetConcurrentSizeToggle() {
        val stale = DisplaySetting()
        val latest = stale.copy(workspaceMarkdownHeadingSizes = true)
        val next = applyDisplaySettingsPatch(latest, stale, stale.copy(showTokenUsage = false))
        assertTrue(next.workspaceMarkdownHeadingSizes)
        assertFalse(next.showTokenUsage)
    }

    @Test fun clearingAValueIsAnExplicitPatchNotAbsence() {
        val previous = DisplaySetting(userNickname = "old", workspaceMarkdownHeadingSizes = true)
        val latest = previous.copy(fontSizeRatio = 1.2f)
        val next = applyDisplaySettingsPatch(latest, previous, previous.copy(userNickname = "", workspaceMarkdownHeadingSizes = false))
        assertEquals("", next.userNickname)
        assertFalse(next.workspaceMarkdownHeadingSizes)
        assertEquals(1.2f, next.fontSizeRatio)
    }

    @Test fun noChangedFieldsIsARealNoOp() {
        val stale = DisplaySetting()
        val latest = stale.copy(workspaceMarkdownHeadingSizes = true)
        assertSame(latest, applyDisplaySettingsPatch(latest, stale, stale))
    }
}
