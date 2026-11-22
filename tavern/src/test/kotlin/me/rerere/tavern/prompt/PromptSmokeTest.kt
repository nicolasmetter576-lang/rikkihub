package me.rerere.tavern.prompt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 冒烟：录好的单例冒烟 golden，验证回放管线 + 组装器主干。 */
class PromptSmokeTest {
    @Test
    fun smoke() {
        val file = PromptGoldenSupport.load("golden/prompt/smoke.json")
        for (c in file["cases"] as JsonArray) {
            val co = c as JsonObject
            val r = PromptGoldenSupport.replay(co)
            val got = PromptGoldenSupport.toWire(PromptAssembler.assemble(r.input, r.host), r.input, r.host)
            assertEquals(
                co["name"]!!.jsonPrimitive.content,
                r.expectedMessages.map { Triple(it.role, it.name, it.content) },
                got.map { Triple(it.role, it.name, it.content) },
            )
        }
    }
}
