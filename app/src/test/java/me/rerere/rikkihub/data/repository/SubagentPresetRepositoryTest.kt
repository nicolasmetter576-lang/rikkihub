package me.rerere.rikkihub.data.repository

import me.rerere.workspace.LocalWorkspaceBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.SubagentPresetConflictException
import me.rerere.rikkihub.data.ai.tools.SubagentPresetGenerationConfig
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolUnavailableException
import me.rerere.rikkihub.data.ai.tools.requireWorkspaceToolAdmissible
import me.rerere.rikkihub.data.ai.tools.workspaceToolIdentity
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.SubagentPresetLibraryEntity
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.workspace.WorkspaceOperationKind
import me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableException
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * OFFLINE CORE: real repositories, native multi-workspace runtime gate and temporary WorkspaceManager.
 * No providers, network, Android startup or filesystem shell execution. SettingsStore is the same
 * detached Unsafe fixture as WorkspaceRepositoryPublicationTest; no workspace deletion is exercised.
 *
 * The store below snapshots/restores the library AND all workspace rows on exceptions. This is a
 * deterministic rollback simulation, NOT SQLite/Room evidence (including observer isolation).
 * Real outer Room transaction acceptance is separately supplied in androidTest, compile-only here.
 */
class SubagentPresetRepositoryTest {
    /** Same targeted-column/default-transaction model as WorkspaceRepositoryPublicationTest. */
    private class FakeWorkspaceDAO : WorkspaceDAO {
        private val rows = linkedMapOf<String, WorkspaceEntity>()
        private val flow = MutableStateFlow<List<WorkspaceEntity>>(emptyList())
        private val statement = Mutex()
        private val transaction = Mutex()
        var writes = 0
            private set
        var failPresetWriteId: String? = null
        var presetWriteAttempts = mutableListOf<String>()
            private set

        private fun publish() { flow.value = rows.values.sortedByDescending { it.updatedAt } }
        suspend fun snapshot(): Map<String, WorkspaceEntity> = statement.withLock { rows.toMap() }
        suspend fun restore(snapshot: Map<String, WorkspaceEntity>) = statement.withLock {
            rows.clear()
            rows.putAll(snapshot)
            publish()
        }
        private suspend fun patch(id: String, updatedAt: Long, transform: (WorkspaceEntity) -> WorkspaceEntity): Int =
            statement.withLock {
                val current = rows[id] ?: return@withLock 0
                rows[id] = transform(current).copy(updatedAt = updatedAt)
                writes++
                publish()
                1
            }

