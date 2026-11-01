package me.rerere.rikkihub.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.utils.moveByKey
import me.rerere.rikkihub.utils.replaceByKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 设置写入改为基于最新值（借鉴上游 3462d9e / 4dbf303）：界面把「怎么改」交给存储，
 * 在最新值上执行，快速输入和拖动不会因为旧快照冲突丢掉改动。
 */
class SettingsLatestEditTest {
    private suspend fun fixture(block: suspend (SettingsStore) -> Unit) {
        val root = Files.createTempDirectory("settings-latest-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(root, "settings.preferences_pb") })
        val runtime = MutationRuntime(File(root, "runtime"), File(root, "restore.marker"))
        val store = SettingsStore.forTesting(dataStore, runtime, scope)
        try { block(store) } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun rapidTypingFromOneStaleScreenNeverLosesTheLastKeystroke() = runBlocking<Unit> {
        fixture { store ->
            store.settingsSnapshot()
            // 界面在同一次组合里连发：旧写法每次都拿同一份快照整份写回，第二次起全部冲突
            val typed = listOf("a", "ab", "abc", "abcd")
            typed.forEach { text -> assertTrue(store.tryUpdate { it.copy(titlePrompt = text) }) }
            assertEquals("abcd", store.settingsSnapshot().titlePrompt)
        }
    }

    @Test fun staleWholeSnapshotStillConflictsButTheFunctionalEditLands() = runBlocking<Unit> {
        fixture { store ->
            val screen = store.settingsSnapshot()
            store.update { it.copy(titlePrompt = "a") }
            assertFalse("the old snapshot path is what lost keystrokes", store.tryUpdate(screen.copy(titlePrompt = "ab")))
            assertTrue(store.tryUpdate { it.copy(titlePrompt = "ab") })
            assertEquals("ab", store.settingsSnapshot().titlePrompt)
        }
    }

    @Test fun editingOneProviderKeepsAConcurrentChangeToAnother() = runBlocking<Unit> {
        fixture { store ->
            val screen = store.settingsSnapshot()
            val first = screen.providers[0]
            val second = screen.providers[1]
            // 后台（如模型列表同步）先改了第二个提供商
            store.update { latest -> latest.copy(providers = latest.providers.map {
                if (it.id == second.id) it.copyProvider(name = "synced") else it
            }) }
            // 界面编辑第一个提供商：只替换它
            val edited = first.copyProvider(name = "edited")
            store.update { latest -> latest.copy(providers = latest.providers.replaceByKey({ it.id }, edited)) }
            val saved = store.settingsSnapshot().providers
            assertEquals("edited", saved.first { it.id == first.id }.name)
            assertEquals("synced", saved.first { it.id == second.id }.name)
        }
    }

    @Test fun assistantEditorAppliesOnlyTheFieldsItChanged() = runBlocking<Unit> {
        val base = Assistant(name = "A", systemPrompt = "old prompt", temperature = 0.5f)
        // 别处刚改了温度，页面还拿着旧的助手
        val latest = base.copy(temperature = 0.9f)
        val edited = base.copy(systemPrompt = "new prompt")
        val merged = mergeObjectEdits(Assistant.serializer(), base, edited, latest)
        assertEquals("new prompt", merged.systemPrompt)
        assertEquals(0.9f, merged.temperature)
        assertEquals("A", merged.name)
        assertSame(latest, mergeObjectEdits(Assistant.serializer(), base, base, latest))
    }

    @Test fun dragReorderReplaysByKeyOnTheLatestList() {
        val screen = listOf("a", "b", "c")
        // 拖动期间别处插入了 x
        val latest = listOf("x", "a", "b", "c")
        assertEquals(listOf("x", "b", "c", "a"), latest.moveByKey({ it }, movedKey = "a", targetKey = "c"))
        assertEquals(listOf("b", "a", "c"), screen.moveByKey({ it }, "a", "b"))
        assertSame(latest, latest.moveByKey({ it }, "gone", "a"))
    }
}
