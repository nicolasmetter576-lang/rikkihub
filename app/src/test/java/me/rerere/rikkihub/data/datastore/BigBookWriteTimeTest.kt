package me.rerere.rikkihub.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.tavern.worldinfo.WorldInfoEntry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A lorebook of 1200 entries (about 2 MB) must not make every settings write slow. The write used to rebuild the table of
 * every settings object once per object (quadratic in the object count): about 280 ms here and 6 s on a slow device, for
 * ANY write. Now it is about 20 ms; the bound below sits well under the old figure and well over the new one.
 */
class BigBookWriteTimeTest {
    private fun entry(i: Int) = WorldInfoEntry(
        uid = i, comment = "Entry $i", key = listOf("k$i", "w${i % 50}"), keysecondary = if (i % 3 == 0) listOf("s") else emptyList(),
        content = "lorem ipsum dolor sit amet ".repeat(30), order = 100 + i % 40, displayIndex = i,
    )

    @Test(timeout = 120_000) fun aWriteWithABigBookIsNotSlow() = runBlocking {
        val root = Files.createTempDirectory("bigbook-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
            val store = SettingsStore.forTesting(dataStore, MutationRuntime(File(root, "runtime"), File(root, "restore.marker")), scope)
            store.update { it.copy(lorebooks = it.lorebooks + Lorebook(name = "Big", entries = (0 until 1200).map(::entry))) }
            val times = (0 until 20).map { i ->
                val start = System.nanoTime()
                store.update { it.copy(quickMessages = it.quickMessages + QuickMessage(title = "q$i", content = "c")) }
                (System.nanoTime() - start) / 1_000_000
            }
            val median = times.drop(2).sorted()[times.size / 2 - 1]
            assertTrue("a settings write next to a 1200-entry book took a median of $median ms (all: $times)", median < 120)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            root.deleteRecursively()
        }
    }
}
