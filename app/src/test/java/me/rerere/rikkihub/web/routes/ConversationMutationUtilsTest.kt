package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMutationUtilsTest {
    private fun patchBody(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, value) }
        }

    private fun string(value: String) = JsonPrimitive(value)

    @Test
    fun `parses a full patch`() {
        val patch = parseConversationMetadataPatch(
            body = patchBody(
                "title" to string("  My title  "),
                "customSystemPrompt" to string("be terse"),
                "workspaceCwd" to string("/workspace/project"),
            ),
            allowConversationSystemPrompt = true,
        )

        assertTrue(patch.patchedTitle)
        assertEquals("My title", patch.title)
        assertTrue(patch.patchedCustomSystemPrompt)
        assertEquals("be terse", patch.customSystemPrompt)
        assertTrue(patch.patchedWorkspaceCwd)
        assertEquals("/workspace/project", patch.workspaceCwd)
    }

    @Test
    fun `explicit null clears optional fields`() {
        val patch = parseConversationMetadataPatch(
            body = patchBody(
                "customSystemPrompt" to JsonNull,
                "workspaceCwd" to JsonNull,
            ),
            allowConversationSystemPrompt = false,
        )

        assertTrue(patch.patchedCustomSystemPrompt)
        assertNull(patch.customSystemPrompt)
        assertTrue(patch.patchedWorkspaceCwd)
        assertNull(patch.workspaceCwd)
    }

    @Test(expected = BadRequestException::class)
    fun `rejects unknown keys`() {
        parseConversationMetadataPatch(
            body = patchBody("isPinned" to JsonPrimitive(true)),
            allowConversationSystemPrompt = true,
        )
    }

    @Test(expected = BadRequestException::class)
    fun `rejects empty patch`() {
        parseConversationMetadataPatch(
            body = JsonObject(emptyMap()),
            allowConversationSystemPrompt = true,
        )
    }

    @Test(expected = BadRequestException::class)
    fun `rejects blank title`() {
        parseConversationMetadataPatch(
            body = patchBody("title" to string("   ")),
            allowConversationSystemPrompt = true,
        )
    }

    @Test(expected = BadRequestException::class)
    fun `rejects null title`() {
        parseConversationMetadataPatch(
            body = patchBody("title" to JsonNull),
            allowConversationSystemPrompt = true,
        )
    }

    @Test(expected = BadRequestException::class)
    fun `rejects non-string title`() {
        parseConversationMetadataPatch(
            body = patchBody("title" to JsonPrimitive(3)),
            allowConversationSystemPrompt = true,
        )
    }

    @Test
    fun `system prompt requires assistant opt-in`() {
        // 开启时允许设置
        val patch = parseConversationMetadataPatch(
            body = patchBody("customSystemPrompt" to string("custom")),
            allowConversationSystemPrompt = true,
        )
        assertEquals("custom", patch.customSystemPrompt)

        // 关闭时设置 -> 400
        try {
            parseConversationMetadataPatch(
                body = patchBody("customSystemPrompt" to string("custom")),
                allowConversationSystemPrompt = false,
            )
            throw AssertionError("expected BadRequestException")
        } catch (expected: BadRequestException) {
        }

        // 关闭时清除仍然放行
        val cleared = parseConversationMetadataPatch(
            body = patchBody("customSystemPrompt" to JsonNull),
            allowConversationSystemPrompt = false,
        )
        assertTrue(cleared.patchedCustomSystemPrompt)
        assertNull(cleared.customSystemPrompt)
    }

    @Test
    fun `blank system prompt clears like the app`() {
        val patch = parseConversationMetadataPatch(
            body = patchBody("customSystemPrompt" to string("   ")),
            allowConversationSystemPrompt = false,
        )
        assertTrue(patch.patchedCustomSystemPrompt)
        assertNull(patch.customSystemPrompt)
    }

    @Test(expected = BadRequestException::class)
    fun `rejects non-string system prompt`() {
        parseConversationMetadataPatch(
            body = patchBody("customSystemPrompt" to JsonPrimitive(true)),
            allowConversationSystemPrompt = true,
        )
    }

    @Test
    fun `workspaceCwd must be an absolute path without dotdot segments`() {
        val ok = parseConversationMetadataPatch(
            body = patchBody("workspaceCwd" to string("/workspace")),
            allowConversationSystemPrompt = false,
        )
        assertEquals("/workspace", ok.workspaceCwd)

        val cases = listOf(
            "relative/path",
            "workspace",
            "/workspace/../etc",
            "/../x",
            "",
        )
        for (cwd in cases) {
            try {
                parseConversationMetadataPatch(
                    body = patchBody("workspaceCwd" to string(cwd)),
                    allowConversationSystemPrompt = false,
                )
                throw AssertionError("expected BadRequestException for $cwd")
            } catch (expected: BadRequestException) {
            }
        }
    }

    @Test(expected = BadRequestException::class)
    fun `rejects non-string workspaceCwd`() {
        parseConversationMetadataPatch(
            body = patchBody("workspaceCwd" to JsonPrimitive(42)),
            allowConversationSystemPrompt = false,
        )
    }

    @Test
    fun `partial patch leaves flags untouched`() {
        val patch = parseConversationMetadataPatch(
            body = patchBody("title" to string("only title")),
            allowConversationSystemPrompt = false,
        )
        assertTrue(patch.patchedTitle)
        assertFalse(patch.patchedCustomSystemPrompt)
        assertFalse(patch.patchedWorkspaceCwd)
    }

    @Test
    fun `workspaceCwdScoped is a boolean on its own and absent means unchanged`() {
        val off = parseConversationMetadataPatch(
            body = patchBody("workspaceCwdScoped" to JsonPrimitive(false)),
            allowConversationSystemPrompt = false,
        )
        assertEquals(false, off.workspaceCwdScoped)
        assertFalse(off.patchedWorkspaceCwd)
        assertNull(parseConversationMetadataPatch(
            body = patchBody("title" to string("t")),
            allowConversationSystemPrompt = false,
        ).workspaceCwdScoped)
        for (bad in listOf(string("true"), JsonPrimitive(1), JsonNull)) {
            try {
                parseConversationMetadataPatch(body = patchBody("workspaceCwdScoped" to bad), allowConversationSystemPrompt = false)
                throw AssertionError("should reject $bad")
            } catch (expected: BadRequestException) {
            }
        }
    }
}
