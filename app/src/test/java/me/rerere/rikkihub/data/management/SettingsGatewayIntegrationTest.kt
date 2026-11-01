package me.rerere.rikkihub.data.management

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.rikkihub.data.datastore.*
import me.rerere.rikkihub.data.management.adapters.*
import me.rerere.rikkihub.data.management.common.*
import me.rerere.rikkihub.data.management.coordinator.*
import me.rerere.rikkihub.data.management.persistence.FileBackedManagementStore
import me.rerere.rikkihub.data.management.ports.*
import me.rerere.rikkihub.data.management.proposal.*
import me.rerere.rikkihub.data.management.recovery.AesGcmPayloadCipher
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.data.mutation.MutationRuntime
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.uuid.Uuid

/** Atomic file journal + real disk Preferences DataStore + real AES-GCM; gate is test-only. */
class SettingsGatewayIntegrationTest {
    private class Fixture {
        val root = Files.createTempDirectory("settings-gateway-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtime = MutationRuntime(File(root, "runtime"), File(root, "restore.marker"))
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
        val settings = SettingsStore.forTesting(dataStore, runtime, scope)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val metadata = FileBackedManagementStore(File(root, "private-management"), AesGcmPayloadCipher { key })
        val fences = ObjectFenceRegistry(metadata)
        val port = SettingsManagementPort(settings)
        val gateway = SettingsStoreGateway(port, metadata, metadata)
        val testGate = object : ManagementWriteGate { override fun status() = WriteGateStatus.Enabled }
        val authority = NativeApprovalAuthority({ 1 }, { 1 })
        val service = CreatorProposalService(metadata, metadata, metadata, fences, authority, testGate, { 1 })
        val coordinator = ManagementMutationCoordinator(mapOf(StoreKind.SETTINGS_DATASTORE to gateway),
            metadata, metadata, DomainLeaseRegistry(), fences, metadata, testGate)
        val ids = object : HostIdAllocator {
            override fun newObjectId() = Uuid.random().toString()
            override fun newEntryId(scope: String) = newObjectId()
        }
        suspend fun plan(command: SettingsDomainCommand): Pair<SettingsDomainAdapter, PlannedMutation> {
            val adapter = SettingsDomainAdapter(command.domain)
            val epoch = gateway.storageEpoch()
            return adapter to adapter.plan(PlanContext(port, System.currentTimeMillis(), epoch, ids), command)
        }
        suspend fun apply(command: SettingsDomainCommand): Proposal {
            val (adapter, plan) = plan(command)
            val proposal = service.previewPlan(command.domain,
                SettingsCollectionInterpreter.strictJson.encodeToString(SettingsDomainCommand.serializer(), command),
                plan, Provenance("session", "run", "new-response-call"))
            val (_, review) = service.nativeReview(proposal.proposalId)
            service.claimApprovedForApply(service.approveNative(review))
            return coordinator.applyClaimed(proposal.proposalId, adapter)
        }
        fun close() { scope.cancel(); root.deleteRecursively() }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(); try { block(fixture) } finally { fixture.close() }
    }
    private fun create(title: String = "hello") = SettingsDomainCommand("settings.quickMessages",
        SettingsAction.Create(mapOf("title" to JsonPrimitive(title), "content" to JsonPrimitive("ordinary content"))))

    @Test(timeout = 30_000) fun nativeClaimCommitsOnceAndReleasesAllParticipationFences() = runBlocking<Unit> {
        fixture { f ->
            val applied = f.apply(create())
            assertEquals(ProposalStatus.APPLIED, applied.status)
            assertEquals(1, f.settings.settingsSnapshot().quickMessages.size)
            assertTrue(f.fences.fencedRefs().isEmpty())
            assertTrue(f.gateway.pendingParticipations().isEmpty())
            val again = f.coordinator.applyClaimed(applied.proposalId, SettingsDomainAdapter(ManagementDomain.QUICK_MESSAGE))
            assertEquals(ProposalStatus.APPLIED, again.status)
            assertEquals(1, f.settings.settingsSnapshot().quickMessages.size)
        }
    }

