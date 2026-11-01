package me.rerere.rikkihub.service

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import kotlin.uuid.Uuid
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import kotlinx.serialization.json.JsonPrimitive

/**
 * 后台文本请求（标题/建议/压缩）必须携带所属 conversationId 作为
 * provider 会话身份；断言走生产函数本身，不重复其字段映射逻辑。
 */
class BackgroundTextGenerationParamsTest {

    private fun model() = Model(
        modelId = "fast-model",
        customHeaders = listOf(CustomHeader("X-A", "1")),
        customBodies = listOf(CustomBody("k", JsonPrimitive("v"))),
    )

    @Test
    fun `params carry the owning conversation id as session identity`() {
        val conversationId = Uuid.random()
        val params = backgroundTextGenerationParams(model(), conversationId)
        assertEquals(conversationId.toString(), params.sessionId)
    }

    @Test fun `standalone background helper remains source compatible and creates identity`() {
        assertNotNull(backgroundTextGenerationParams(model()).sessionId)
    }

    @Test fun `standalone background helper keeps model manual session headers`() {
        val custom = model().copy(customHeaders = listOf(CustomHeader("X-Session-ID", "manual")))
        val params = backgroundTextGenerationParams(custom)
        assertNull(params.sessionId)
        assertEquals(custom.customHeaders, params.customHeaders)
    }

    @Test
    fun `reasoning level and custom fields are preserved`() {
        val params = backgroundTextGenerationParams(
            model(), Uuid.random(), ReasoningLevel.LOW,
        )
        assertEquals(ReasoningLevel.LOW, params.reasoningLevel)
        assertEquals("X-A", params.customHeaders.single().name)
        assertEquals("k", params.customBody.single().key)
    }
}
