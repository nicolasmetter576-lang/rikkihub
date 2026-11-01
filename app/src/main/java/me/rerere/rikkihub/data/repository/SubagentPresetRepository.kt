package me.rerere.rikkihub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.ai.tools.SubagentPresetConflictException
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.workspaceSubagentPresetTemplates
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.entity.SubagentPresetLibraryEntity
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import java.util.UUID
import kotlin.uuid.Uuid

@Serializable
data class SubagentPresetLibraryState(
    val presets: List<WorkspaceSubagentPreset>?,
    val corrupt: Boolean,
)

/** Transaction adapter also enables deterministic offline rollback/concurrency tests. */
internal interface SubagentPresetLibraryStore {
    fun observe(): Flow<SubagentPresetLibraryEntity?>
    suspend fun read(): SubagentPresetLibraryEntity?
    suspend fun initialize(entity: SubagentPresetLibraryEntity)
    suspend fun compareAndSet(next: SubagentPresetLibraryEntity, expectedRevision: Long): Boolean
    suspend fun <T> transaction(block: suspend () -> T): T
}

private class RoomSubagentPresetLibraryStore(private val db: AppDatabase) : SubagentPresetLibraryStore {
    private val dao = db.subagentPresetLibraryDao()
    override fun observe() = dao.observe()
    override suspend fun read() = dao.read()
    override suspend fun initialize(entity: SubagentPresetLibraryEntity) { dao.initialize(entity) }
    override suspend fun compareAndSet(next: SubagentPresetLibraryEntity, expectedRevision: Long) =
        dao.compareAndSet(next.presets, next.revision, expectedRevision) == 1
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
}

/**
 * Canonical shared definitions plus gated, transactionally materialized workspace projections.
 * Execution never performs a second library lookup: the signed workspace request remains frozen.
 * Lock order: library mutation mutex -> sorted workspace gates -> ONE Room write transaction.
 */