        override fun listFlow(): Flow<List<WorkspaceEntity>> = flow
        override suspend fun getById(id: String): WorkspaceEntity? = statement.withLock { rows[id] }
        override suspend fun getAll(): List<WorkspaceEntity> = statement.withLock {
            rows.values.sortedByDescending { it.updatedAt }
        }
        override suspend fun insert(workspace: WorkspaceEntity) = statement.withLock {
            check(workspace.id !in rows) { "INSERT aborted: duplicate id ${workspace.id}" }
            rows[workspace.id] = workspace
            writes++
            publish()
        }
        override suspend fun updateName(id: String, name: String, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(name = name) }
        override suspend fun updateSubagentModelId(id: String, modelId: String?, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(subagentModelId = modelId) }
        override suspend fun updateSubagentAllowContext(id: String, allowContext: Boolean, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(subagentAllowContext = allowContext) }
        override suspend fun updateSubagentPresetsJson(id: String, json: String, updatedAt: Long): Int {
            presetWriteAttempts += id
            if (id == failPresetWriteId) throw InjectedFailure()
            return patch(id, updatedAt) { it.copy(subagentPresets = json) }
        }
        override suspend fun updateExposeUpload(id: String, exposed: Boolean, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(exposeUpload = exposed) }
        override suspend fun updateExposeSkills(id: String, exposed: Boolean, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(exposeSkills = exposed) }
        override suspend fun updateExposeToolOutputs(id: String, exposed: Boolean, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(exposeToolOutputs = exposed) }
        override suspend fun updateToolApprovalsJson(id: String, json: String, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(toolApprovals = json) }
        override suspend fun updateToolEnablementJson(id: String, json: String, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(toolEnablement = json) }
        override suspend fun updateLastAccessAt(id: String, lastAccessAt: Long?, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(lastAccessAt = lastAccessAt) }
        override suspend fun touchUpdatedAt(id: String, updatedAt: Long) = patch(id, updatedAt) { it }
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(shellStatus = shellStatus) }
        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(shellCompatibilityMode = enabled) }
        override suspend fun setFirstMessageFileSnapshot(id: String, enabled: Boolean, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(firstMessageFileSnapshot = enabled) }
        override suspend fun setFirstMessageFileSnapshotDepth(id: String, depth: Int, updatedAt: Long) =
            patch(id, updatedAt) { it.copy(firstMessageFileSnapshotDepth = depth) }
        override suspend fun updateInTransaction(id: String, transform: suspend (WorkspaceEntity) -> WorkspaceEntity): WorkspaceEntity? =
            transaction.withLock { yield(); super.updateInTransaction(id, transform) }
        override suspend fun mergeToolApprovals(id: String, patch: Map<String, Boolean>, updatedAt: Long) =
            transaction.withLock { yield(); super.mergeToolApprovals(id, patch, updatedAt) }
        override suspend fun mergeToolEnablement(id: String, patch: Map<String, Boolean>, updatedAt: Long) =
            transaction.withLock { yield(); super.mergeToolEnablement(id, patch, updatedAt) }
        override suspend fun deleteById(id: String): Int = statement.withLock {
            val removed = rows.remove(id) != null
            if (removed) writes++
            publish()
            if (removed) 1 else 0
        }
    }

    private class InjectedFailure : IllegalStateException("Injected transaction failure")

    /** Deliberately not a fake claim of Room: rollback restores immutable snapshots in memory. */
    private class MemoryLibraryStore(private val dao: FakeWorkspaceDAO) : SubagentPresetLibraryStore {
        private val state = MutableStateFlow<SubagentPresetLibraryEntity?>(null)
        private val statement = Mutex()
        private val transaction = Mutex()
        var initializations = 0
            private set
        var casWrites = 0
            private set
        var commits = 0
            private set
        var rollbacks = 0
            private set
        var failAfterCas = false
        var beforeCommit: (suspend () -> Unit)? = null
        var observedBeforeRollback: Pair<SubagentPresetLibraryEntity?, Map<String, WorkspaceEntity>>? = null
            private set

        override fun observe(): Flow<SubagentPresetLibraryEntity?> = state
        override suspend fun read() = statement.withLock { state.value }
        override suspend fun initialize(entity: SubagentPresetLibraryEntity) = statement.withLock {
            if (state.value == null) {
                state.value = entity
                initializations++
            }
            Unit
        }
        override suspend fun compareAndSet(next: SubagentPresetLibraryEntity, expectedRevision: Long): Boolean =
            statement.withLock {
                if (state.value?.revision != expectedRevision) return@withLock false
                state.value = next
                casWrites++
                true
            }
        override suspend fun <T> transaction(block: suspend () -> T): T = transaction.withLock {
            val oldLibrary = read()
            val oldRows = dao.snapshot()
            try {
                val result = block()
                beforeCommit?.invoke()
                if (failAfterCas) {
                    observedBeforeRollback = read() to dao.snapshot()
                    throw InjectedFailure()
                }
                commits++
                result
            } catch (error: Throwable) {
                statement.withLock { state.value = oldLibrary }
                dao.restore(oldRows)
                rollbacks++
                throw error
            }
        }
    }

    private class Harness : Closeable {
        val dir: File = Files.createTempDirectory("shared-presets-offline").toFile()
        val dao = FakeWorkspaceDAO()
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000L)
        private val manager = WorkspaceManager(dir)
        val workspaces = WorkspaceRepository(dao = dao, manager = LocalWorkspaceBackend(manager, RootfsInstaller(manager), java.io.File("/nonexistent-native"), null), settingsStore = detachedSettingsStore(), runtimeGate = gate)
        val store = MemoryLibraryStore(dao)
        val library = SubagentPresetRepository(store, workspaces)
        // IDs are real UUIDs; only names are w1/w2. Target validation intentionally rejects aliases.
        val w1 = UUID.randomUUID().toString()
        val w2 = UUID.randomUUID().toString()
        val ids get() = linkedSetOf(w1, w2)
        suspend fun seed() {
            dao.insert(workspace(w1, "w1"))
            dao.insert(workspace(w2, "w2"))
            library.list()
        }
        suspend fun presets(id: String) = WorkspaceSubagentPresets.parse(dao.getById(id)!!.subagentPresets)
        suspend fun linked(id: String, source: String) = presets(id).single { it.libraryPresetId == source }
        suspend fun definitions() = library.list().presets!!
        override fun close() { dir.deleteRecursively() }
    }

    companion object {
        private fun detachedSettingsStore(): SettingsStore {
            val klass = Class.forName("sun.misc.Unsafe")
            val field = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return klass.getMethod("allocateInstance", Class::class.java)
                .invoke(field.get(null), SettingsStore::class.java) as SettingsStore
        }
        private fun workspace(id: String, name: String) = WorkspaceEntity(
            id = id, name = name, root = id, createdAt = 1, updatedAt = 1,
            shellStatus = WorkspaceShellStatus.READY.name, subagentAllowContext = true,
            toolEnablement = "{\"bash\":false}", toolApprovals = "{\"bash\":true}",
        )
        private fun preset(key: String = "worker") = WorkspaceSubagentPreset(
            key = key, name = "Worker", description = "Implement changes", systemPrompt = "Work carefully",
        )
    }

    private data class Running(val result: Deferred<Result<Unit>>, val release: CompletableDeferred<Unit>)
    private suspend fun CoroutineScope.start(h: Harness, id: String, stableId: String = BuiltinToolIds.SUBAGENT): Running {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async(Dispatchers.Default) {
            runCatching {
                h.gate.withOperation(workspaceId = id, kind = WorkspaceOperationKind.TOOL, stableIds = setOf(stableId)) {
                    started.complete(Unit)
                    release.await()
                }
            }
        }
        withTimeout(3_000) { started.await() }
        return Running(result, release)
    }

    /** Every failed preflight is checked with active subagents in BOTH target workspaces. */
    private suspend fun Harness.noEffects(expected: Class<out Throwable>, action: suspend () -> Unit) = coroutineScope {
        val running = ids.map { start(this@noEffects, it) }
        val rows = dao.snapshot()
        val canonical = store.read()
        val writes = dao.writes
        val cas = store.casWrites
        val epochs = ids.associateWith { gate.currentEpoch(it) }
        val toolEpochs = ids.associateWith { gate.currentToolEpoch(it, BuiltinToolIds.SUBAGENT) }
        val events = ids.associateWith { gate.eventsFlow(it).value }
        try {
            val error = runCatching { action() }.exceptionOrNull()
            assertTrue("Expected ${expected.simpleName}, got $error", expected.isInstance(error))
            assertEquals(rows, dao.snapshot())
            assertEquals(canonical, store.read())
            assertEquals(writes, dao.writes)
            assertEquals(cas, store.casWrites)
            ids.forEach {
                assertEquals(epochs[it], gate.currentEpoch(it))
                assertEquals(toolEpochs[it], gate.currentToolEpoch(it, BuiltinToolIds.SUBAGENT))
                assertEquals(events[it], gate.eventsFlow(it).value)
                assertFalse(gate.snapshot(it).sealed)
            }
            assertTrue(running.all { it.result.isActive })
        } finally {
            running.forEach { it.release.complete(Unit) }
        }
        running.forEach { assertTrue(withTimeout(3_000) { it.result.await() }.isSuccess) }
    }

    @Test
    fun `builtins have stable source IDs initialize exactly once and never auto assign`() = runBlocking {
        Harness().use { h ->
            h.dao.insert(workspace(h.w1, "w1"))
            h.dao.insert(workspace(h.w2, "w2"))
            val original = h.dao.snapshot()
            val states = coroutineScope {
                (1..8).map { async(Dispatchers.Default) { h.library.list() } }.awaitAll()
            }
            val builtin = SubagentPresetRepository.builtInPresets()
            assertEquals(builtin, SubagentPresetRepository.builtInPresets())
            assertEquals(listOf("explorer", "developer", "reviewer", "tester"), builtin.map { it.key })
            builtin.forEach {
                assertEquals(UUID.nameUUIDFromBytes("rikkihub.shared.subagent.${it.key}".toByteArray(Charsets.UTF_8)).toString(), it.id)
                assertNull(it.libraryPresetId)
                assertEquals(24, it.maxSteps)
            }
            assertTrue(states.all { !it.corrupt && it.presets == builtin })
            assertEquals(builtin, h.library.libraryFlow().first().presets)
            assertEquals(1, h.store.initializations)
            assertEquals(original, h.dao.snapshot())
            h.ids.forEach { assertEquals(0L, h.gate.currentEpoch(it)); assertNull(h.gate.eventsFlow(it).value) }
        }
    }

    @Test
    fun `healthy empty singleton after deleting every builtin is never reseeded`() = runBlocking {
        Harness().use { h ->
            h.seed()
            h.definitions().forEach { h.library.delete(it.id, it.version) }
            val empty = h.store.read()!!
            assertEquals("[]", empty.presets)
            repeat(3) { assertEquals(emptyList<WorkspaceSubagentPreset>(), h.definitions()) }
            val reopened = SubagentPresetRepository(h.store, h.workspaces)
            assertEquals(emptyList<WorkspaceSubagentPreset>(), reopened.libraryFlow().first().presets)
            assertFalse(reopened.list().corrupt)
            assertEquals(empty, h.store.read())
            assertEquals(1, h.store.initializations)
            h.ids.forEach { assertTrue(h.presets(it).isEmpty()); assertEquals(0L, h.gate.currentEpoch(it)) }
        }
    }

    @Test
    fun `global display edits project to two workspaces preserving independent rows IDs order and revisions`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val local1 = h.workspaces.createSubagentPreset(h.w1, preset("local_one"))
            val local2 = h.workspaces.createSubagentPreset(h.w2, preset("local_two"))
            val shared = h.library.create(preset(), h.ids)
            val original = h.ids.associateWith { h.dao.getById(it)!! }
            val projections = h.ids.associateWith { h.linked(it, shared.id) }
            assertNotEquals(shared.id, projections[h.w1]!!.id)
            assertNotEquals(projections[h.w1]!!.id, projections[h.w2]!!.id)
            val before = h.store.read()!!
            var checkedInsideTransaction = false
            h.store.beforeCommit = {
                // All projections and canonical CAS are written inside the ONE simulated outer
                // transaction, while EVERY participating real gate is still sealed to admission.
                assertEquals(before.revision + 1, h.store.read()!!.revision)
                h.ids.forEach {
                    assertTrue(h.gate.snapshot(it).sealed)
                    assertEquals("Renamed", h.linked(it, shared.id).name)
                }
                checkedInsideTransaction = true
            }
            val edited = h.library.update(shared.copy(name = "Renamed", description = "New display text",
                sortOrder = 999, version = 999, executionRevision = 999), shared.version)
            assertTrue(checkedInsideTransaction)
            assertEquals(2L, edited.version)
            assertEquals(1L, edited.executionRevision)
            assertEquals(shared.sortOrder, edited.sortOrder)
            h.ids.forEach { id ->
                val old = projections.getValue(id)
                val next = h.linked(id, shared.id)
                assertEquals(old.copy(name = "Renamed", description = "New display text", version = old.version + 1), next)
                val row = h.dao.getById(id)!!
                assertEquals(original.getValue(id).copy(subagentPresets = row.subagentPresets, updatedAt = row.updatedAt), row)
                assertTrue(h.gate.eventsFlow(id).value!!.committed)
                assertEquals(emptySet<String>(), h.gate.eventsFlow(id).value!!.affectedStableIds)
            }
            assertEquals(local1, h.presets(h.w1).first())
            assertEquals(local2, h.presets(h.w2).first())
            assertEquals(before.revision + 1, h.store.read()!!.revision)
            assertEquals(edited, h.definitions().single { it.id == shared.id })
        }
    }

    @Test
    fun `global execution content copies atomically while workspace counters and local ordering remain owned locally`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val local = h.workspaces.createSubagentPreset(h.w1, preset("local"))
            val shared = h.library.create(preset(), h.ids)
            val roles = h.presets(h.w1)
            h.workspaces.reorderSubagentPresets(h.w1, roles.reversed().map { it.id }, roles.associate { it.id to it.version })
            val reorderedLocal = h.presets(h.w1).single { it.id == local.id }
            val before = h.ids.associateWith { h.linked(it, shared.id) }
            val updated = h.library.update(shared.copy(systemPrompt = "Execute new instructions", maxSteps = 120,
                generationConfig = SubagentPresetGenerationConfig(temperature = 0.2f, topP = 0.7f, maxTokens = 123)), 1)
            assertEquals(2L, updated.executionRevision)
            h.ids.forEach {
                val old = before.getValue(it)
                assertEquals(updated.copy(id = old.id, libraryPresetId = shared.id, sortOrder = old.sortOrder,
                    version = old.version + 1, executionRevision = old.executionRevision + 1), h.linked(it, shared.id))
                assertTrue(h.gate.eventsFlow(it).value!!.committed)
            }
            assertEquals(reorderedLocal, h.presets(h.w1).single { it.id == local.id })
            assertEquals(0, h.linked(h.w1, shared.id).sortOrder)
            assertEquals(0L, h.gate.currentToolEpoch(h.w1, BuiltinToolIds.SUBAGENT))
        }
    }

    @Test
    fun `scope edits advance canonical CAS so stale editors cannot restore old applicability`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val original = h.library.create(preset(), setOf(h.w1))
            val bound = h.library.setWorkspaceEnabled(h.w2, original.id, true, original.version)
            assertEquals(2L, bound.version)
            assertEquals(original.executionRevision, bound.executionRevision)
            h.noEffects(SubagentPresetConflictException::class.java) {
                h.library.update(original.copy(name = "Stale edit"), original.version, setOf(h.w1))
            }
            h.noEffects(SubagentPresetConflictException::class.java) { h.library.delete(original.id, original.version) }
            h.noEffects(SubagentPresetConflictException::class.java) {
                h.library.setWorkspaceEnabled(h.w1, original.id, false, original.version)
            }
            val display = h.library.update(bound.copy(name = "Latest edit without scope"), bound.version)
            h.ids.forEach { assertEquals(display.name, h.linked(it, original.id).name) }
            val detached = h.library.update(display, display.version, emptySet())
            assertEquals(display.version + 1, detached.version)
            h.ids.forEach { assertTrue(h.presets(it).isEmpty()) }
        }
    }

    @Test
    fun `global disable keeps raw applicability bind and unbind work without accidentally re enabling`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), setOf(h.w1))
            val disabled = h.library.update(source.copy(enabled = false), source.version)
            val oldId = h.linked(h.w1, source.id).id
            assertFalse(h.linked(h.w1, source.id).enabled)
            val bound = h.library.setWorkspaceEnabled(h.w2, source.id, true, disabled.version)
            h.ids.forEach {
                // Presence/provenance is raw applicability, enabled is the effective execution flag.
                assertEquals(source.id, h.linked(it, source.id).libraryPresetId)
                assertFalse(h.linked(it, source.id).enabled)
            }
            val unbound = h.library.setWorkspaceEnabled(h.w2, source.id, false, bound.version)
            assertFalse(unbound.enabled)
            assertTrue(h.presets(h.w2).isEmpty())
            assertEquals(oldId, h.linked(h.w1, source.id).id)
            val enabled = h.library.update(unbound.copy(enabled = true), unbound.version)
            assertTrue(enabled.enabled)
            assertTrue(h.linked(h.w1, source.id).enabled)
            assertEquals(oldId, h.linked(h.w1, source.id).id)
            assertTrue(h.presets(h.w2).isEmpty())
        }
    }

    private suspend fun revocation(action: String) = coroutineScope {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), h.ids)
            val victim = start(h, h.w1)
            val shell = start(h, h.w1, BuiltinToolIds.WORKSPACE_SHELL)
            val otherWorkspace = start(h, h.w2)
            withTimeout(3_000) {
                when (action) {
                    "unbind" -> h.library.setWorkspaceEnabled(h.w1, source.id, false, source.version)
                    "delete" -> h.library.delete(source.id, source.version)
                    "context" -> h.library.update(source.copy(acceptContext = false), source.version)
                    else -> h.library.update(source.copy(enabled = false), source.version)
                }
            }
            assertTrue(victim.result.await().exceptionOrNull() is WorkspacePolicyCancellationException)
            assertTrue(shell.result.isActive)
            assertEquals(1L, h.gate.currentToolEpoch(h.w1, BuiltinToolIds.SUBAGENT))
            assertEquals(0L, h.gate.currentToolEpoch(h.w1, BuiltinToolIds.WORKSPACE_SHELL))
            assertTrue(h.gate.eventsFlow(h.w1).value!!.committed)
            when (action) {
                "unbind" -> {
                    assertTrue(h.presets(h.w1).isEmpty())
                    assertEquals(source.id, h.linked(h.w2, source.id).libraryPresetId)
                }
                "delete" -> {
                    h.ids.forEach { assertTrue(h.presets(it).isEmpty()) }
                    assertFalse(h.definitions().any { it.id == source.id })
                }
                "context" -> h.ids.forEach { assertFalse(h.linked(it, source.id).acceptContext) }
                else -> h.ids.forEach {
                    assertEquals(source.id, h.linked(it, source.id).libraryPresetId)
                    assertFalse(h.linked(it, source.id).enabled)
                }
            }
            if (action == "unbind") {
                assertTrue(otherWorkspace.result.isActive)
                assertEquals(0L, h.gate.currentToolEpoch(h.w2, BuiltinToolIds.SUBAGENT))
            } else {
                assertTrue(otherWorkspace.result.await().exceptionOrNull() is WorkspacePolicyCancellationException)
                assertEquals(1L, h.gate.currentToolEpoch(h.w2, BuiltinToolIds.SUBAGENT))
            }
            shell.release.complete(Unit)
            otherWorkspace.release.complete(Unit)
            assertTrue(withTimeout(3_000) { shell.result.await() }.isSuccess)
            if (action == "unbind") assertTrue(withTimeout(3_000) { otherWorkspace.result.await() }.isSuccess)
        }
    }

    @Test fun `unbind revokes running subagent only in the removed workspace`() = runBlocking { revocation("unbind") }
    @Test fun `delete revokes running subagents in every linked workspace`() = runBlocking { revocation("delete") }
    @Test fun `context tightening revokes running subagents but leaves unrelated shell alive`() = runBlocking { revocation("context") }
    @Test fun `global disable revokes running subagents while retaining linked snapshots`() = runBlocking { revocation("disable") }

    @Test
    fun `display edits neither cancel running subagents nor change their execution fingerprints`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), h.ids)
            val identities = h.ids.associateWith { workspaceToolIdentity(h.dao.getById(it)!!, BuiltinToolIds.SUBAGENT) }
            coroutineScope {
                val running = h.ids.map { start(h, it) }
                h.library.update(source.copy(name = "Display change", description = "Only presentation"), 1)
                assertTrue(running.all { it.result.isActive })
                h.ids.forEach {
                    requireWorkspaceToolAdmissible(identities.getValue(it), h.dao.getById(it))
                    assertEquals(identities.getValue(it).implementationId,
                        workspaceToolIdentity(h.dao.getById(it)!!, BuiltinToolIds.SUBAGENT).implementationId)
                    assertEquals(1L, h.linked(it, source.id).executionRevision)
                    assertEquals(0L, h.gate.currentToolEpoch(it, BuiltinToolIds.SUBAGENT))
                }
                running.forEach { it.release.complete(Unit) }
                running.forEach { assertTrue(withTimeout(3_000) { it.result.await() }.isSuccess) }
            }
        }
    }

    @Test
    fun `A B A execution revisions are monotonic and old signed fingerprints remain rejected`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val a = h.library.create(preset(), h.ids)
            val oldIdentity = workspaceToolIdentity(h.dao.getById(h.w1)!!, BuiltinToolIds.SUBAGENT)
            val b = h.library.update(a.copy(systemPrompt = "B"), a.version)
            val middleIdentity = workspaceToolIdentity(h.dao.getById(h.w1)!!, BuiltinToolIds.SUBAGENT)
            val again = h.library.update(b.copy(systemPrompt = a.systemPrompt), b.version)
            assertEquals(listOf(1L, 2L, 3L), listOf(a.executionRevision, b.executionRevision, again.executionRevision))
            h.ids.forEach { assertEquals(3L, h.linked(it, a.id).executionRevision) }
            val latest = h.dao.getById(h.w1)!!
            assertEquals(a.systemPrompt, h.linked(h.w1, a.id).systemPrompt)
            for (old in listOf(oldIdentity, middleIdentity)) {
                val failure = runCatching { requireWorkspaceToolAdmissible(old, latest) }.exceptionOrNull()
                assertTrue(failure is WorkspaceToolUnavailableException)
                assertEquals(WorkspaceToolUnavailableException.Reason.CONFIG_INVALID,
                    (failure as WorkspaceToolUnavailableException).reason)
                assertNotEquals(old.implementationId, workspaceToolIdentity(latest, BuiltinToolIds.SUBAGENT).implementationId)
            }
            requireWorkspaceToolAdmissible(workspaceToolIdentity(latest, BuiltinToolIds.SUBAGENT), latest)
        }
    }

    @Test
    fun `unbind rebind allocates new local identity and cannot resurrect a revoked activation epoch`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val a = h.library.create(preset(), setOf(h.w1))
            val old = h.linked(h.w1, a.id)
            val epoch = h.gate.currentToolEpoch(h.w1, BuiltinToolIds.SUBAGENT)
            val unbound = h.library.setWorkspaceEnabled(h.w1, a.id, false, a.version)
            h.library.setWorkspaceEnabled(h.w1, a.id, true, unbound.version)
            assertNotEquals(old.id, h.linked(h.w1, a.id).id)
            assertEquals(epoch + 1, h.gate.currentToolEpoch(h.w1, BuiltinToolIds.SUBAGENT))
            val error = runCatching {
                h.gate.withOperation(h.w1, WorkspaceOperationKind.TOOL, setOf(BuiltinToolIds.SUBAGENT),
                    expectedToolEpochs = mapOf(BuiltinToolIds.SUBAGENT to epoch)) { }
            }.exceptionOrNull()
            assertTrue(error is WorkspaceUnavailableException)
        }
    }

    @Test
    fun `duplicate canonical key is rejected before writes epochs or worker cancellation`() = runBlocking {
        Harness().use { h ->
            h.seed()
            h.library.create(preset(), h.ids)
            h.noEffects(IllegalArgumentException::class.java) { h.library.create(preset(), h.ids) }
        }
    }

    @Test
    fun `key conflict in last target preflights the whole batch and preserves first target`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val last = h.ids.sorted().last()
            h.workspaces.createSubagentPreset(last, preset())
            h.dao.touchUpdatedAt(last, 2)
            h.dao.touchUpdatedAt(h.ids.sorted().first(), 3)
            assertEquals(last, h.dao.getAll().last().id)
            h.noEffects(IllegalArgumentException::class.java) { h.library.create(preset(), h.ids) }
        }
    }

    @Test
    fun `full last target with sixteen local presets rejects batch without partial assignment`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val last = h.ids.sorted().last()
            repeat(16) { h.workspaces.createSubagentPreset(last, preset("local$it")) }
            assertEquals(16, h.presets(last).size)
            h.dao.touchUpdatedAt(last, 2)
            h.dao.touchUpdatedAt(h.ids.sorted().first(), 3)
            assertEquals(last, h.dao.getAll().last().id)
            h.noEffects(IllegalArgumentException::class.java) { h.library.create(preset(), h.ids) }
        }
    }

    @Test
    fun `step bounds reject zero and 121 accept one and 120 and default to 24`() = runBlocking {
        Harness().use { h ->
            h.seed()
            assertEquals(24, preset().maxSteps)
            for (steps in listOf(0, 121)) {
                h.noEffects(IllegalArgumentException::class.java) { h.library.create(preset().copy(maxSteps = steps), h.ids) }
            }
            val source = h.library.create(preset().copy(maxSteps = 1), h.ids)
            assertEquals(1, source.maxSteps)
            val updated = h.library.update(source.copy(maxSteps = 120), source.version)
            assertEquals(120, updated.maxSteps)
            h.ids.forEach { assertEquals(120, h.linked(it, source.id).maxSteps) }
            for (steps in listOf(0, 121)) {
                h.noEffects(IllegalArgumentException::class.java) { h.library.update(updated.copy(maxSteps = steps), updated.version) }
            }
        }
    }

    @Test
    fun `corrupt last target JSON fails closed without touching healthy targets or cancellation`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val last = h.ids.sorted().last()
            h.dao.updateSubagentPresetsJson(last, " { broken presets\n", 7)
            h.dao.touchUpdatedAt(h.ids.sorted().first(), 8)
            assertEquals(last, h.dao.getAll().last().id)
            h.noEffects(SubagentPresetConflictException::class.java) { h.library.create(preset(), h.ids) }
            assertEquals(" { broken presets\n", h.dao.getById(last)!!.subagentPresets)
        }
    }

    @Test
    fun `library supports exactly 64 definitions independently from workspace capacity`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val builtinCount = h.definitions().size
            repeat(64 - builtinCount) { h.library.create(preset("global$it")) }
            assertEquals(64, h.definitions().size)
            h.ids.forEach { assertTrue(h.presets(it).isEmpty()) }
            h.noEffects(IllegalArgumentException::class.java) { h.library.create(preset("overflow"), h.ids) }
        }
    }

    @Test
    fun `invalid missing and over bounded target sets have no publication side effects`() = runBlocking {
        Harness().use { h ->
            h.seed()
            h.noEffects(IllegalArgumentException::class.java) { h.library.create(preset(), setOf("w1")) }
            h.noEffects(NoSuchElementException::class.java) {
                h.library.create(preset(), setOf(h.w1, UUID.randomUUID().toString()))
            }
            val tooMany = (1..257).map { UUID.randomUUID().toString() }.toSet()
            h.noEffects(IllegalArgumentException::class.java) { h.library.create(preset(), tooMany) }
        }
    }

    @Test
    fun `second workspace transaction failure rolls back every row and library with uncommitted events`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), h.ids)
            val rows = h.dao.snapshot()
            val canonical = h.store.read()
            val attempts = h.dao.presetWriteAttempts.size
            val writesBefore = h.dao.writes
            val casBefore = h.store.casWrites
            h.dao.failPresetWriteId = h.ids.sorted().last()
            assertTrue(runCatching { h.library.update(source.copy(name = "Not committed"), 1) }
                .exceptionOrNull() is InjectedFailure)
            assertEquals(h.ids.sorted(), h.dao.presetWriteAttempts.drop(attempts))
            assertEquals(writesBefore + 1, h.dao.writes) // First row really wrote before second row threw.
            assertEquals(casBefore, h.store.casWrites)
            assertEquals(rows, h.dao.snapshot())
            assertEquals(canonical, h.store.read())
            assertEquals(1, h.store.rollbacks)
            h.ids.forEach {
                assertEquals(false, h.gate.eventsFlow(it).value!!.committed)
                assertFalse(h.gate.snapshot(it).sealed)
            }
            // Gates reopen after a transaction failure and the same CAS baseline can be retried.
            h.dao.failPresetWriteId = null
            h.library.update(source.copy(name = "Retry commits"), 1)
            h.ids.forEach { assertTrue(h.gate.eventsFlow(it).value!!.committed) }
        }
    }

    @Test
    fun `failure after library CAS restores both workspace rows and library but revocation is irreversible`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), h.ids)
            val rows = h.dao.snapshot()
            val canonical = h.store.read()!!
            val epoch = h.ids.associateWith { h.gate.currentEpoch(it) }
            coroutineScope {
                val running = h.ids.map { start(h, it) }
                h.store.failAfterCas = true
                assertTrue(runCatching { h.library.update(source.copy(enabled = false), source.version) }
                    .exceptionOrNull() is InjectedFailure)
                running.forEach { assertTrue(it.result.await().exceptionOrNull() is WorkspacePolicyCancellationException) }
            }
            val (inTransactionLibrary, inTransactionRows) = h.store.observedBeforeRollback!!
            assertEquals(canonical.revision + 1, inTransactionLibrary!!.revision)
            assertFalse(SubagentPresetRepository.decode(inTransactionLibrary).single { it.id == source.id }.enabled)
            h.ids.forEach {
                assertFalse(WorkspaceSubagentPresets.parse(inTransactionRows.getValue(it).subagentPresets)
                    .single { p -> p.libraryPresetId == source.id }.enabled)
                assertEquals(epoch.getValue(it) + 1, h.gate.currentEpoch(it))
                assertEquals(1L, h.gate.currentToolEpoch(it, BuiltinToolIds.SUBAGENT))
                assertEquals(false, h.gate.eventsFlow(it).value!!.committed)
                assertFalse(h.gate.snapshot(it).sealed)
            }
            assertEquals(rows, h.dao.snapshot())
            assertEquals(canonical, h.store.read())
            assertEquals(1, h.store.rollbacks)
        }
    }

    @Test
    fun `local generic CRUD cannot forge edit delete or strip linked provenance`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), setOf(h.w1))
            val linked = h.linked(h.w1, source.id)
            h.noEffects(IllegalArgumentException::class.java) {
                h.workspaces.createSubagentPreset(h.w2, preset("forged").copy(libraryPresetId = source.id))
            }
            h.noEffects(SubagentPresetConflictException::class.java) {
                h.workspaces.updateSubagentPreset(h.w1, linked.copy(name = "Local edit"), linked.version)
            }
            h.noEffects(SubagentPresetConflictException::class.java) {
                h.workspaces.deleteSubagentPreset(h.w1, linked.id, linked.version)
            }
            for (entry in listOf(linked.copy(name = "Generic edit"), linked.copy(libraryPresetId = null))) {
                h.noEffects(SubagentPresetConflictException::class.java) {
                    h.workspaces.applyConfigPatch(h.w1) { it.copy(subagentPresets = WorkspaceSubagentPresets.encode(listOf(entry))) }
                }
            }
            h.noEffects(SubagentPresetConflictException::class.java) {
                h.workspaces.update(h.w1) { it.copy(subagentPresets = "[]") }
            }
            h.noEffects(SubagentPresetConflictException::class.java) {
                h.workspaces.applyConfigPatch(h.w2) { it.copy(subagentPresets = WorkspaceSubagentPresets.encode(listOf(linked))) }
            }
            val local = h.workspaces.createSubagentPreset(h.w2, preset("local"))
            h.noEffects(SubagentPresetConflictException::class.java) {
                h.workspaces.updateSubagentPreset(h.w2, local.copy(libraryPresetId = source.id), local.version)
            }
        }
    }

    @Test
    fun `explicit clone is independent and survives global edits and deletion`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), h.ids)
            val linked = h.linked(h.w1, source.id)
            val clone = h.workspaces.createSubagentPreset(h.w1, linked.copy(key = "worker_copy", libraryPresetId = null))
            assertNull(clone.libraryPresetId)
            assertNotEquals(linked.id, clone.id)
            assertNotEquals(source.id, clone.id)
            val editedClone = h.workspaces.updateSubagentPreset(h.w1, clone.copy(systemPrompt = "Independent instructions"), clone.version)
            val updated = h.library.update(source.copy(systemPrompt = "Shared instructions"), source.version)
            assertEquals(editedClone, h.presets(h.w1).single { it.id == clone.id })
            h.library.delete(source.id, updated.version)
            assertEquals(listOf(editedClone), h.presets(h.w1))
            assertTrue(h.presets(h.w2).isEmpty())
            assertFalse(h.definitions().any { it.id == clone.id || it.id == source.id })
        }
    }

    @Test
    fun `multiple preset edits with reversed workspace IDs and concurrent local patches never lose updates or deadlock`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val forward = h.ids.sorted().toCollection(linkedSetOf())
            val reverse = h.ids.sortedDescending().toCollection(linkedSetOf())
            val a = h.library.create(preset("alpha"), forward)
            val b = h.library.create(preset("beta"), reverse)
            val before = h.ids.associateWith { h.presets(it).associateBy { p -> p.libraryPresetId } }
            withTimeout(5_000) {
                coroutineScope {
                    awaitAll(
                        async(Dispatchers.Default) { h.library.update(a.copy(systemPrompt = "Alpha changed"), a.version, reverse) },
                        async(Dispatchers.Default) { h.library.update(b.copy(systemPrompt = "Beta changed"), b.version, forward) },
                        async(Dispatchers.Default) { h.workspaces.rename(h.w1, "Renamed workspace") },
                        async(Dispatchers.Default) { h.workspaces.setToolApproval(h.w2, BuiltinToolIds.WORKSPACE_SHELL, false) },
                    )
                }
            }
            h.ids.forEach { id ->
                assertEquals(setOf(a.id, b.id), h.presets(id).map { it.libraryPresetId }.toSet())
                for ((source, prompt) in listOf(a.id to "Alpha changed", b.id to "Beta changed")) {
                    val old = before.getValue(id).getValue(source)
                    assertEquals(old.copy(systemPrompt = prompt, version = 2, executionRevision = 2), h.linked(id, source))
                }
                assertFalse(h.gate.snapshot(id).sealed)
                assertTrue(h.gate.eventsFlow(id).value!!.committed)
            }
            assertEquals("Renamed workspace", h.dao.getById(h.w1)!!.name)
            assertEquals(mapOf("bash" to true, BuiltinToolIds.WORKSPACE_SHELL to false), h.dao.getById(h.w2)!!.toolApprovalOverrides())
            assertEquals("{\"bash\":false}", h.dao.getById(h.w1)!!.toolEnablement)
            assertEquals(2L, h.definitions().single { it.id == a.id }.version)
            assertEquals(2L, h.definitions().single { it.id == b.id }.version)
        }
    }

    @Test
    fun `concurrent canonical editors with same expected version have exactly one winner`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), h.ids)
            val results = withTimeout(5_000) {
                coroutineScope {
                    listOf("First", "Second").map { name -> async(Dispatchers.Default) {
                        runCatching { h.library.update(source.copy(name = name), source.version, h.ids.reversed().toSet()) }
                    } }.awaitAll()
                }
            }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is SubagentPresetConflictException })
            val winner = results.single { it.isSuccess }.getOrThrow()
            assertEquals(winner, h.definitions().single { it.id == source.id })
            h.ids.forEach { assertEquals(winner.name, h.linked(it, source.id).name); assertEquals(2L, h.linked(it, source.id).version) }
        }
    }

    @Test
    fun `unassigned new definition is independent from unrelated corrupt workspace JSON`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val corrupt = " { broken legacy presets\n"
            h.dao.updateSubagentPresetsJson(h.w2, corrupt, 8)
            val rows = h.dao.snapshot()
            val source = h.library.create(preset("unassigned"))
            assertEquals(source, h.definitions().single { it.id == source.id })
            assertEquals(rows, h.dao.snapshot())
            h.ids.forEach { assertEquals(0L, h.gate.currentEpoch(it)) }
            val assigned = h.library.create(preset("healthy_target"), setOf(h.w1))
            assertEquals(assigned.id, h.linked(h.w1, assigned.id).libraryPresetId)
            assertEquals(corrupt, h.dao.getById(h.w2)!!.subagentPresets)
            assertEquals(0L, h.gate.currentEpoch(h.w2))
        }
    }

    @Test
    fun `explicit recovery replaces invalid storage revisions with a healthy CAS revision`() = runBlocking {
        for (invalidRevision in listOf(-2L, 0L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            Harness().use { h ->
                h.seed()
                val shared = h.library.create(preset(), h.ids)
                val local = h.workspaces.createSubagentPreset(h.w1, preset("local"))
                val baseline = h.store.read()!!
                assertTrue(h.store.compareAndSet(baseline.copy(presets = "{broken", revision = invalidRevision), baseline.revision))
                assertTrue(h.library.list().corrupt)
                h.library.reset()
                assertFalse(h.library.list().corrupt)
                assertTrue(h.definitions().isEmpty())
                assertEquals(1L, h.store.read()!!.revision)
                assertEquals(listOf(local), h.presets(h.w1))
                assertTrue(h.presets(h.w2).isEmpty())
                val next = h.library.create(preset("recovered"), setOf(h.w2))
                assertEquals(next.id, h.linked(h.w2, next.id).libraryPresetId)
                assertFalse(h.definitions().any { it.id == shared.id })
            }
        }
    }

    @Test
    fun `corrupt canonical library is not reseeded and explicit reset removes only shared projections`() = runBlocking {
        Harness().use { h ->
            h.seed()
            val source = h.library.create(preset(), h.ids)
            val local = h.workspaces.createSubagentPreset(h.w1, preset("local"))
            val canonical = h.store.read()!!
            h.store.compareAndSet(canonical.copy(presets = "{broken-library", revision = canonical.revision + 1), canonical.revision)
            val corrupt = h.store.read()
            assertTrue(h.library.list().corrupt)
            assertNull(h.library.libraryFlow().first().presets)
            h.noEffects(SubagentPresetConflictException::class.java) { h.library.update(source, source.version) }
            assertEquals(corrupt, h.store.read())
            assertEquals(1, h.store.initializations)
            h.library.reset()
            assertEquals(listOf(local), h.presets(h.w1))
            assertTrue(h.presets(h.w2).isEmpty())
            assertEquals(emptyList<WorkspaceSubagentPreset>(), h.definitions())
            h.noEffects(IllegalArgumentException::class.java) { h.library.reset() }
        }
    }
}
