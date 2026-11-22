package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ContextCheckpointKind
import me.rerere.ai.ui.ContextCheckpointMetadata
import me.rerere.rikkihub.service.ContextCheckpointRecord
import me.rerere.rikkihub.service.ContextRecordMessagePreview
import me.rerere.rikkihub.service.ContextUsageBreakdown
import me.rerere.rikkihub.data.ai.AutoCompressMode
import me.rerere.rikkihub.data.ai.CarryOriginalsMode
import me.rerere.rikkihub.data.ai.CompressionConfig
import me.rerere.rikkihub.data.ai.CompressionTemplate
import me.rerere.rikkihub.data.ai.CompressionTemplates
import me.rerere.rikkihub.data.ai.ConversationCompressionOverride
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.service.CompressErrorCodes
import me.rerere.rikkihub.service.CompressSuggestion
import me.rerere.rikkihub.service.CompressOrigin
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.service.CompressStatus
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class CompressRoutesTest {
    @Test
    fun `only the additional prompt is read and old fields are ignored`() {
        assertEquals("", CompressStartRequest().toCompressRequest().additionalPrompt)
        // 旧版网页会多带 targetTokens / keepRecentMessages：按未知键忽略
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            CompressStartRequest.serializer(),
            """{"additionalPrompt":"keep file paths","targetTokens":2000,"keepRecentMessages":32}""",
        )
        assertEquals("keep file paths", decoded.toCompressRequest().additionalPrompt)
    }

    @Test
    fun `kept recent messages are read, default to none, and out of range is a 400`() {
        assertEquals(0, CompressStartRequest().toCompressRequest().keepRecent)
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            CompressStartRequest.serializer(),
            """{"additionalPrompt":"","keepRecent":6}""",
        )
        assertEquals(6, decoded.toCompressRequest().keepRecent)
        assertThrows(BadRequestException::class.java) { CompressStartRequest(keepRecent = -1).toCompressRequest() }
        assertThrows(BadRequestException::class.java) {
            CompressStartRequest(keepRecent = Int.MAX_VALUE).toCompressRequest()
        }
    }

    @Test
    fun `too long additional prompt is a 400, not clamped`() {
        assertThrows(BadRequestException::class.java) {
            CompressStartRequest(additionalPrompt = "x".repeat(2001)).toCompressRequest()
        }
    }

    @Test
    fun `state wire form uses lowercase status and origin`() {
        val id = Uuid.random()
        val dto = CompressState(
            CompressStatus.FAILED, CompressOrigin.WEB, startedAt = 1, finishedAt = 2,
            errorCode = CompressErrorCodes.CONVERSATION_CHANGED, errorMessage = "changed",
        ).toDto(id)
        assertEquals(id.toString(), dto.conversationId)
        assertEquals("failed", dto.status)
        assertEquals("web", dto.origin)
        assertEquals(CompressErrorCodes.CONVERSATION_CHANGED, dto.errorCode)
    }

    @Test
    fun `template and review are read and awaiting review carries the summary on the wire`() {
        val request = CompressStartRequest(templateId = "code", review = true).toCompressRequest()
        assertEquals("code", request.templateId)
        assertTrue(request.review)
        assertFalse(CompressStartRequest().toCompressRequest().review)
        val dto = CompressState(
            CompressStatus.AWAITING_REVIEW, CompressOrigin.AUTO, startedAt = 1, reviewSummary = "S",
        ).toDto(Uuid.random())
        assertEquals("awaiting_review", dto.status)
        assertEquals("auto", dto.origin)
        assertEquals("S", dto.reviewSummary)
    }

    private fun patch(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun rejects(config: CompressionConfig, json: String): Boolean =
        runCatching { mergeCompressionConfig(config, patch(json), Settings()) }.exceptionOrNull() is BadRequestException

    @Test
    fun `config patch changes only the given keys and rejects bad input`() {
        val base = CompressionConfig()
        val merged = mergeCompressionConfig(base, patch("""{"mode":"auto","triggerPercent":90,"keepRecent":3}"""), Settings())
        assertEquals(AutoCompressMode.AUTO, merged.mode)
        assertEquals(90, merged.triggerPercent)
        assertEquals(3, merged.keepRecent)
        assertEquals(base.targetPercent, merged.targetPercent)
        val bad = mapOf(
            "unknown key" to """{"bogus":1}""",
            "unknown mode" to """{"mode":"sometimes"}""",
            "trigger out of range" to """{"triggerPercent":99}""",
            "target too close to trigger" to """{"targetPercent":78}""",
            "string number" to """{"keepRecent":"3"}""",
            "unknown template" to """{"templateId":"nope"}""",
            "unknown model" to """{"modelId":"${Uuid.random()}"}""",
            "custom template reusing a built-in id" to """{"customTemplates":[{"id":"code","name":"C","prompt":"p"}]}""",
            "custom template without prompt" to """{"customTemplates":[{"id":"a","name":"A","prompt":"  "}]}""",
            "duplicate custom ids" to """{"customTemplates":[{"id":"a","name":"A","prompt":"p"},{"id":"a","name":"B","prompt":"q"}]}""",
        )
        val accepted = bad.filterValues { !rejects(base, it) }.keys
        assertTrue("accepted: $accepted", accepted.isEmpty())
    }

    @Test
    fun `custom templates can be added and chosen, and deleting the default falls back to general`() {
        val withCustom = mergeCompressionConfig(
            CompressionConfig(),
            patch("""{"customTemplates":[{"id":"mine","name":" Mine ","prompt":"## A"}],"templateId":"mine"}"""),
            Settings(),
        )
        assertEquals(listOf(CompressionTemplate("mine", "Mine", "## A")), withCustom.customTemplates)
        assertEquals("mine", withCustom.templateId)
        val removed = mergeCompressionConfig(withCustom, patch("""{"customTemplates":[]}"""), Settings())
        assertEquals(CompressionTemplates.GENERAL, removed.templateId)
        val projected = projectCompressionConfig(Settings(compression = withCustom))
        assertEquals("mine", projected.templateId)
        assertEquals(CompressionTemplates.BUILT_IN, projected.builtInTemplates.map { it.id })
    }

    @Test
    fun `conversation override maps both ways and rejects unknown templates`() {
        val config = CompressionConfig()
        assertEquals(
            ConversationCompressionOverride(mode = AutoCompressMode.OFF, templateId = "roleplay"),
            ConversationCompressionDto(mode = "off", templateId = "roleplay").toOverride(config),
        )
        assertTrue(ConversationCompressionDto().toOverride(config).isEmpty)
        assertThrows(BadRequestException::class.java) { ConversationCompressionDto(templateId = "gone").toOverride(config) }
        assertThrows(BadRequestException::class.java) { ConversationCompressionDto(mode = "loud").toOverride(config) }
        assertEquals(ConversationCompressionDto(), (null as ConversationCompressionOverride?).toDto())
    }

    @Test
    fun `suggestion event clears with null and keeps percent otherwise`() {
        val id = Uuid.random()
        assertNull((null as CompressSuggestion?).toEvent(id).percent)
        val event = CompressSuggestion(percent = 83, exact = true).toEvent(id)
        assertEquals(83, event.percent)
        assertTrue(event.exact)
    }

    @Test
    fun `batch 2 config keys patch cleanly and reject out-of-range inputs`() {
        val base = CompressionConfig()
        val updated = mergeCompressionConfig(
            base,
            patch(
                """{"carryOriginalsMode":"auto","carryOriginalsCount":5,"carryIncludeReplies":true,"maxChunkRequests":12,"chunkTimeoutSeconds":120}"""
            ),
            Settings(),
        )
        assertEquals(CarryOriginalsMode.AUTO, updated.carryOriginalsMode)
        assertEquals(5, updated.carryOriginalsCount)
        assertTrue(updated.carryIncludeReplies)
        assertEquals(12, updated.maxChunkRequests)
        assertEquals(120, updated.chunkTimeoutSeconds)

        val clearedTimeout = mergeCompressionConfig(updated, patch("""{"chunkTimeoutSeconds":null}"""), Settings())
        assertNull(clearedTimeout.chunkTimeoutSeconds)

        val bad = mapOf(
            "bad carryMode" to """{"carryOriginalsMode":"all"}""",
            "carryCount low" to """{"carryOriginalsCount":0}""",
            "carryCount high" to """{"carryOriginalsCount":51}""",
            "carryIncludeReplies string" to """{"carryIncludeReplies":"yes"}""",
            "maxChunkRequests low" to """{"maxChunkRequests":1}""",
            "maxChunkRequests high" to """{"maxChunkRequests":33}""",
            "timeout low" to """{"chunkTimeoutSeconds":10}""",
            "timeout high" to """{"chunkTimeoutSeconds":601}""",
        )
        val accepted = bad.filterValues { !rejects(base, it) }.keys
        assertTrue("accepted: $accepted", accepted.isEmpty())
    }

    @Test
    fun `batch 2 start and estimate requests parse carry and fromOriginal fields and state dtos carry stats`() {
        val nodeId = Uuid.random()
        val start = CompressStartRequest(
            fromOriginal = true,
            carryMode = "recent",
            carryCount = 4,
            carryIncludeReplies = true,
            carryExcludedNodeIds = listOf(nodeId.toString()),
            carryEdits = mapOf(nodeId.toString() to CarriedOriginalEditDto(userText = "edited", replyText = "r")),
        ).toCompressRequest()
        assertTrue(start.fromOriginal)
        assertEquals(CarryOriginalsMode.RECENT, start.carryMode)
        assertEquals(4, start.carryCount)
        assertTrue(start.carryIncludeReplies == true)
        assertEquals(setOf(nodeId), start.carryExcludedNodeIds)
        assertEquals("edited", start.carryEdits[nodeId]?.userText)

        val est = CompressEstimateRequest(keepRecent = 1, fromOriginal = true, carryMode = "auto").toCompressRequest()
        assertEquals(1, est.keepRecent)
        assertTrue(est.fromOriginal)
        assertEquals(CarryOriginalsMode.AUTO, est.carryMode)

        assertThrows(BadRequestException::class.java) {
            CompressStartRequest(carryMode = "bogus").toCompressRequest()
        }
        assertThrows(BadRequestException::class.java) {
            CompressStartRequest(carryExcludedNodeIds = listOf("not-a-uuid")).toCompressRequest()
        }
        assertThrows(BadRequestException::class.java) {
            CompressEstimateRequest(carryCount = 99).toCompressRequest()
        }

        val stateDto = CompressState(
            status = CompressStatus.SUCCEEDED,
            origin = CompressOrigin.WEB,
            startedAt = 10,
            finishedAt = 20,
            requestCount = 3,
            completedChunks = 3,
            totalChunks = 3,
            chunked = true,
            fromOriginal = true,
            cacheBypassed = true,
            carriedCount = 2,
            totalUsage = TokenUsage(promptTokens = 100, completionTokens = 25, totalTokens = 125),
        ).toDto(Uuid.random())
        assertEquals(3, stateDto.requestCount)
        assertTrue(stateDto.chunked)
        assertTrue(stateDto.fromOriginal)
        assertTrue(stateDto.cacheBypassed)
        assertEquals(2, stateDto.carriedCount)
        assertEquals(125, stateDto.totalUsage?.totalTokens)
    }

    @Test
    fun `batch 3 start request, state dto, context records, and usage breakdown map cleanly`() {
        val convId = Uuid.random()
        val targetId = Uuid.random()
        val startReq = CompressStartRequest(
            keepRecent = 2,
            review = true,
            createNewConversation = true,
        ).toCompressRequest()
        assertTrue(startReq.createNewConversation)

        val stateDto = CompressState(
            status = CompressStatus.SUCCEEDED,
            origin = CompressOrigin.WEB,
            startedAt = 100L,
            finishedAt = 200L,
            createNewConversation = true,
            targetConversationId = targetId,
        ).toDto(convId)
        assertTrue(stateDto.createNewConversation)
        assertEquals(targetId.toString(), stateDto.targetConversationId)

        val nodeId = Uuid.random()
        val msgId = Uuid.random()
        val previewNodeId = Uuid.random()
        val previewMsgId = Uuid.random()
        val laterId = Uuid.random()
        val recordDto = ContextCheckpointRecord(
            nodeId = nodeId,
            messageId = msgId,
            nodeIndex = 2,
            createdAt = "2026-11-20T00:00:00Z",
            isClear = false,
            summaryText = "Summary text",
            meta = ContextCheckpointMetadata(kind = ContextCheckpointKind.MANUAL, templateId = "code", summarizedCount = 1),
            rangeStartIndex = 0,
            rangeEndIndex = 1,
            rangeStartNodeId = previewNodeId,
            rangeEndNodeId = previewNodeId,
            coveredMessageCount = 1,
            coveredPreviews = listOf(
                ContextRecordMessagePreview(previewNodeId, previewMsgId, 0, MessageRole.USER, "hi")
            ),
            isActive = false,
            laterCheckpointNodeIds = listOf(laterId),
            supersededByNodeId = laterId,
        ).toDto()
        assertEquals("user", recordDto.coveredPreviews.single().role)
        assertEquals("code", recordDto.meta?.templateId)
        assertEquals(laterId.toString(), recordDto.supersededByNodeId)

        val usageDto = ContextUsageBreakdown(
            totalTokens = 900,
            totalExact = false,
            reportedContextTokens = 800,
            estimatedTotalTokens = 850,
            contextLength = 4096,
            inputBudgetTokens = 3500,
            systemPromptTokens = 100,
            memoryTokens = 50,
            lorebookAndTavernTokens = 40,
            toolsLocalTokens = 120,
            toolsMcpTokens = 80,
            toolsSkillsTokens = 30,
            toolsWorkspaceTokens = 70,
            historyCheckpointTokens = 90,
            historyMessagesTokens = 140,
            attachmentsTokens = 30,
            draftTokens = 100,
            toolsLocalCount = 2,
            toolsMcpCount = 1,
            toolsSkillsCount = 1,
            toolsWorkspaceCount = 1,
            historyMessageCount = 3,
            hasActiveCheckpoint = true,
            attachmentCount = 1,
            activeLorebookCount = 2,
            memoryFileCount = 2,
        ).toDto()
        assertEquals(900, usageDto.totalTokens)
        assertEquals(120, usageDto.toolsLocalTokens)
        assertEquals(80, usageDto.toolsMcpTokens)
        assertEquals(30, usageDto.toolsSkillsTokens)
        assertEquals(70, usageDto.toolsWorkspaceTokens)
    }
}
