package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.Json
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.dto.TransferWorkspaceFilesRequest
import me.rerere.rikkihub.web.dto.TransferWorkspaceFilesResponse
import me.rerere.rikkihub.web.dto.WorkspaceTransferItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTransferRoutesTest {
    @Test
    fun `request defaults to this workspace and its root folder`() {
        val request = Json.decodeFromString<TransferWorkspaceFilesRequest>("""{"paths":["a.md","dir"],"mode":"copy"}""")
        assertEquals(listOf("a.md", "dir"), request.paths)
        assertNull(request.targetWorkspaceId)
        assertEquals("", request.targetDir)
        assertEquals("copy", request.mode)
    }

    @Test
    fun `paths keep their order and pass when within the batch limits`() {
        assertEquals(listOf("b", "a/c"), validateTransferPaths(listOf("b", "a/c")))
    }

    @Test
    fun `paths outside the batch limits are rejected`() {
        assertThrows(BadRequestException::class.java) { validateTransferPaths(emptyList()) }
        assertThrows(BadRequestException::class.java) { validateTransferPaths(listOf(" ")) }
        assertThrows(BadRequestException::class.java) { validateTransferPaths(listOf("a\u0000b")) }
        assertThrows(BadRequestException::class.java) { validateTransferPaths(List(MAX_BATCH_PATH_COUNT + 1) { "f$it" }) }
        assertThrows(BadRequestException::class.java) { validateTransferPaths(listOf("x".repeat(MAX_BATCH_PATH_LENGTH + 1))) }
    }

    @Test
    fun `response carries per-item results`() {
        val json = Json.encodeToString(
            TransferWorkspaceFilesResponse.serializer(),
            TransferWorkspaceFilesResponse(
                targetWorkspaceId = "w2",
                targetDir = "archive",
                items = listOf(
                    WorkspaceTransferItemDto("a.md", ok = true, target = "archive/a (1).md", renamed = true),
                    WorkspaceTransferItemDto("gone.md", ok = false, errorCode = "FILE_NOT_FOUND", error = "Does not exist: gone.md"),
                ),
            ),
        )
        assertTrue(json, json.contains("\"target\":\"archive/a (1).md\""))
        assertTrue(json, json.contains("\"errorCode\":\"FILE_NOT_FOUND\""))
    }
}
