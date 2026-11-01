package me.rerere.rikkihub.data.management

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.management.ports.RoomManagementBackend
import me.rerere.rikkihub.data.management.proposal.Provenance
import me.rerere.rikkihub.data.management.recovery.AesGcmPayloadCipher
import me.rerere.rikkihub.data.management.runtime.AppSkillsBackend
import me.rerere.rikkihub.data.management.runtime.ManagementRuntime
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.mutation.ConversationCommandApplier
import me.rerere.rikkihub.data.mutation.ConversationCommandContext
import me.rerere.rikkihub.data.mutation.ConversationHostIds
import me.rerere.rikkihub.data.mutation.ConversationMutationCommand
import me.rerere.rikkihub.data.mutation.ConversationVersion
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.data.mutation.MutationDomain
import me.rerere.rikkihub.data.mutation.MutationRuntime
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.uuid.Uuid

/**
 * Shared JVM fixture for the management chain: real Preferences DataStore, real atomic
 * management files with AES-GCM recovery images, real user-skill directory, and an in-memory
 * Room backend that enforces the same CAS rules as ChatService.
 */
internal class FakeManagementRoom(private val epochSource: suspend () -> Long) : RoomManagementBackend {
    val conversations = linkedMapOf<Uuid, Conversation>()
    val receipts = hashMapOf<String, String>()
    var busy: String? = null
    var calls = 0
    override suspend fun epoch() = epochSource()
    override suspend fun conversationRow(id: Uuid) = conversations[id]?.copy(messageNodes = emptyList())
    override suspend fun conversationTree(id: Uuid) = conversations[id]
    override suspend fun isDeleted(id: Uuid) = conversations[id]?.deletedAt != null
    override fun busyReason(id: Uuid) = busy
    override suspend fun referenceRows() = conversations.values.filter { it.deletedAt == null }.map {
        RoomManagementBackend.ReferenceRow(it.id.toString(), it.modeInjectionIds.toString(), it.lorebookIds.toString(), it.revision, it.generation)
    }
    override suspend fun applyConversationCommand(
        id: Uuid, expected: ConversationVersion?, operationId: String,
        command: ConversationMutationCommand, hostIds: ConversationHostIds,
    ): ConversationVersion {
        calls++
        receipts[operationId]?.let { return conversations.getValue(id).let { ConversationVersion(0, it.generation, it.revision) } }
        val current = conversations[id] ?: throw MutationConflictException("missing")
        if (expected != null && (current.revision != expected.revision || current.generation != expected.generation))
            throw MutationConflictException("stale")
        val next = when (command) {
            ConversationMutationCommand.SoftDelete -> current.copy(deletedAt = 1, generation = current.generation + 1, revision = current.revision + 1)
            ConversationMutationCommand.Restore -> current.copy(deletedAt = null, generation = current.generation + 1)
            else -> ConversationCommandApplier.apply(current, command, hostIds,
                ConversationCommandContext(setOf(current.assistantId), emptySet(), emptySet(), emptySet()))
                .conversation.copy(revision = current.revision + 1)
        }
        conversations[id] = next
        receipts[operationId] = "${next.generation}:${next.revision}"
        return ConversationVersion(0, next.generation, next.revision)
    }
    override suspend fun receiptAfter(operationId: String) = receipts[operationId]
    override suspend fun writeReceipt(operationId: String, objectKey: String, after: String) { receipts[operationId] = after }
    val libraryPresets = mutableListOf<WorkspaceSubagentPreset>()
    val workspaceRows = mutableListOf<RoomManagementBackend.WorkspaceRow>()
    var libraryRev = 0L
    override suspend fun library() = libraryPresets.toList()
    override suspend fun libraryRevision() = libraryRev
    override suspend fun workspaces() = workspaceRows.toList()
    override suspend fun libraryCreate(preset: WorkspaceSubagentPreset, workspaceIds: Set<String>) {
        libraryPresets += preset.copy(sortOrder = libraryPresets.size); libraryRev++
    }
    override suspend fun libraryUpdate(preset: WorkspaceSubagentPreset, expectedVersion: Long, workspaceIds: Set<String>?) = error("unused")
    override suspend fun libraryDelete(presetId: String, expectedVersion: Long) = error("unused")
    override suspend fun libraryReorder(orderedIds: List<String>) = error("unused")
    override suspend fun localCreate(workspaceId: String, preset: WorkspaceSubagentPreset) = error("unused")
    override suspend fun localUpdate(workspaceId: String, preset: WorkspaceSubagentPreset, expectedVersion: Long) = error("unused")
    override suspend fun localDelete(workspaceId: String, presetId: String, expectedVersion: Long) = error("unused")
    override suspend fun localReorder(workspaceId: String, orderedIds: List<String>, expectedVersions: Map<String, Long>) = error("unused")
}

internal class ManagementFixture {
    val root: File = Files.createTempDirectory("management-runtime-").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mutation = MutationRuntime(File(root, "mutation"), File(root, "restore.marker"))
    val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
    val settings = SettingsStore.forTesting(dataStore, mutation, scope)
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val room = FakeManagementRoom { mutation.epochs.epoch(MutationDomain.ROOM_CONVERSATION) }
    var gateOpen = true
    val filesRoot = File(root, "files").also { File(it, "skills").mkdirs() }
    fun newRuntime() = ManagementRuntime(
        root = File(root, "management"), settingsStore = settings, mutationRuntime = mutation,
        roomBackend = room,
        skillsBackend = AppSkillsBackend(filesRoot, File(root, "management/receipts/files"), mutation),
        cipher = AesGcmPayloadCipher { key },
        mutationAccepted = { gateOpen }, definitionVersion = { 1 },
    )
    var runtime = newRuntime()
    val source = Provenance("session-1", "run-1", "call-1")

    suspend fun approve(proposalId: String) = runtime.review(proposalId).second.let { runtime.approveAndApply(it) }
    fun close() { scope.cancel(); root.deleteRecursively() }
}

internal fun managementFixture(block: suspend (ManagementFixture) -> Unit) = runBlocking {
    val f = ManagementFixture()
    try { block(f) } finally { f.close() }
}

