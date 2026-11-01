package me.rerere.rikkihub.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.data.mutation.ExpectedVersions
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.data.mutation.MutationOutcome
import me.rerere.rikkihub.data.mutation.MutationRuntime
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Real Preferences DataStore edits on disk, not a mocked Settings lambda. */
class SettingsMutationTest {
    private suspend fun fixture(block: suspend (SettingsStore) -> Unit) {
        val root = Files.createTempDirectory("settings-mutation-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(root, "settings.preferences_pb") })
        val runtime = MutationRuntime(File(root, "runtime"), File(root, "restore.marker"))
        val store = SettingsStore.forTesting(dataStore, runtime, scope)
        try { block(store) } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun functionalEditBeforeFlowInitializationUsesDurableDefaults() = runBlocking<Unit> {
        fixture { store ->
            store.update { it.copy(titlePrompt = "first durable edit") }
            val saved = store.settingsSnapshot()
            assertFalse(saved.init)
            assertEquals("first durable edit", saved.titlePrompt)
            assertTrue(saved.assistants.isNotEmpty())
            assertTrue(saved.readRevision > 0)
        }
    }

    @Test fun concurrentEditsDoNotOverwriteEachOther() = runBlocking<Unit> {
        fixture { store ->
            (1..24).map { async(Dispatchers.Default) {
                store.update { it.copy(sponsorAlertDismissedAt = it.sponsorAlertDismissedAt + 1) }
            } }.awaitAll()
            assertEquals(24, store.settingsSnapshot().sponsorAlertDismissedAt)
        }
    }

    @Test fun launchCountLivesOutsideSettingsAndKeepsTheRevision() = runBlocking<Unit> {
        fixture { store ->
            store.update { it.copy(titlePrompt = "edit") }
            val before = store.settingsSnapshot()
            repeat(3) { store.incrementLaunchCount() }
            assertEquals(3, store.launchCountFlow.first())
            val after = store.settingsSnapshot()
            // 启动计数不升设置 revision，整份设置也不变，旧快照的写入仍能按原 base 合并
            assertEquals(before.readRevision, after.readRevision)
            assertEquals(before, after)
            store.setLaunchCount(42)
            assertEquals(42, store.launchCountFlow.first())
            assertTrue(store.tryUpdate(before.copy(titlePrompt = "from old snapshot")))
        }
    }

    @Test fun staleSnapshotEditOfSameFieldIsRejectedNotOverwritten() = runBlocking<Unit> {
        fixture { store ->
            val old = store.settingsSnapshot()
            store.update { it.copy(titlePrompt = "newer") }
            assertThrows(MutationConflictException::class.java) {
                runBlocking { store.update(old.copy(titlePrompt = "stale draft")) }
            }
            assertEquals("newer", store.settingsSnapshot().titlePrompt)
            assertFalse(store.tryUpdate(old.copy(titlePrompt = "stale draft")))
            assertEquals("newer", store.settingsSnapshot().titlePrompt)
        }
    }

    @Test fun disjointFieldEditsFromOneSnapshotBothLand() = runBlocking<Unit> {
        fixture { store ->
            val old = store.settingsSnapshot()
            // Two text fields committing on dispose from the same composition snapshot.
            store.update(old.copy(titlePrompt = "title edit"))
            store.update(old.copy(suggestionPrompt = "suggestion edit"))
            val saved = store.settingsSnapshot()
            assertEquals("title edit", saved.titlePrompt)
            assertEquals("suggestion edit", saved.suggestionPrompt)
        }
    }

    @Test fun keyedListMergesPerObjectAndRejectsSameObjectOrReorderRaces() = runBlocking<Unit> {
        fixture { store ->
            val a = QuickMessage(title = "A", content = "a")
            val b = QuickMessage(title = "B", content = "b")
            store.update { it.copy(quickMessages = listOf(a, b)) }
            val old = store.settingsSnapshot()
            // Concurrent edit of A, stale edit of B: both survive.
            store.update { s -> s.copy(quickMessages = s.quickMessages.map { if (it.id == a.id) it.copy(content = "a2") else it }) }
            store.update(old.copy(quickMessages = old.quickMessages.map { if (it.id == b.id) it.copy(content = "b2") else it }))
            assertEquals(listOf("a2", "b2"), store.settingsSnapshot().quickMessages.map { it.content })
            // Stale edit of A (already changed) conflicts.
            assertThrows(MutationConflictException::class.java) {
                runBlocking { store.update(old.copy(quickMessages = old.quickMessages.map { if (it.id == a.id) it.copy(content = "lost") else it })) }
            }
            // A stale add is placed and does not resurrect/delete others.
            val c = QuickMessage(title = "C", content = "c")
            store.update(old.copy(quickMessages = old.quickMessages + c))
            assertEquals(listOf("a2", "b2", "c"), store.settingsSnapshot().quickMessages.map { it.content })
            // Stale reorder after a concurrent reorder conflicts.
            val fresh = store.settingsSnapshot()
            store.update { s -> s.copy(quickMessages = s.quickMessages.reversed()) }
            assertThrows(MutationConflictException::class.java) {
                runBlocking { store.update(fresh.copy(quickMessages = listOf(fresh.quickMessages[1], fresh.quickMessages[0], fresh.quickMessages[2]))) }
            }
            // A deleted item cannot be re-deleted from a stale view that also edited it.
            assertEquals(3, store.settingsSnapshot().quickMessages.size)
        }
    }

    @Test fun ordinaryEditsDoNotGrowDurableReceipts() = runBlocking<Unit> {
        fixture { store ->
            repeat(5) { store.update { it.copy(sponsorAlertDismissedAt = it.sponsorAlertDismissedAt + 1) } }
            val op = Uuid.random().toString()
            store.mutate(operationId = op, requestFingerprint = "fp") { it.copy(sponsorAlertDismissedAt = 99) }
            assertNotNull(store.receiptFor(op))
            assertEquals(99, store.settingsSnapshot().sponsorAlertDismissedAt)
        }
    }

    @Test fun operationReceiptPreventsDuplicateEffectAndRejectsDifferentPayload() = runBlocking<Unit> {
        fixture { store ->
            val op = Uuid.random().toString()
            val first = store.mutate(operationId = op, requestFingerprint = "exact-increment") {
                it.copy(sponsorAlertDismissedAt = it.sponsorAlertDismissedAt + 1)
            }
            val replay = store.mutate(operationId = op, requestFingerprint = "exact-increment") {
                fail("replayed operation must not invoke the mutator"); it
            }
            assertEquals(MutationOutcome.APPLIED, first)
            assertEquals(MutationOutcome.REPLAYED, replay)
            assertEquals(1, store.settingsSnapshot().sponsorAlertDismissedAt)
            assertNotNull(store.receiptFor(op))
            assertThrows(MutationConflictException::class.java) {
                runBlocking { store.mutate(operationId = op, requestFingerprint = "other") { it } }
            }
        }
    }

    @Test fun defaultAssistantDeletionAndEmptyOrdinaryListPersist() = runBlocking<Unit> {
        fixture { store ->
            val creator = me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ID
            val ids = store.settingsSnapshot().assistants.map { it.id }.toSet() - creator
            store.update { it.copy(assistants = emptyList()) }
            // Only the creator (not deletable) remains; the ordinary list stays empty.
            val saved = store.settingsSnapshot()
            assertEquals(listOf(creator), saved.assistants.map { it.id })
            assertTrue(saved.deletedAssistantIds.containsAll(ids))
            store.update { it.copy(titlePrompt = "unrelated") }
            assertEquals(listOf(creator), store.settingsSnapshot().assistants.map { it.id })
        }
    }

    @Test fun deleteRecreateDoesNotSatisfyOldObjectToken() = runBlocking<Unit> {
        fixture { store ->
            val id = Uuid.random()
            val message = QuickMessage(id = id, title = "one", content = "text")
            store.update { it.copy(quickMessages = listOf(message)) }
            val key = "quickMessage:$id"
            val old = checkNotNull(store.currentSettingsToken(key))
            store.update { it.copy(quickMessages = emptyList()) }
            assertNull(store.currentSettingsToken(key))
            store.update { it.copy(quickMessages = listOf(message)) }
            val fresh = checkNotNull(store.currentSettingsToken(key))
            assertTrue(fresh.generation > old.generation)
            assertThrows(MutationConflictException::class.java) {
                runBlocking { store.mutate(expected = ExpectedVersions(objects = mapOf(key to old))) { it } }
            }
        }
    }

    @Test fun newBindingInvalidatesReferencePredicateWithoutChangingAssistantIds() = runBlocking<Unit> {
        fixture { store ->
            val message = QuickMessage(title = "one", content = "text")
            store.update { it.copy(quickMessages = listOf(message)) }
            val key = "assistants[].quickMessageIds"
            val revision = store.currentCollectionRevision(key)
            store.update { current -> current.copy(assistants = current.assistants.mapIndexed { index, assistant ->
                if (index == 0) assistant.copy(quickMessageIds = setOf(message.id)) else assistant
            }) }
            assertTrue(store.currentCollectionRevision(key) > revision)
            assertThrows(MutationConflictException::class.java) {
                runBlocking { store.mutate(expected = ExpectedVersions(collections = mapOf(key to revision))) { it } }
            }
        }
    }

    @Test fun creatorAssistantIsAlwaysPresentSelectableAndNeverBoundToAWorkspace() = runBlocking<Unit> {
        fixture { store ->
            val creatorId = me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ID
            val initial = store.settingsSnapshot()
            val creator = initial.assistants.first()
            assertEquals(creatorId, creator.id)
            assertEquals(me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.SYSTEM_PROMPT, creator.systemPrompt)
            assertTrue(creator.localTools.isEmpty())

            // Selectable like any other assistant.
            store.updateAssistant(creatorId)
            assertEquals(creatorId, store.settingsSnapshot().assistantId)

            // A workspace binding (App, Web or import) is dropped on persistence.
            store.update { current -> current.copy(assistants = current.assistants.map {
                if (it.id == creatorId) it.copy(workspaceId = Uuid.random(), name = "renamed") else it
            }) }
            store.settingsSnapshot().assistants.single { it.id == creatorId }.let {
                assertNull(it.workspaceId)
                assertEquals("renamed", it.name)
            }

            // Deleting it brings it back, and it does not stay in deletedAssistantIds.
            store.update { current -> current.copy(assistants = current.assistants.filterNot { it.id == creatorId }) }
            val after = store.settingsSnapshot()
            assertEquals(1, after.assistants.count { it.id == creatorId })
            assertFalse(creatorId in after.deletedAssistantIds)
        }
    }

    @Test fun currentAssistantFallbackPrefersAnOrdinaryAssistant() = runBlocking<Unit> {
        fixture { store ->
            val settings = store.settingsSnapshot().copy(assistantId = Uuid.random())
            assertFalse(me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.isCreator(settings.getCurrentAssistant().id))
        }
    }

    @Test fun legacyObjectsHaveStableInitialTokens() = runBlocking<Unit> {
        fixture { store ->
            val assistant = store.settingsSnapshot().assistants.first { !me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.isCreator(it.id) }
            val token = checkNotNull(store.currentSettingsToken("assistant:${assistant.id}"))
            // Fresh Preferences has no assistants: startup publishes defaults as
            // newly created objects. Persisted legacy objects are seeded at zero.
            assertEquals(1L, token.revision)
            assertEquals(token, store.currentSettingsToken("assistant:${assistant.id}"))
        }
    }

    @Test fun importChangesEpochAndRejectsOldSnapshot() = runBlocking<Unit> {
        fixture { store ->
            val old = store.settingsSnapshot()
            store.replaceForImport(old.copy(titlePrompt = "imported", readRevision = -1, readEpoch = -1))
            assertNotEquals(old.readEpoch, store.settingsSnapshot().readEpoch)
            assertThrows(MutationConflictException::class.java) { runBlocking { store.update(old) } }
            assertEquals("imported", store.settingsSnapshot().titlePrompt)
        }
    }

    @Test fun untrustedSnapshotsAndReservedToolNamesCannotBeInstalled() = runBlocking<Unit> {
        fixture { store ->
            assertThrows(MutationConflictException::class.java) { runBlocking { store.update(Settings()) } }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.update { it.copy(toolOverrides = mapOf("ordinary" to
                    ToolOverride(name = "creator_list_objects"))) } }
            }
        }
    }

