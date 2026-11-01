package me.rerere.rikkihub.data.management

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.creator.runtime.CreatorCommandDecoder
import me.rerere.rikkihub.data.creator.runtime.CreatorCommandRejected
import me.rerere.rikkihub.data.creator.session.CreatorOperation
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.management.adapters.SettingsAction
import me.rerere.rikkihub.data.management.adapters.SettingsDomainCommand
import me.rerere.rikkihub.data.management.adapters.conversation.ConversationManagementCommand
import me.rerere.rikkihub.data.management.proposal.ProposalServiceError
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.management.proposal.Provenance
import me.rerere.rikkihub.data.management.recovery.AesGcmPayloadCipher
import me.rerere.rikkihub.data.management.ports.RoomManagementBackend
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
import me.rerere.rikkihub.data.mutation.MutationFencedException
import me.rerere.rikkihub.data.mutation.MutationRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.uuid.Uuid

/**
 * End-to-end management chain on the JVM: real Preferences DataStore, real atomic
 * management files with AES-GCM recovery images, real user-skill directory, and an
 * in-memory Room backend that enforces the same CAS rules as ChatService.
 */
class ManagementRuntimeIntegrationTest {

    private fun fixture(block: suspend (ManagementFixture) -> Unit) = managementFixture(block)

    private fun quickMessage(title: String) = SettingsDomainCommand("settings.quickMessages",
        SettingsAction.Create(mapOf("title" to JsonPrimitive(title), "content" to JsonPrimitive("body"))))

