package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPricingTest {
    @Test fun `cached input falls back to regular input without double charging`() {
        val price = ModelPricing(input = 2.0, output = 4.0)
        assertEquals(0.00028, price.cost(100, 20, 40), 1e-12)
        assertEquals(120L, price.pricedTokens(100, 20, 40))
        assertEquals(0.00012, price.copy(cachedInput = 1.0).cost(100, 0, 80), 1e-12)
    }

    @Test fun `coverage distinguishes missing fields and explicit free prices`() {
        assertEquals(40L, ModelPricing(cachedInput = 0.0).pricedTokens(100, 20, 40))
        assertEquals(20L, ModelPricing(output = 0.0).pricedTokens(100, 20, 40))
        assertEquals(120L, ModelPricing(input = 0.0, output = 0.0).pricedTokens(100, 20, 40))
        assertEquals(0L, ModelPricing().pricedTokens(100, 20, 40))
    }

    @Test fun `out of range cached counts are clamped to actual input`() {
        val price = ModelPricing(input = 2.0, cachedInput = 1.0)
        assertEquals(0.0001, price.cost(100, 0, 999), 1e-12)
        assertEquals(100L, price.pricedTokens(100, 0, 999))
        assertEquals(0.0002, price.cost(100, 0, -20), 1e-12)
    }
}
