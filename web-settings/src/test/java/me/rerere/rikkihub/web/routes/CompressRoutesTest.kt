package me.rerere.rikkihub.web.routes

import me.rerere.rikkihub.service.CompressErrorCodes
import me.rerere.rikkihub.service.CompressOrigin
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.service.CompressStatus
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
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
}
