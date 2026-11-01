package me.rerere.rikkihub.web.routes

import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.web.dto.toDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkspaceDto.toolEnablement / toolEnablementCorrupt 的序列化语义:
 * 合法 JSON → map + corrupt=false; 损坏 → null + corrupt=true (fail-closed 显式受限).
 */
class WorkspaceDtoEnablementTest {

    private fun entity(toolEnablement: String) = WorkspaceEntity(
        id = "w1",
        name = "ws",
        root = "w1",
        createdAt = 0L,
        updatedAt = 0L,
        toolEnablement = toolEnablement,
    )

    @Test
    fun validEnablementJsonIsExposedAsMap() {
        val dto = entity("""{"workspace_shell":false,"bash":true}""").toDto()
        assertEquals(mapOf("workspace_shell" to false, "bash" to true), dto.toolEnablement)
        assertFalse(dto.toolEnablementCorrupt)
    }

    @Test
    fun defaultEmptyObjectIsAllEnabled() {
        val dto = entity("{}").toDto()
        assertEquals(emptyMap<String, Boolean>(), dto.toolEnablement)
        assertFalse(dto.toolEnablementCorrupt)
    }

    @Test
    fun corruptedJsonIsNullAndMarkedCorrupt() {
        val dto = entity("not-json").toDto()
        assertNull(dto.toolEnablement)
        assertTrue(dto.toolEnablementCorrupt)
    }

    @Test
    fun unknownKeyJsonIsMarkedCorrupt() {
        val dto = entity("""{"not_a_tool":false}""").toDto()
        assertNull(dto.toolEnablement)
        assertTrue(dto.toolEnablementCorrupt)
    }

    @Test
    fun nonBooleanValueIsMarkedCorrupt() {
        val dto = entity("""{"bash":"false"}""").toDto()
        assertNull(dto.toolEnablement)
        assertTrue(dto.toolEnablementCorrupt)
    }

    @Test
    fun blankStringIsMarkedCorrupt() {
        val dto = entity("").toDto()
        assertNull(dto.toolEnablement)
        assertTrue(dto.toolEnablementCorrupt)
    }
}
