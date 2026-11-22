package me.rerere.ai.registry

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelPricing
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModelReferencePriceTest {
    private val price = ModelPricing(input = 2.0, output = 10.0, cachedInput = 0.2)

    @Before fun install() {
        ModelRegistry.installCatalog {
            ModelsDevCatalog.decode("""{"version":1,"entries":{
                "gpt-5.2":[13,400000,2,10,0.2],
                "gpt-5.4":[13,1000000,2,10,0.2],
                "claude-opus-4.5":[13,200000,2,10,0.2],
                "claude-haiku-4.5":[13,200000,2,10,0.2],
                "claude-sonnet-4.5":[13,200000,2,10,0.2],
                "gpt-4.1":[5,1000000,2,10,0.2],
                "gpt-5.1":[13,400000,2,10,0.2],
                "gemini-3-flash":[13,1000000,2,10,0.2],
                "claude-fable-5":[13,1000000,2,10,0.2],
                "gpt-6-sol":[13,1000000,2,10,0.2],
                "gpt-6-sol-high":[13,1000000,0,0,0],
                "gpt-6-sol-max":[13,1000000],
                "claude-sonnet-4-6":[13,1000000,2,10,0.2]
            }}""")
        }
    }

    @After fun uninstall() = ModelRegistry.installCatalog { null }

    @Test fun `Devin protocol UIDs resolve through the trusted catalog names`() {
        for (id in listOf("MODEL_GPT_5_2", "MODEL_CLAUDE_4_5_OPUS", "MODEL_PRIVATE_11",
            "MODEL_PRIVATE_2", "MODEL_PRIVATE_3", "MODEL_CHAT_GPT_4_1_2025_04_14",
            "MODEL_PRIVATE_12", "MODEL_PRIVATE_13", "MODEL_PRIVATE_14", "MODEL_PRIVATE_15",
            "MODEL_GOOGLE_GEMINI_3_0_FLASH", "claude-5-fable")) {
            assertEquals(id, price, ModelRegistry.referencePrice(id))
        }
    }

    @Test fun `OAuth reasoning variants and numeric separators find their reference price`() {
        for (id in listOf("devin/MODEL_GPT_5_2_HIGH", "model_claude_4_5_opus_thinking",
            "claude-sonnet-4-6-thinking", "gpt-5.4-high", "claude-sonnet-4.6-high")) {
            assertEquals(id, price, ModelRegistry.referencePrice(id))
        }
    }

    @Test fun `exact free and unpriced entries win and unknown premium variants are never guessed`() {
        assertEquals(ModelPricing(0.0, 0.0, 0.0), ModelRegistry.referencePrice("gpt-6-sol-high"))
        for (id in listOf("gpt-6-sol-max", "gpt-6-sol-fast", "gpt-6-sol-priority",
            "claude-sonnet-4-6-1m", "MODEL_PRIVATE_UNKNOWN", "swe-2")) {
            assertNull(id, ModelRegistry.referencePrice(id))
        }
    }

    @Test fun `price lookup never applies prices without the user's fill action`() {
        val model = Model(modelId = "gpt-6-sol")
        assertEquals(price, ModelRegistry.referencePrice(model.modelId))
        assertNull(ModelRegistry.withDetectedCapabilities(model).pricing)
    }
}
