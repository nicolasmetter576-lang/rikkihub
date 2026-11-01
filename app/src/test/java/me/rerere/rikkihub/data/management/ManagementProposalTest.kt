package me.rerere.rikkihub.data.management

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkihub.data.creator.session.CreatorCanonicalizer
import me.rerere.rikkihub.data.management.common.*
import me.rerere.rikkihub.data.management.coordinator.*
import me.rerere.rikkihub.data.management.persistence.FileBackedManagementStore
import me.rerere.rikkihub.data.management.ports.*
import me.rerere.rikkihub.data.management.proposal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManagementProposalTest {
    private class Fixture {
        val root = Files.createTempDirectory("proposal-local-").toFile()
        var epoch = 7L
        var version = 1
        var now = 1_000L
        val store = FileBackedManagementStore(root, nowMillis = { now })
        val fences = ObjectFenceRegistry(store) { now }
        val authority = NativeApprovalAuthority({ epoch }, { version }, { now })
        val service = CreatorProposalService(store, store, store, fences, authority,
            object : ManagementWriteGate { override fun status() = WriteGateStatus.Enabled },
            { version }, { now })
        suspend fun proposal(): Proposal {
            val ref = ObjectRef(ManagementDomain.QUICK_MESSAGE, "11111111-1111-1111-1111-111111111111")
            val plan = PlannedMutation(ReadSet(listOf(ReadSetEntry.Collection("settings.quickMessages", VersionToken(epoch, 0, 0)))),
                listOf(SettingsCollectionOp("settings.quickMessages", CollectionPatch.Create(ref.id,
                    mapOf("title" to JsonPrimitive("Test"), "content" to JsonPrimitive("ordinary text"))),
                    listOf(ref), "quick-message-create")),
                listOf(DiffLine("quickMessages", null, "ordinary text")), RiskLevel.HIGH,
                RetentionNote(), listOf(ref), true, mapOf("new0" to ref.id))
            return service.previewPlan(ManagementDomain.QUICK_MESSAGE, "{\"command\":\"Create\"}", plan,
                Provenance("session", "run", "new-successful-response-call"))
        }
        fun close() { root.deleteRecursively() }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(); try { block(f) } finally { f.close() }
    }

    @Test(timeout = 15_000) fun previewIsOnlyDataAndOneNativeApprovalSuffices() = runBlocking<Unit> {
        fixture { f ->
            val p = f.proposal()
            assertEquals(ProposalStatus.PENDING_APPROVAL, p.status)
            assertTrue(f.fences.fencedRefs().isEmpty())
            val (_, review) = f.service.nativeReview(p.proposalId)
            f.service.approveNative(review)
            assertEquals(ProposalStatus.APPROVED, f.service.get(p.proposalId).status)
            // The review handle is single-use.
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.service.approveNative(review) } }
        }
    }

    @Test(timeout = 15_000) fun exactNativeClaimIsOneUseAndDoubleClickCannotClaimTwice() = runBlocking<Unit> {
        fixture { f ->
            val p = f.proposal(); val (_, review) = f.service.nativeReview(p.proposalId)
            val approval = f.service.approveNative(review)
            val results = (1..2).map { async(Dispatchers.Default) {
                runCatching { f.service.claimApprovedForApply(approval) }
            } }.awaitAll()
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(ProposalStatus.APPLYING, f.service.get(p.proposalId).status)
            assertEquals(1, f.store.allJournalEntries().count { it.proposalId == p.proposalId &&
                it.phase == me.rerere.rikkihub.data.management.persistence.JournalPhase.PROPOSAL_CLAIMED })
            assertFalse(f.fences.fencedRefs().isEmpty())
        }
    }

    @Test(timeout = 15_000) fun oldReviewCannotApproveDifferentOrChangedDiff() = runBlocking<Unit> {
        fixture { f ->
            val p = f.proposal(); val (_, review) = f.service.nativeReview(p.proposalId)
            f.store.updateProposal(p.proposalId) { it!!.copy(diffSummary = listOf(DiffLine("changed", null, "other"))) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.service.approveNative(review) } }
            assertEquals(ProposalStatus.PENDING_APPROVAL, f.service.get(p.proposalId).status)
        }
    }

    @Test(timeout = 15_000) fun epochRevocationRejectsApprovalBeforeAnyFenceOrBusinessWrite() = runBlocking<Unit> {
        fixture { f ->
            val p = f.proposal(); val (_, review) = f.service.nativeReview(p.proposalId)
            val approval = f.service.approveNative(review)
            f.epoch++
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.service.claimApprovedForApply(approval) } }
            assertTrue(f.fences.fencedRefs().isEmpty())
        }
    }

    @Test(timeout = 15_000) fun nativeHandleLivesAsLongAsTheProposal() = runBlocking<Unit> {
        fixture { f ->
            // No short review/approval window: a handle survives a long pause...
            val p = f.proposal(); val (_, review) = f.service.nativeReview(p.proposalId)
            f.now += 60L * 60 * 1000
            f.service.claimApprovedForApply(f.service.approveNative(review))
            assertEquals(ProposalStatus.APPLYING, f.service.get(p.proposalId).status)
        }
        fixture { f ->
            // ...but not the proposal's own lifetime.
            val late = f.proposal(); val (_, lateReview) = f.service.nativeReview(late.proposalId)
            val approval = f.service.approveNative(lateReview)
            f.now = late.expiresAtMillis + 1
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.service.claimApprovedForApply(approval) } }
        }
    }

    @Test(timeout = 15_000) fun processRestartInvalidatesPersistedApprovedAndInMemoryCapability() = runBlocking<Unit> {
        fixture { f ->
            val p = f.proposal(); val (_, review) = f.service.nativeReview(p.proposalId)
            val approval = f.service.approveNative(review)
            f.service.invalidateApprovalsAfterRestart()
            assertEquals(ProposalStatus.EXPIRED, f.service.get(p.proposalId).status)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.service.claimApprovedForApply(approval) } }
        }
    }

    @Test(timeout = 15_000) fun interruptedApplyRetainsDurableFenceAndRequiresFreshRecovery() = runBlocking<Unit> {
        fixture { f ->
            val p = f.proposal(); val (_, review) = f.service.nativeReview(p.proposalId)
            val approval = f.service.approveNative(review)
            f.service.claimApprovedForApply(approval)
            f.service.invalidateApprovalsAfterRestart()
            assertEquals(ProposalStatus.NEEDS_RECOVERY, f.service.get(p.proposalId).status)
            val restored = ObjectFenceRegistry(FileBackedManagementStore(f.root))
            restored.restore()
            assertFalse(restored.fencedRefs().isEmpty())
        }
    }

    @Test(timeout = 15_000) fun sessionCannotQueryOrCancelAnotherSessionsProposal() = runBlocking<Unit> {
        fixture { f ->
            val p = f.proposal()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.service.status(p.proposalId, "other") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.service.cancel(p.proposalId, "other") } }
            assertEquals(ProposalStatus.PENDING_APPROVAL, f.service.get(p.proposalId).status)
        }
    }

    @Test(timeout = 15_000) fun completeCorruptFenceMetadataIsNotTreatedAsNoFence() = runBlocking<Unit> {
        fixture { f ->
            val p = f.proposal(); val (_, review) = f.service.nativeReview(p.proposalId)
            f.service.claimApprovedForApply(f.service.approveNative(review))
            File(f.root, "fences/fences.json").writeText("{corrupt}")
            assertThrows(Exception::class.java) { runBlocking { ObjectFenceRegistry(f.store).restore() } }
        }
    }

    @Test(timeout = 15_000) fun differentProposalCannotStealExistingFence() = runBlocking<Unit> {
        fixture { f ->
            val ref = ObjectRef(ManagementDomain.ASSISTANT, "id")
            f.fences.install("owner", listOf(ref))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.fences.install("other", listOf(ref)) } }
            assertEquals("owner", f.fences.fencedBy(ref))
            assertEquals("owner", f.store.allFences().single().proposalId)
        }
    }

    @Test(timeout = 15_000) fun canonicalKeysAreEscapedAndUnambiguous() {
        val tree = JsonObject(mapOf("x\"\\\n" to JsonPrimitive("value"), "a" to JsonPrimitive(1)))
        val canonical = CreatorCanonicalizer.canonicalize(tree)
        assertEquals(tree, Json.parseToJsonElement(canonical))
        assertEquals(CreatorCanonicalizer.canonicalDigest(tree),
            CreatorCanonicalizer.canonicalDigest(JsonObject(tree.entries.reversed().associate { it.toPair() })))
    }

    @Test(timeout = 15_000) fun durableOperationUnknownKindOrFieldsAreRejected() {
        val op = SettingsFieldOp("settings.defaults.prompt.TITLE", JsonPrimitive("hi"),
            listOf(ObjectRef(ManagementDomain.MODEL_AND_PROMPT_DEFAULTS, "setting:titlePrompt")), "prompt")
        assertEquals(op, StoreOperationCodec.decode(StoreOperationCodec.encode(op)))
        assertThrows(IllegalArgumentException::class.java) { StoreOperationCodec.decode("{\"kind\":\"java.lang.Runtime\",\"body\":{}}") }
        assertThrows(IllegalArgumentException::class.java) { StoreOperationCodec.decode("{\"kind\":\"settings.field.v1\",\"body\":{},\"extra\":1}") }
    }
}
