package me.rerere.rikkihub.ui.components.ai

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchToggleTest {
    @Test
    fun builtInSearchWinsOverLocal() {
        val builtIn = Model(tools = setOf(BuiltInTools.Search))
        assertEquals(SearchMode.BUILT_IN, currentSearchMode(enableSearch = true, model = builtIn))
        assertEquals(SearchMode.LOCAL, currentSearchMode(enableSearch = true, model = Model()))
        assertEquals(SearchMode.OFF, currentSearchMode(enableSearch = false, model = Model()))
        assertEquals(SearchMode.OFF, currentSearchMode(enableSearch = false, model = null))
    }

    @Test
    fun tapTurnsAnyActiveModeOff() {
        assertEquals(SearchMode.OFF, nextSearchModeOnToggle(SearchMode.LOCAL, SearchMode.LOCAL, builtInSupported = true))
        assertEquals(SearchMode.OFF, nextSearchModeOnToggle(SearchMode.BUILT_IN, SearchMode.BUILT_IN, builtInSupported = true))
        // Leftover built-in search on a model that no longer supports it can still be switched off.
        assertEquals(SearchMode.OFF, nextSearchModeOnToggle(SearchMode.BUILT_IN, null, builtInSupported = false))
    }

    @Test
    fun tapRestoresLastModeWhenPossible() {
        assertEquals(SearchMode.BUILT_IN, nextSearchModeOnToggle(SearchMode.OFF, SearchMode.BUILT_IN, builtInSupported = true))
        assertEquals(SearchMode.LOCAL, nextSearchModeOnToggle(SearchMode.OFF, SearchMode.BUILT_IN, builtInSupported = false))
        assertEquals(SearchMode.LOCAL, nextSearchModeOnToggle(SearchMode.OFF, SearchMode.LOCAL, builtInSupported = true))
        assertEquals(SearchMode.LOCAL, nextSearchModeOnToggle(SearchMode.OFF, null, builtInSupported = true))
    }

    @Test
    fun builtInSupportFollowsProvider() {
        assertTrue(supportsBuiltInSearch(ProviderSetting.Google()))
        assertTrue(supportsBuiltInSearch(ProviderSetting.Claude()))
        assertTrue(supportsBuiltInSearch(ProviderSetting.OpenAI(useResponseApi = true)))
        assertFalse(supportsBuiltInSearch(ProviderSetting.OpenAI(useResponseApi = false)))
        assertFalse(supportsBuiltInSearch(null))
    }
}