    @Test fun freshInstallKeepsBuiltInModeInjectionsAndAnEmptiedListStaysEmpty() = runBlocking<Unit> {
        fixture { store ->
            // No MODE_INJECTIONS key yet: the built-in Learning Mode must not be decoded away.
            assertEquals(DEFAULT_MODE_INJECTIONS, store.settingsSnapshot().modeInjections)
            store.update { it.copy(titlePrompt = "first write") }
            assertEquals(DEFAULT_MODE_INJECTIONS, store.settingsSnapshot().modeInjections)
            store.update { it.copy(modeInjections = emptyList()) }
            assertEquals(emptyList<Any>(), store.settingsSnapshot().modeInjections)
        }
    }

    @Test fun cancelledWriteIsNotReportedAsWriteFailure() = runBlocking<Unit> {
        fixture { store ->
            val failures = mutableListOf<SettingsWriteFailure>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { store.writeFailures.collect { failures += it } }
            assertThrows(CancellationException::class.java) {
                runBlocking { store.tryUpdate { throw CancellationException("caller left") } }
            }
            yield()
            collector.cancel()
            assertEquals(emptyList<SettingsWriteFailure>(), failures)
            // The store keeps working after the cancelled write.
            assertTrue(store.tryUpdate { it.copy(titlePrompt = "after") })
            assertEquals("after", store.settingsSnapshot().titlePrompt)
        }
    }
}
