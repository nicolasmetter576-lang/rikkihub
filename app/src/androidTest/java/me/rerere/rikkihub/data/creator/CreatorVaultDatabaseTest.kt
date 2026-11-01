package me.rerere.rikkihub.data.creator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.creator.vault.CreatorDatabase
import me.rerere.rikkihub.data.creator.vault.CreatorProposalEntity
import me.rerere.rikkihub.data.creator.vault.CreatorSessionEntity
import me.rerere.rikkihub.data.creator.vault.CreatorVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/**
 * CreatorVault Room v1 真库验证（必须验证 SQLite 实际创建/迁移
 * 行为；设备/instrumented 层——emulator/真机可用后由验收代理执行）。
 *
 * 覆盖：建库、实体 CRUD、proposal CAS 迁移、账本状态机、grant 影子只记
 * 事件、epoch 更换脱钩打开 run、审计只追加、回收 payload 往返。
 */
@RunWith(AndroidJUnit4::class)
class CreatorVaultDatabaseTest {

    private lateinit var db: CreatorDatabase
    private lateinit var vault: CreatorVault

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CreatorDatabase::class.java).build()
        vault = CreatorVault(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sessionId() = Uuid.random()

    @Test
    fun createSessionAndNextRunOrdinalAreMonotonic() = runBlocking<Unit> {
        val id = sessionId()
        vault.createSession(id, 1)
        assertNotNull(vault.getSession(id))
        assertEquals(0, vault.nextRunOrdinal(id))
        assertEquals(1, vault.nextRunOrdinal(id))
    }

    @Test
    fun missingSessionReturnsNegativeOneOrdinal() = runBlocking<Unit> {
        assertEquals(-1, vault.nextRunOrdinal(sessionId()))
    }

    @Test
    fun runInsertAndTerminalUpdate() = runBlocking<Unit> {
        val sid = sessionId()
        vault.createSession(sid, 1)
        vault.insertRun(
            me.rerere.rikkihub.data.creator.vault.CreatorRunEntity(
                runId = "r1", sessionId = sid.toString(), ordinal = 0,
                runNonce = "n", grantId = "g", status = "RUNNING",
                frozenModelId = "m", frozenProviderId = "p",
                definitionVersion = 1, startedAtMillis = 1,
                endedAtMillis = null, terminalReason = null,
            )
        )
        assertEquals(1, vault.openRuns().size)
        vault.finishRun("r1", "COMPLETED", null)
        assertTrue(vault.openRuns().isEmpty())
        assertEquals("COMPLETED", db.creatorRunDao().get("r1")?.status)
    }

    @Test
    fun callLedgerLifecycle() = runBlocking<Unit> {
        val sid = sessionId()
        vault.createSession(sid, 1)
        val call = me.rerere.rikkihub.data.creator.vault.CreatorCallLedgerEntity(
            hostCallInstanceId = "h1", bindingId = "b1", runId = "r1",
            sessionId = sid.toString(), grantId = "g", stableToolId = "creator_list_objects",
            wireName = "creator_list_objects", runNonce = "n", stepNonce = "s",
            stepIndex = 0, messageId = null, partIndex = 0, inputDigest = "d",
            state = "ISSUED", issuedAtMillis = 0, expiresAtMillis = Long.MAX_VALUE,
            terminalAtMillis = null, outputSummary = null, errorCode = null,
        )
        vault.insertCall(call)
        vault.updateCallState("h1", "EXECUTING")
        vault.updateCallState("h1", "OK", outputSummary = "done")
        val stored = db.creatorCallLedgerDao().get("h1")
        assertEquals("OK", stored?.state)
        assertEquals("done", stored?.outputSummary)
    }

    @Test
    fun proposalCASTransitionFailsOnStaleExpectedState() = runBlocking<Unit> {
        val sid = sessionId()
        vault.createSession(sid, 1)
        vault.insertProposal(
            CreatorProposalEntity(
                proposalId = "p1", sessionId = sid.toString(), runId = null,
                sourceCallId = null, operationId = "op-p1", state = "DRAFT",
                canonicalDigest = "d".repeat(64), payloadJson = "{}",
                readSetJson = "[]", riskSummary = null, definitionVersion = 1,
                createdAtMillis = 0, updatedAtMillis = 0, expiresAtMillis = Long.MAX_VALUE,
            )
        )
        // CAS：expectedState 不符 → false（并发领取/旧回调不执行两次）
        assertFalse(vault.transitionProposal("p1", "APPLIED", "APPLYING"))
        assertTrue(vault.transitionProposal("p1", "DRAFT", "PENDING_APPROVAL"))
        assertEquals("PENDING_APPROVAL", vault.getProposal("p1")?.state)
    }

    @Test
    fun auditIsAppendOnly() = runBlocking<Unit> {
        vault.appendAudit("k", "a", "r", "{}")
        vault.appendAudit("k2", "a2", "r2", "{}")
        assertEquals(2, vault.recentAudit(10).size)
    }

    @Test
    fun recyclePayloadRoundtrip() = runBlocking<Unit> {
        vault.putRecyclePayload("x", "before", "ref", byteArrayOf(1, 2, 3))
        assertNotNull(vault.getRecyclePayload("x"))
    }

    @Test
    fun epochChangeDetachesOpenRuns() = runBlocking<Unit> {
        val sid = sessionId()
        vault.createSession(sid, 1)
        vault.insertRun(
            me.rerere.rikkihub.data.creator.vault.CreatorRunEntity(
                runId = "r1", sessionId = sid.toString(), ordinal = 0,
                runNonce = "n", grantId = "g", status = "RUNNING",
                frozenModelId = "m", frozenProviderId = "p",
                definitionVersion = 1, startedAtMillis = 1,
                endedAtMillis = null, terminalReason = null,
            )
        )
        val epoch = vault.onStorageEpochChanged("restore")
        assertEquals(1L, epoch)
        assertTrue(vault.openRuns().isEmpty())
        assertEquals("DETACHED", db.creatorRunDao().get("r1")?.status)
    }

    @Test
    fun grantShadowRecordsEvents() = runBlocking<Unit> {
        vault.recordGrantShadow("g1", "s", "r", 0, "ISSUED")
        val history = db.creatorGrantShadowDao().historyOf("g1")
        assertEquals(1, history.size)
        assertEquals("ISSUED", history[0].kind)
    }
}