class SubagentPresetRepository internal constructor(
    private val store: SubagentPresetLibraryStore,
    private val workspaces: WorkspaceRepository,
) {
    constructor(database: AppDatabase, workspaceRepository: WorkspaceRepository) :
        this(RoomSubagentPresetLibraryStore(database), workspaceRepository)

    companion object {
        const val MAX_LIBRARY_PRESETS = 64
        const val MAX_TARGET_WORKSPACES = 256

        /** Stable source identities; initialization creates definitions, never assignments. */
        fun builtInPresets(): List<WorkspaceSubagentPreset> = workspaceSubagentPresetTemplates().mapIndexed { order, preset ->
            preset.copy(
                id = UUID.nameUUIDFromBytes("rikkihub.shared.subagent.${preset.key}".toByteArray(Charsets.UTF_8)).toString(),
                sortOrder = order,
            )
        }

        internal fun decode(entity: SubagentPresetLibraryEntity): List<WorkspaceSubagentPreset> {
            require(entity.id == 1 && entity.revision in 1 until Long.MAX_VALUE) { "Invalid preset library state" }
            val presets = WorkspaceSubagentPresets.parse(entity.presets, MAX_LIBRARY_PRESETS)
            require(presets.all { it.libraryPresetId == null }) { "Library definitions cannot themselves be linked" }
            return presets.sortedWith(compareBy({ it.sortOrder }, { it.key }))
        }
    }

    private val mutationMutex = Mutex()

    private suspend fun initialized(): SubagentPresetLibraryEntity {
        store.read()?.let { return it }
        store.initialize(SubagentPresetLibraryEntity(
            presets = WorkspaceSubagentPresets.encode(builtInPresets(), MAX_LIBRARY_PRESETS),
        ))
        return store.read() ?: error("Preset library initialization failed")
    }

    fun libraryFlow(): Flow<SubagentPresetLibraryState> = flow {
        initialized()
        emitAll(store.observe().map { entity ->
            val presets = entity?.let { runCatching { decode(it) }.getOrNull() }
            SubagentPresetLibraryState(presets, presets == null)
        })
    }

    suspend fun list(): SubagentPresetLibraryState {
        val entity = initialized()
        val presets = runCatching { decode(entity) }.getOrNull()
        return SubagentPresetLibraryState(presets, presets == null)
    }

    /**
     * [assignedId] is used only by the management gateway, which freezes the host-allocated
     * ID into an approved proposal (and by restore of a tombstoned definition). Ordinary
     * callers never choose server IDs.
     */
    suspend fun create(
        preset: WorkspaceSubagentPreset,
        workspaceIds: Set<String> = emptySet(),
        assignedId: String? = null,
    ): WorkspaceSubagentPreset {
        val targets = boundedTargets(workspaceIds)
        require(preset.libraryPresetId == null) { "A library definition cannot be a linked workspace snapshot" }
        assignedId?.let { require(runCatching { Uuid.parse(it) }.isSuccess) { "Invalid assigned preset id" } }
        val normalized = WorkspaceSubagentPresets.normalize(preset).copy(
            id = assignedId ?: Uuid.random().toString(), version = 1, executionRevision = 1, libraryPresetId = null,
        )
        WorkspaceSubagentPresets.validate(normalized)
        return mutationMutex.withLock {
            val baseline = initialized()
            val current = healthyLibrary(baseline)
            require(current.size < MAX_LIBRARY_PRESETS) { "At most $MAX_LIBRARY_PRESETS shared presets are allowed" }
            if (current.any { it.id == normalized.id }) throw SubagentPresetConflictException("Preset id already exists")
            val next = normalized.copy(sortOrder = current.maxOfOrNull { it.sortOrder }?.let { Math.addExact(it, 1) } ?: 0)
            val updated = current + next
            WorkspaceSubagentPresets.validateAll(updated, MAX_LIBRARY_PRESETS)
            publishMutation(baseline, updated, next.id, next, targets)
            next
        }
    }

    /** Absent workspaceIds preserves latest applicability; an explicit empty set detaches all. */
    suspend fun update(
        preset: WorkspaceSubagentPreset,
        expectedVersion: Long,
        workspaceIds: Set<String>? = null,
    ): WorkspaceSubagentPreset {
        val targets = workspaceIds?.let(::boundedTargets)
        require(preset.libraryPresetId == null) { "A library definition cannot be a linked workspace snapshot" }
        val normalized = WorkspaceSubagentPresets.normalize(preset)
        WorkspaceSubagentPresets.validate(normalized)
        return mutationMutex.withLock {
            val baseline = initialized()
            val current = healthyLibrary(baseline)
            val old = current.firstOrNull { it.id == normalized.id }
                ?: throw NoSuchElementException("Shared subagent preset not found")
            requireVersion(old, expectedVersion)
            require(normalized.key == old.key) { "Preset key is immutable" }
            val next = normalized.copy(
                sortOrder = old.sortOrder,
                version = Math.addExact(old.version, 1),
                executionRevision = if (WorkspaceSubagentPresets.sameExecution(old, normalized)) old.executionRevision
                    else Math.addExact(old.executionRevision, 1),
            )
            publishMutation(baseline, current.map { if (it.id == old.id) next else it }, next.id, next, targets)
            next
        }
    }

    suspend fun delete(presetId: String, expectedVersion: Long) = mutationMutex.withLock {
        val baseline = initialized()
        val current = healthyLibrary(baseline)
        val old = current.firstOrNull { it.id == presetId }
            ?: throw NoSuchElementException("Shared subagent preset not found")
        requireVersion(old, expectedVersion)
        publishMutation(baseline, current.filterNot { it.id == presetId }, presetId, null, emptySet())
    }

    /** A binding edit increments canonical CAS: another editor cannot overwrite the new target set. */
    suspend fun setWorkspaceEnabled(
        workspaceId: String,
        presetId: String,
        enabled: Boolean,
        expectedVersion: Long,
    ): WorkspaceSubagentPreset = mutationMutex.withLock {
        val baseline = initialized()
        val current = healthyLibrary(baseline)
        val old = current.firstOrNull { it.id == presetId }
            ?: throw NoSuchElementException("Shared subagent preset not found")
        requireVersion(old, expectedVersion)
        val rows = workspaces.list()
        if (rows.none { it.id == workspaceId }) throw NoSuchElementException("Workspace not found: $workspaceId")
        val targets = linkedWorkspaceIds(rows, presetId).toMutableSet()
        if (enabled) targets += workspaceId else targets -= workspaceId
        val next = old.copy(version = Math.addExact(old.version, 1))
        publishMutation(baseline, current.map { if (it.id == old.id) next else it }, presetId, next, boundedTargets(targets))
        next
    }

    /** Global library order: exact membership, server-owned sortOrder, one CAS publication. */
    suspend fun reorder(orderedIds: List<String>) = mutationMutex.withLock {
        val baseline = initialized()
        val current = healthyLibrary(baseline)
        require(orderedIds.size == current.size && orderedIds.toSet() == current.map { it.id }.toSet()) {
            "Library order must contain the entire exact ID set without duplicates"
        }
        val byId = current.associateBy { it.id }
        val reordered = orderedIds.mapIndexed { order, id ->
            val old = byId.getValue(id)
            if (old.sortOrder == order) old else old.copy(sortOrder = order, version = Math.addExact(old.version, 1))
        }
        store.transaction {
            requireUnchangedLibrary(baseline)
            val next = baseline.copy(
                presets = WorkspaceSubagentPresets.encode(reordered, MAX_LIBRARY_PRESETS),
                revision = Math.addExact(baseline.revision, 1),
            )
            if (!store.compareAndSet(next, baseline.revision)) throw SubagentPresetConflictException()
        }
    }

    /** Library CAS revision (management read-set baseline). */
    suspend fun libraryRevision(): Long = initialized().revision

    /** Explicit recovery removes all linked snapshots through the same revoking publication path. */
    suspend fun reset() = mutationMutex.withLock {
        val baseline = initialized()
        require(runCatching { decode(baseline) }.isFailure) { "Only a corrupt library can be reset" }
        val initialRows = workspaces.list()
        val affected = initialRows.filter { parseWorkspace(it).any { preset -> preset.libraryPresetId != null } }
        val ids = affected.map { it.id }
        workspaces.publishPresetLibraryBatch(ids, prepare = {
            requireUnchangedLibrary(baseline)
            val rows = workspaces.list()
            val currentLinked = rows.filter { parseWorkspace(it).any { p -> p.libraryPresetId != null } }
            if (currentLinked.map { it.id }.toSet() != ids.toSet()) throw SubagentPresetConflictException()
            currentLinked.associate { row ->
                row.id to (row to row.copy(subagentPresets = WorkspaceSubagentPresets.encode(
                    parseWorkspace(row).filter { it.libraryPresetId == null },
                )))
            }
        }, publish = { plans ->
            store.transaction {
                requireUnchangedLibrary(baseline)
                workspaces.writePreparedPresetLibraryBatch(plans)
                val recoveredRevision = if (baseline.revision in 1 until Long.MAX_VALUE - 1) baseline.revision + 1 else 1L
                val cleared = baseline.copy(presets = "[]", revision = recoveredRevision)
                if (!store.compareAndSet(cleared, baseline.revision)) throw SubagentPresetConflictException()
            }
        })
    }

    private fun healthyLibrary(entity: SubagentPresetLibraryEntity): List<WorkspaceSubagentPreset> =
        runCatching { decode(entity) }.getOrElse {
            throw SubagentPresetConflictException("Preset library is corrupt; explicitly repair it first")
        }

    private suspend fun requireUnchangedLibrary(expected: SubagentPresetLibraryEntity) {
        if (store.read() != expected) throw SubagentPresetConflictException("Preset library changed concurrently")
    }

    private fun requireVersion(preset: WorkspaceSubagentPreset, expected: Long) {
        require(expected > 0) { "expectedVersion must be positive" }
        if (preset.version != expected) throw SubagentPresetConflictException()
    }

    private fun boundedTargets(ids: Set<String>): Set<String> = ids.toSet().also { copy ->
        require(copy.size <= MAX_TARGET_WORKSPACES) { "At most $MAX_TARGET_WORKSPACES target workspaces are allowed" }
        require(copy.all { runCatching { Uuid.parse(it) }.isSuccess }) { "Invalid target workspace id" }
    }

    private fun parseWorkspace(row: WorkspaceEntity): List<WorkspaceSubagentPreset> =
        WorkspaceSubagentPresets.tryParse(row.subagentPresets)
            ?: throw SubagentPresetConflictException("Workspace '${row.name}' has corrupt presets; repair it before changing shared presets")

    private fun linkedWorkspaceIds(rows: List<WorkspaceEntity>, presetId: String): Set<String> =
        rows.filter { row -> parseWorkspace(row).any { it.libraryPresetId == presetId } }.mapTo(linkedSetOf()) { it.id }

    private suspend fun publishMutation(
        baseline: SubagentPresetLibraryEntity,
        library: List<WorkspaceSubagentPreset>,
        sourceId: String,
        definition: WorkspaceSubagentPreset?,
        requestedTargets: Set<String>?,
    ) {
        WorkspaceSubagentPresets.validateAll(library, MAX_LIBRARY_PRESETS)
        val initialRows = workspaces.list()
        // A fresh server-allocated source cannot have legitimate old projections. Unrelated
        // corrupt workspace JSON must not block creating an unassigned library definition.
        val existingSource = decode(baseline).any { it.id == sourceId }
        val oldTargets = if (existingSource) linkedWorkspaceIds(initialRows, sourceId) else emptySet()
        val desired = requestedTargets ?: oldTargets
        val known = initialRows.map { it.id }.toSet()
        if (!known.containsAll(desired)) throw NoSuchElementException("One or more target workspaces no longer exist")
        val affected = (oldTargets + desired).sorted()
        // New workspace identities allocated ONCE, not inside the repeatable preview transform.
        val allocations = affected.associateWith { Uuid.random().toString() }
        // Complete preflight before advancing epochs or canceling any worker.
        initialRows.filter { it.id in affected }.forEach { row ->
            materialize(row, sourceId, definition, row.id in desired, allocations.getValue(row.id))
        }
        workspaces.publishPresetLibraryBatch(affected, prepare = {
            requireUnchangedLibrary(baseline)
            val currentRows = workspaces.list()
            if (existingSource && linkedWorkspaceIds(currentRows, sourceId) != oldTargets) {
                throw SubagentPresetConflictException("Workspace applicability changed concurrently")
            }
            val byId = currentRows.associateBy { it.id }
            affected.associateWith { id ->
                val current = byId[id] ?: throw NoSuchElementException("Workspace not found: $id")
                current to materialize(current, sourceId, definition, id in desired, allocations.getValue(id))
            }
        }, publish = { plans ->
            store.transaction {
                requireUnchangedLibrary(baseline)
                // Workspace deletion is serialized by each gate; membership is rechecked in Room.
                if (existingSource && linkedWorkspaceIds(workspaces.list(), sourceId) != oldTargets) {
                    throw SubagentPresetConflictException()
                }
                workspaces.writePreparedPresetLibraryBatch(plans)
                val next = baseline.copy(
                    presets = WorkspaceSubagentPresets.encode(library, MAX_LIBRARY_PRESETS),
                    revision = Math.addExact(baseline.revision, 1),
                )
                if (!store.compareAndSet(next, baseline.revision)) throw SubagentPresetConflictException()
            }
        })
    }

    private fun materialize(
        row: WorkspaceEntity,
        sourceId: String,
        definition: WorkspaceSubagentPreset?,
        assigned: Boolean,
        newId: String,
    ): WorkspaceEntity {
        val entries = parseWorkspace(row)
        val old = entries.firstOrNull { it.libraryPresetId == sourceId }
        val nextEntries = if (!assigned || definition == null) {
            entries.filterNot { it.libraryPresetId == sourceId }
        } else {
            require(entries.none { it.libraryPresetId != sourceId && it.key == definition.key }) {
                "Workspace '${row.name}' already has preset key '${definition.key}'; keep it independent, or clone it under another key and remove the conflicting local role before enabling this shared role"
            }
            val base = definition.copy(
                id = old?.id ?: newId,
                libraryPresetId = sourceId,
                sortOrder = old?.sortOrder ?: entries.maxOfOrNull { it.sortOrder }?.let { Math.addExact(it, 1) } ?: 0,
                version = old?.version ?: 1,
                executionRevision = old?.executionRevision ?: 1,
            )
            val projected = if (old == null || old == base) base else base.copy(
                version = Math.addExact(old.version, 1),
                executionRevision = if (WorkspaceSubagentPresets.sameExecution(old, base)) old.executionRevision
                    else Math.addExact(old.executionRevision, 1),
            )
            if (old == null) entries + projected else entries.map { if (it.id == old.id) projected else it }
        }
        try {
            WorkspaceSubagentPresets.validateAll(nextEntries)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Workspace '${row.name}': ${error.message}", error)
        }
        return if (nextEntries == entries) row else row.copy(subagentPresets = WorkspaceSubagentPresets.encode(nextEntries))
    }
}
