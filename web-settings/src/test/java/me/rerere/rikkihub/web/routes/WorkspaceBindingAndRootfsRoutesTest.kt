package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

/** Chat-bar workspace switch (`PUT /assistants/{id}/workspace`) and Web rootfs install body. */
class WorkspaceBindingAndRootfsRoutesTest {
    @Test
    fun `workspace id is a uuid string or null and the key is required`() {
        val id = Uuid.random()
        assertEquals(id, parseAssistantWorkspaceId(buildJsonObject { put("workspaceId", JsonPrimitive(id.toString())) }))
        assertNull(parseAssistantWorkspaceId(buildJsonObject { put("workspaceId", JsonNull) }))
        listOf(
            buildJsonObject { },
            buildJsonObject { put("workspaceId", JsonPrimitive("not-a-uuid")) },
            buildJsonObject { put("workspaceId", JsonPrimitive(3)) },
            buildJsonObject { put("workspaceId", buildJsonObject { }) },
        ).forEach { body -> assertThrows(BadRequestException::class.java) { parseAssistantWorkspaceId(body) } }
    }

    @Test
    fun `only the target assistant's workspace changes, everything else is kept`() {
        val other = Assistant(name = "other", workspaceId = Uuid.random())
        val target = Assistant(name = "target", systemPrompt = "keep me", enabledSkills = setOf("s"))
        val workspace = Uuid.random()
        val updated = withWorkspaceId(listOf(other, target), target.id, workspace)!!
        assertEquals(other, updated[0])
        assertEquals(target.copy(workspaceId = workspace), updated[1])
        assertEquals(target.copy(workspaceId = null), withWorkspaceId(updated, target.id, null)!![1])
        assertNull(withWorkspaceId(listOf(other), target.id, workspace))
    }

    @Test
    fun `rootfs install body is optional and only takes http urls`() {
        assertNull(parseRootfsInstallUrl(""))
        assertNull(parseRootfsInstallUrl("{}"))
        assertNull(parseRootfsInstallUrl("""{"url":"  "}"""))
        assertNull(parseRootfsInstallUrl("""{"url":null}"""))
        assertEquals("https://example.com/rootfs.tar.gz", parseRootfsInstallUrl("""{"url":" https://example.com/rootfs.tar.gz "}"""))
        listOf("""{"url":"file:///sdcard/x.tar.gz"}""", """{"url":1}""", "[1]", "nope").forEach { body ->
            assertThrows(body, BadRequestException::class.java) { parseRootfsInstallUrl(body) }
        }
    }
}
