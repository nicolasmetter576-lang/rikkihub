package me.rerere.rikkihub.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.mutation.MutationRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 世界书体积大又很少变，而 `decodeSettings` 每次发射、每次 `mutate` 都整份解码所有键。
 * 键的原始 JSON 没变时复用上次解码出的实例（`RawJsonMemo`），改别的设置不再重新解码它。
 */
class SettingsLorebooksMemoTest {
    private suspend fun fixture(block: suspend (SettingsStore) -> Unit) {
        val root = Files.createTempDirectory("settings-lorebooks-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(root, "settings.preferences_pb") })
        val runtime = MutationRuntime(File(root, "runtime"), File(root, "restore.marker"))
        val store = SettingsStore.forTesting(dataStore, runtime, scope)
        try { block(store) } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun unrelatedEditsReuseTheDecodedLorebooks() = runBlocking<Unit> {
        fixture { store ->
            store.update { it.copy(lorebooks = listOf(Lorebook(name = "Eldoria"))) }
            val before = store.settingsSnapshot().lorebooks
            assertEquals(listOf("Eldoria"), before.map { it.name })

            store.update { it.copy(titlePrompt = "changed") }
            val after = store.settingsSnapshot()
            assertEquals("changed", after.titlePrompt)
            // 列表对象本身每次都是新的（normalizedForPersistence 里的 distinctBy 总会新建列表），
            // 但元素是同一批实例：没有重新解码 JSON，后续的相等比较也因此逐个 === 短路
            assertEquals(before, after.lorebooks)
            assertSame("世界书没变，应复用解码过的实例", before[0], after.lorebooks[0])
        }
    }

    @Test fun changingTheLorebooksDecodesAgain() = runBlocking<Unit> {
        fixture { store ->
            store.update { it.copy(lorebooks = listOf(Lorebook(name = "A"))) }
            val first = store.settingsSnapshot().lorebooks

            store.update { it.copy(lorebooks = first + Lorebook(name = "B")) }
            val second = store.settingsSnapshot().lorebooks
            // 内容变了，缓存必须失效：重新解码出的元素不能还是旧实例
            assertNotSame(first[0], second[0])
            assertEquals(listOf("A", "B"), second.map { it.name })
        }
    }

    @Test fun absentLorebooksStayEmpty() = runBlocking<Unit> {
        fixture { store ->
            assertEquals(emptyList<Lorebook>(), store.settingsSnapshot().lorebooks)
            store.update { it.copy(titlePrompt = "x") }
            assertEquals(emptyList<Lorebook>(), store.settingsSnapshot().lorebooks)
        }
    }
}