    @Test(timeout = 60_000) fun previewWritesNothingAndNativeApprovalAppliesExactlyOnce() = fixture { f ->
        val proposal = f.runtime.preview(quickMessage("greeting"), f.source)
        assertEquals(ProposalStatus.PENDING_APPROVAL, proposal.status)
        assertTrue("preview must not write business state", f.settings.settingsSnapshot().quickMessages.isEmpty())
        val (_, review) = f.runtime.review(proposal.proposalId)
        val applied = f.runtime.approveAndApply(review)
        assertEquals(ProposalStatus.APPLIED, applied.status)
        assertEquals(listOf("greeting"), f.settings.settingsSnapshot().quickMessages.map { it.title })
        // A consumed review handle cannot approve again.
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.runtime.approveAndApply(review) } }
        assertEquals(1, f.settings.settingsSnapshot().quickMessages.size)
        assertTrue(f.runtime.fences.fencedRefs().isEmpty())
    }

    @Test(timeout = 60_000) fun approveAllAppliesSessionPendingAndReportsEachOutcome() = fixture { f ->
        f.runtime.preview(quickMessage("first"), f.source)
        f.runtime.preview(quickMessage("second"), f.source)
        f.runtime.preview(quickMessage("other session"), Provenance("session-2", "run-2", "call-2"))
        val results = f.runtime.approveAllAndApply("session-1")
        assertEquals(2, results.size)
        // The second proposal's collection base moved when the first applied: it is re-planned and applied.
        assertEquals(listOf(ProposalStatus.APPLIED, ProposalStatus.APPLIED), results.map { it.getOrThrow().status })
        assertEquals(setOf("first", "second"), f.settings.settingsSnapshot().quickMessages.map { it.title }.toSet())
        assertEquals(1, f.runtime.list().count { it.status == ProposalStatus.PENDING_APPROVAL })
        assertTrue(f.runtime.approveAllAndApply("session-1").isEmpty())
    }

    @Test(timeout = 60_000) fun approveAllKeepsObjectLevelConflictsForManualReview() = fixture { f ->
        val created = f.approve(f.runtime.preview(quickMessage("base"), f.source).proposalId)
        assertEquals(ProposalStatus.APPLIED, created.status)
        val id = f.settings.settingsSnapshot().quickMessages.single().id.toString()
        f.runtime.preview(SettingsDomainCommand("settings.quickMessages",
            SettingsAction.Patch(id, mapOf("content" to JsonPrimitive("model edit")))), f.source)
        f.settings.update { s -> s.copy(quickMessages = s.quickMessages.map { it.copy(content = "user edit") }) }
        val results = f.runtime.approveAllAndApply("session-1")
        assertEquals(listOf(ProposalStatus.CONFLICT), results.map { it.getOrThrow().status })
        assertEquals("user edit", f.settings.settingsSnapshot().quickMessages.single().content)
    }

    @Test(timeout = 60_000) fun closedGateRejectsClaimWithoutBusinessEffect() = fixture { f ->
        f.gateOpen = false
        val proposal = f.runtime.preview(quickMessage("blocked"), f.source)
        val (_, review) = f.runtime.review(proposal.proposalId)
        assertThrows(ProposalServiceError.WriteGateClosed::class.java) { runBlocking { f.runtime.approveAndApply(review) } }
        assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())
    }

    @Test(timeout = 60_000) fun ordinaryEditAfterPreviewTurnsApplyIntoConflict() = fixture { f ->
        val proposal = f.runtime.preview(quickMessage("planned"), f.source)
        f.settings.update { it.copy(quickMessages = it.quickMessages + me.rerere.rikkihub.data.model.QuickMessage(title = "user")) }
        val (_, review) = f.runtime.review(proposal.proposalId)
        val result = f.runtime.approveAndApply(review)
        assertEquals(ProposalStatus.CONFLICT, result.status)
        assertEquals(listOf("user"), f.settings.settingsSnapshot().quickMessages.map { it.title })
    }

    @Test(timeout = 60_000) fun undoRestoresBeforeImageAndRefusesAfterLaterEdit() = fixture { f ->
        val applied = f.approve(f.runtime.preview(quickMessage("temp"), f.source).proposalId)
        assertEquals(ProposalStatus.APPLIED, applied.status)
        val undo = checkNotNull(f.runtime.previewUndo(applied, f.source))
        assertTrue(undo.diffSummary.isNotEmpty())
        assertEquals(ProposalStatus.APPLIED, f.approve(undo.proposalId).status)
        assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())

        val second = f.approve(f.runtime.preview(quickMessage("kept"), f.source).proposalId)
        f.settings.update { s -> s.copy(quickMessages = s.quickMessages.map { it.copy(content = "user edit") }) }
        assertThrows(ProposalServiceError.DomainError::class.java) { runBlocking { f.runtime.previewUndo(second, f.source) } }
        assertEquals("user edit", f.settings.settingsSnapshot().quickMessages.single().content)
    }

    @Test(timeout = 60_000) fun managementFenceBlocksOrdinaryWriteOfTheSameObject() = fixture { f ->
        f.runtime.ensureStarted()
        f.settings.update { it.copy(quickMessages = listOf(me.rerere.rikkihub.data.model.QuickMessage(title = "a"))) }
        val id = f.settings.settingsSnapshot().quickMessages.single().id
        val ref = me.rerere.rikkihub.data.management.common.ObjectRef(
            me.rerere.rikkihub.data.management.common.ManagementDomain.QUICK_MESSAGE, id.toString())
        f.runtime.fences.install("pending-proposal", listOf(ref))
        assertThrows(MutationFencedException::class.java) {
            runBlocking { f.settings.update { s -> s.copy(quickMessages = s.quickMessages.map { it.copy(title = "b") }) } }
        }
        // Unrelated Settings edits stay available.
        f.settings.update { it.copy(titlePrompt = "unrelated") }
        f.runtime.fences.release("pending-proposal")
        f.settings.update { s -> s.copy(quickMessages = s.quickMessages.map { it.copy(title = "b") }) }
        assertEquals("b", f.settings.settingsSnapshot().quickMessages.single().title)
    }

    @Test(timeout = 60_000) fun skillCreatePatchAndRenameGoThroughUserTreeOnly() = fixture { f ->
        val create = CreatorCommandDecoder.decode(CreatorOperation("SKILL", command = "CreateSkill", params = JsonObject(mapOf(
            "dirName" to JsonPrimitive("notes"),
            "skillMd" to JsonPrimitive("---\nname: notes\ndescription: Take notes\n---\nBody\n"),
        ))))
        assertEquals(ProposalStatus.APPLIED, f.approve(f.runtime.preview(create, f.source).proposalId).status)
        val skillMd = File(f.filesRoot, "skills/notes/SKILL.md")
        assertTrue(skillMd.readText().contains("Take notes"))
        val patch = CreatorCommandDecoder.decode(CreatorOperation("SKILL", command = "CreateFile", params = JsonObject(mapOf(
            "skillName" to JsonPrimitive("notes"), "path" to JsonPrimitive("ref/guide.md"), "content" to JsonPrimitive("guide"),
        ))))
        assertEquals(ProposalStatus.APPLIED, f.approve(f.runtime.preview(patch, f.source).proposalId).status)
        assertEquals("guide", File(f.filesRoot, "skills/notes/ref/guide.md").readText())
        // Path escapes are rejected at plan time and never touch the filesystem.
        val escape = CreatorCommandDecoder.decode(CreatorOperation("SKILL", command = "CreateFile", params = JsonObject(mapOf(
            "skillName" to JsonPrimitive("notes"), "path" to JsonPrimitive("../escape.md"), "content" to JsonPrimitive("x"),
        ))))
        assertThrows(Exception::class.java) { runBlocking { f.runtime.preview(escape, f.source) } }
        assertFalse(File(f.filesRoot, "skills/escape.md").exists())
    }

    @Test(timeout = 60_000) fun conversationCommandUsesPlannedVersionAndBusyIsRejected() = fixture { f ->
        val assistant = f.settings.settingsSnapshot().assistants.first { !me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.isCreator(it.id) }.id
        val id = Uuid.random()
        f.room.conversations[id] = Conversation(id = id, assistantId = assistant, title = "old", messageNodes = emptyList(), revision = 3)
        val rename = ConversationManagementCommand(id.toString(), ConversationMutationCommand.PatchMetadata(title = "new"))
        f.room.busy = "GENERATING"
        assertThrows(ProposalServiceError.DomainError::class.java) { runBlocking { f.runtime.preview(rename, f.source) } }
        f.room.busy = null
        val proposal = f.runtime.preview(rename, f.source)
        assertEquals(ProposalStatus.APPLIED, f.approve(proposal.proposalId).status)
        assertEquals("new", f.room.conversations.getValue(id).title)
        assertEquals(4, f.room.conversations.getValue(id).revision)
    }

    @Test(timeout = 60_000) fun restartRevokesApprovalsAndReconcilesFromReceipts() = fixture { f ->
        val proposal = f.runtime.preview(quickMessage("before-restart"), f.source)
        val (_, review) = f.runtime.review(proposal.proposalId)
        // Process death: a new runtime instance over the same files.
        f.runtime = f.newRuntime()
        f.runtime.ensureStarted()
        assertThrows(Exception::class.java) { runBlocking { f.runtime.approveAndApply(review) } }
        assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())
        // A fresh native review of the still-pending proposal works.
        assertEquals(ProposalStatus.APPLIED, f.approve(proposal.proposalId).status)
    }

    @Test fun decoderRejectsModelSuppliedSnapshotsAndHostIds() {
        assertThrows(CreatorCommandRejected::class.java) {
            CreatorCommandDecoder.decode(CreatorOperation("SUBAGENT_PRESET", command = "RestoreLibraryPreset",
                params = JsonObject(mapOf("presetId" to JsonPrimitive("x"), "snapshotJson" to JsonPrimitive("{}")))))
        }
        assertThrows(CreatorCommandRejected::class.java) {
            CreatorCommandDecoder.decode(CreatorOperation("QUICK_MESSAGE", command = "Create",
                params = JsonObject(mapOf("fields" to JsonObject(mapOf("id" to JsonPrimitive("forged")))))))
        }
        assertThrows(CreatorCommandRejected::class.java) {
            CreatorCommandDecoder.decode(CreatorOperation("CONVERSATION", targetId = "x", command = "CreateOrdinaryConversation",
                params = JsonObject(mapOf("assistantId" to JsonPrimitive("a")))))
        }
        assertNotNull(CreatorCommandDecoder.decode(CreatorOperation("PROMPT", command = "ResetPrompt",
            params = JsonObject(mapOf("slot" to JsonPrimitive("TITLE"))))))
        assertNull(null)
    }
}
