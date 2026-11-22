package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelPricing
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.*
import org.junit.Test

class ModelPricingMutationTest {
    private fun draft(body: String) = parseModelUpdate(JsonInstant.parseToJsonElement(body).jsonObject)

    @Test fun `missing prices retain old values while null and empty explicitly clear`() {
        val model = Model(modelId = "m", pricing = ModelPricing(input = 2.0))
        assertEquals(model.pricing, mergeModel(model, draft("""{"modelId":"m"}""")).pricing)
        assertNull(mergeModel(model, draft("""{"modelId":"m","pricing":null}""")).pricing)
        assertNull(mergeModel(model, draft("""{"modelId":"m","pricing":{}}""")).pricing)
        assertEquals(ModelPricing(input = 0.0), mergeModel(model, draft("""{"modelId":"m","pricing":{"input":0}}""")).pricing)
    }

    @Test fun `price bounds are validated on the backend not just in the browser`() {
        for (value in listOf("-1", "1000001", "\"not a number\"", "true")) {
            assertThrows(BadRequestException::class.java) { draft("""{"pricing":{"input":$value}}""") }
        }
        assertEquals(1_000_000.0, draft("""{"pricing":{"input":1000000}}""").incoming.pricing!!.input!!, 0.0)
    }
}
