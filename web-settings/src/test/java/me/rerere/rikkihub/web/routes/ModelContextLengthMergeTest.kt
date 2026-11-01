package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.DEFAULT_MODEL_CONTEXT_LENGTH
import me.rerere.ai.provider.MAX_MODEL_CONTEXT_LENGTH
import me.rerere.ai.provider.Model
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 模型 PUT 是整份替换，contextLength 要按有无字段合并，旧版网页不带它时不能被改回默认值。 */
class ModelContextLengthMergeTest {
    private val existing = Model(modelId = "m", displayName = "M", contextLength = 200_000)
    private val raw = JsonInstant.encodeToJsonElement(Model.serializer(), existing).jsonObject

    @Test
    fun `missing contextLength keeps the stored value`() {
        val body = JsonObject(raw - "contextLength" + ("displayName" to JsonPrimitive("renamed")))
        val merged = mergeModel(existing, parseModelUpdate(body))
        assertEquals("renamed", merged.displayName)
        assertEquals(200_000, merged.contextLength)
    }

    @Test
    fun `present contextLength replaces the stored value`() {
        val body = JsonObject(raw + ("contextLength" to JsonPrimitive(DEFAULT_MODEL_CONTEXT_LENGTH)))
        assertEquals(DEFAULT_MODEL_CONTEXT_LENGTH, mergeModel(existing, parseModelUpdate(body)).contextLength)
    }

    @Test
    fun `out of range contextLength is a bad request`() {
        listOf(0, -1, MAX_MODEL_CONTEXT_LENGTH + 1).forEach { value ->
            val body = JsonObject(raw + ("contextLength" to JsonPrimitive(value)))
            assertThrows(BadRequestException::class.java) { parseModelUpdate(body) }
        }
    }
}
