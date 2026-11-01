package me.rerere.rikkihub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AIIconMatcherTest {
    @Test
    fun `wan models use the qwen icon`() {
        assertEquals("qwen-color.svg", computeAIIconByName("wan3.0-video-prime"))
        assertEquals("qwen-color.svg", computeAIIconByName("wanx2.1-t2i-turbo"))
        assertEquals("qwen-color.svg", computeAIIconByName("wan-2.2"))
        assertNotEquals("qwen-color.svg", computeAIIconByName("swan-model"))
    }

    @Test
    fun `hunyuan hy models use the hunyuan icon`() {
        assertEquals("hunyuan-color.svg", computeAIIconByName("hy3-preview"))
        assertEquals("hunyuan-color.svg", computeAIIconByName("HY-turbo"))
        assertEquals("hunyuan-color.svg", computeAIIconByName("hunyuan-t1"))
        assertNotEquals("hunyuan-color.svg", computeAIIconByName("why-model"))
    }
}