    @Test(timeout = 30_000) fun proposalPreviewAndAwaitingApprovalHaveNoBusinessEffect() = runBlocking<Unit> {
        fixture { f ->
            val (adapter, plan) = f.plan(create())
            assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())
            assertEquals(1, plan.hostAssignedIds.size)
            assertTrue(plan.steps.single() is SettingsTransactionOp)
            assertEquals(plan.steps.single(), adapter.decodeStep(adapter.encodeStep(plan.steps.single())!!))
            assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())
        }
    }

    @Test(timeout = 30_000) fun ordinaryEditAfterPreviewConflictsWithoutOverwritingNewValues() = runBlocking<Unit> {
        fixture { f ->
            val command = create(); val (adapter, plan) = f.plan(command)
            val p = f.service.previewPlan(command.domain, "{}", plan, Provenance("session", "run", "call"))
            f.settings.update { it.copy(quickMessages = listOf(QuickMessage(title = "newer user data"))) }
            val (_, review) = f.service.nativeReview(p.proposalId)
            f.service.claimApprovedForApply(f.service.approveNative(review))
            val result = f.coordinator.applyClaimed(p.proposalId, adapter)
            assertEquals(ProposalStatus.CONFLICT, result.status)
            assertEquals("newer user data", f.settings.settingsSnapshot().quickMessages.single().title)
            assertTrue(f.fences.fencedRefs().isEmpty())
        }
    }

    @Test(timeout = 30_000) fun actualCompensationUsesEncryptedBeforeImageAndCreatesNewRevision() = runBlocking<Unit> {
        fixture { f ->
            val applied = f.apply(create())
            val step = applied.steps.single()
            val receipt = checkNotNull(step.receipt)
            val op = StoreOperationCodec.decode(step.opJson)
            val before = f.port.collectionToken("settings.quickMessages")
            val result = f.gateway.compensate(op, "${receipt.operationId}/compensate", receipt.afterTokens)
            assertEquals(CompensationStatus.APPLIED, result.status)
            assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())
            assertTrue(f.port.collectionToken("settings.quickMessages").revision > before.revision)
            val meta = f.metadata.meta(f.gateway.beforeImageRef(receipt.operationId))!!
            assertTrue(meta.encrypted); assertFalse(meta.incomplete)
        }
    }

    @Test(timeout = 30_000) fun compensationDoesNotOverwriteLaterOrdinaryEdit() = runBlocking<Unit> {
        fixture { f ->
            val applied = f.apply(create()); val step = applied.steps.single(); val receipt = step.receipt!!
            f.settings.update { current -> current.copy(quickMessages = current.quickMessages.map { it.copy(content = "newer") }) }
            val result = f.gateway.compensate(StoreOperationCodec.decode(step.opJson),
                "${receipt.operationId}/compensate", receipt.afterTokens)
            assertEquals(CompensationStatus.SKIPPED_TOKEN_MISMATCH, result.status)
            assertEquals("newer", f.settings.settingsSnapshot().quickMessages.single().content)
        }
    }

    @Test(timeout = 30_000) fun quickMessageDeleteAtomicallyClearsOrdinaryAssistantBindings() = runBlocking<Unit> {
        fixture { f ->
            f.apply(create()); val id = f.settings.settingsSnapshot().quickMessages.single().id
            val assistant = f.settings.settingsSnapshot().assistants.first { !me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.isCreator(it.id) }.id
            f.apply(SettingsDomainCommand("settings.quickMessages", SettingsAction.BindAssistant(assistant.toString(), id.toString(), true)))
            assertTrue(id in f.settings.settingsSnapshot().assistants.first { !me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.isCreator(it.id) }.quickMessageIds)
            val deleted = f.apply(SettingsDomainCommand("settings.quickMessages", SettingsAction.Delete(id.toString())))
            assertEquals(ProposalStatus.APPLIED, deleted.status)
            val after = f.settings.settingsSnapshot()
            assertTrue(after.quickMessages.isEmpty())
            assertTrue(after.assistants.all { id !in it.quickMessageIds })
        }
    }

    @Test(timeout = 30_000) fun creatorAssistantCannotBeTargetedButBindingCleanupCoversIt() = runBlocking<Unit> {
        fixture { f ->
            val creator = me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ID
            assertThrows(me.rerere.rikkihub.data.creator.definition.ReservedAssistantIdException::class.java) {
                runBlocking { f.plan(SettingsDomainCommand("settings.assistants",
                    SettingsAction.Patch(creator.toString(), mapOf("name" to JsonPrimitive("hijacked"))))) }
            }
            f.apply(create()); val id = f.settings.settingsSnapshot().quickMessages.single().id
            // The user binds the quick message to the creator through the ordinary settings UI.
            f.settings.update { current -> current.copy(assistants = current.assistants.map {
                if (it.id == creator) it.copy(quickMessageIds = setOf(id)) else it
            }) }
            val deleted = f.apply(SettingsDomainCommand("settings.quickMessages", SettingsAction.Delete(id.toString())))
            assertEquals(ProposalStatus.APPLIED, deleted.status)
            assertTrue(f.settings.settingsSnapshot().assistants.single { it.id == creator }.quickMessageIds.isEmpty())
        }
    }

    @Test(timeout = 30_000) fun assistantReorderKeepsHiddenCreatorInPlace() = runBlocking<Unit> {
        fixture { f ->
            val creator = me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ID.toString()
            val before = f.settings.settingsSnapshot().assistants.map { it.id.toString() }
            assertEquals(creator, before.first())
            val ordinary = before.filterNot { it == creator }
            assertTrue(ordinary.size >= 2)
            val applied = f.apply(SettingsDomainCommand("settings.assistants", SettingsAction.Reorder(ordinary.reversed())))
            assertEquals(ProposalStatus.APPLIED, applied.status)
            assertEquals(listOf(creator) + ordinary.reversed(), f.settings.settingsSnapshot().assistants.map { it.id.toString() })
        }
    }

    @Test(timeout = 30_000) fun assistantSensitiveFieldsAreRetainedAndCannotBePatched() = runBlocking<Unit> {
        fixture { f ->
            val assistantId = f.settings.settingsSnapshot().assistants.first { !me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.isCreator(it.id) }.id
            f.settings.update { current -> current.copy(assistants = current.assistants.map {
                if (it.id == assistantId) it.copy(customHeaders = listOf(CustomHeader("Authorization", "synthetic-canary"))) else it
            }) }
            val command = SettingsDomainCommand("settings.assistants", SettingsAction.Patch(assistantId.toString(), mapOf("name" to JsonPrimitive("renamed"))))
            assertEquals(ProposalStatus.APPLIED, f.apply(command).status)
            assertEquals("synthetic-canary", f.settings.settingsSnapshot().assistants.first { !me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.isCreator(it.id) }.customHeaders.single().value)
            val forbidden = assertThrows(me.rerere.rikkihub.data.management.proposal.ProposalServiceError.DomainError::class.java) {
                runBlocking { f.plan(SettingsDomainCommand("settings.assistants", SettingsAction.Patch(assistantId.toString(),
                    mapOf("customHeaders" to JsonArray(emptyList()))))) }
            }
            assertEquals(me.rerere.rikkihub.data.management.proposal.ProposalErrorCode.FORBIDDEN_FIELD, forbidden.errorCode)
            assertTrue(forbidden.message.orEmpty().contains("customHeaders"))
            val projection = f.port.readObjectJson("settings.assistants", assistantId.toString())!!
            assertFalse(projection.toString().contains("synthetic-canary"))
        }
    }

    @Test(timeout = 30_000) fun defaultPromptResetUsesCodeDefaultAndRealVersionReceipt() = runBlocking<Unit> {
        fixture { f ->
            val custom = SettingsDomainCommand("settings.defaults.prompts", SettingsAction.SetField("settings.defaults.prompt.TITLE", JsonPrimitive("custom")))
            assertEquals(ProposalStatus.APPLIED, f.apply(custom).status)
            assertEquals("custom", f.settings.settingsSnapshot().titlePrompt)
            f.apply(SettingsDomainCommand("settings.defaults.prompts", SettingsAction.ResetPrompt("TITLE")))
            assertEquals(me.rerere.rikkihub.data.ai.prompts.DEFAULT_TITLE_PROMPT, f.settings.settingsSnapshot().titlePrompt)
        }
    }

    @Test(timeout = 30_000) fun recoveryImageTamperingCannotRestoreAnything() = runBlocking<Unit> {
        fixture { f ->
            val applied = f.apply(create()); val step = applied.steps.single(); val receipt = step.receipt!!
            val key = f.gateway.beforeImageRef(receipt.operationId)
            val body = File(f.root, "private-management/recycle/$key.bin")
            val bytes = java.util.Base64.getDecoder().decode(body.readText())
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            body.writeText(java.util.Base64.getEncoder().encodeToString(bytes))
            assertThrows(Exception::class.java) { runBlocking { f.gateway.compensate(StoreOperationCodec.decode(step.opJson),
                "${receipt.operationId}/compensate", receipt.afterTokens) } }
            assertEquals(1, f.settings.settingsSnapshot().quickMessages.size)
        }
    }
}
