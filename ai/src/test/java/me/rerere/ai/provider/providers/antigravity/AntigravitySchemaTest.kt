package me.rerere.ai.provider.providers.antigravity

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravitySchemaTest {
    private fun schema(text: String) = json.parseToJsonElement(text)
    private fun JsonObject.prop(name: String) = this["properties"]!!.jsonObject[name]!!.jsonObject
    private fun JsonObject.desc() = this["description"]?.jsonPrimitive?.content

    @Test
    fun `enum and constraints move into the description and are removed`() {
        val cleaned = AntigravitySchema.cleanToolSchema(
            schema(
                """{"type":"object","properties":{
                  "mode":{"type":"string","enum":["a","b"],"description":"Mode"},
                  "n":{"type":"integer","minimum":1,"maximum":5},
                  "p":{"type":"string","format":"uri","pattern":"^x"}
                },"required":["mode","gone"],"additionalProperties":false,"${'$'}schema":"x"}"""
            ),
            claude = false,
        )
        assertEquals("Mode (Allowed: a, b)", cleaned.prop("mode").desc())
        assertFalse("enum" in cleaned.prop("mode"))
        assertEquals("minimum: 1 (maximum: 5)", cleaned.prop("n").desc())
        assertFalse("minimum" in cleaned.prop("n"))
        assertFalse("format" in cleaned.prop("p") || "pattern" in cleaned.prop("p"))
        assertEquals(listOf("mode"), cleaned["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("additionalProperties" in cleaned || "\$schema" in cleaned)
        assertEquals("No extra properties allowed", cleaned.desc())
    }

    @Test
    fun `const becomes a single allowed hint`() {
        val cleaned = AntigravitySchema.cleanToolSchema(
            schema("""{"type":"object","properties":{"k":{"type":"string","const":"only"}}}"""),
            claude = false,
        )
        assertEquals("Allowed: only", cleaned.prop("k").desc())
        assertFalse("const" in cleaned.prop("k") || "enum" in cleaned.prop("k"))
    }

    @Test
    fun `anyOf picks the strongest branch and marks nullable`() {
        val cleaned = AntigravitySchema.cleanToolSchema(
            schema(
                """{"type":"object","properties":{"v":{"description":"Value","anyOf":[
                  {"type":"string"},{"type":"object","properties":{"a":{"type":"string"}}},{"type":"null"}]}}}"""
            ),
            claude = false,
        )
        val v = cleaned.prop("v")
        assertEquals("object", v["type"]!!.jsonPrimitive.content)
        assertEquals("true", v["nullable"]!!.jsonPrimitive.content)
        assertEquals("Value (Accepts: string | object | null)", v.desc())
        assertTrue("a" in v["properties"]!!.jsonObject)
    }

    @Test
    fun `type arrays collapse to one type with nullable`() {
        val cleaned = AntigravitySchema.cleanToolSchema(
            schema("""{"type":"object","properties":{"v":{"type":["string","null"]}}}"""),
            claude = false,
        )
        val v = cleaned.prop("v")
        assertEquals("string", v["type"]!!.jsonPrimitive.content)
        assertEquals("true", v["nullable"]!!.jsonPrimitive.content)
        assertEquals("(nullable)", v.desc())
    }

    @Test
    fun `local refs are inlined and cycles fall back to a hint`() {
        val cleaned = AntigravitySchema.cleanToolSchema(
            schema(
                """{"type":"object","${'$'}defs":{"Node":{"type":"object","description":"A node",
                  "properties":{"child":{"${'$'}ref":"#/${'$'}defs/Node"}}}},
                  "properties":{"root":{"${'$'}ref":"#/${'$'}defs/Node"}}}"""
            ),
            claude = false,
        )
        assertFalse("\$defs" in cleaned)
        val root = cleaned.prop("root")
        assertEquals("A node", root.desc())
        assertEquals("A node (See: Node)", root.prop("child").desc())
        assertFalse("\$ref" in root.prop("child"))
    }

    @Test
    fun `arrays get items and properties force object type`() {
        val cleaned = AntigravitySchema.cleanToolSchema(
            schema("""{"properties":{"list":{"type":"array"},"obj":{"properties":{"x":{"type":"string"}}}}}"""),
            claude = false,
        )
        assertEquals("object", cleaned["type"]!!.jsonPrimitive.content)
        assertEquals("string", cleaned.prop("list")["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("object", cleaned.prop("obj")["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `property named like a keyword is kept`() {
        val cleaned = AntigravitySchema.cleanToolSchema(
            schema("""{"type":"object","properties":{"format":{"type":"string"},"id":{"type":"string"}},"required":["format"]}"""),
            claude = false,
        )
        assertTrue("format" in cleaned["properties"]!!.jsonObject)
        assertTrue("id" in cleaned["properties"]!!.jsonObject)
    }

    @Test
    fun `title is dropped for gemini and kept for claude`() {
        val input = schema("""{"type":"object","title":"T","properties":{"a":{"type":"string","title":"A"}},"required":["a"]}""")
        assertNull(AntigravitySchema.cleanToolSchema(input, claude = false)["title"])
        assertEquals("T", AntigravitySchema.cleanToolSchema(input, claude = true)["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `claude placeholders for empty objects and objects without required`() {
        val empty = AntigravitySchema.cleanToolSchema(schema("""{"type":"object","properties":{}}"""), claude = true)
        assertEquals(listOf("reason"), empty["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("string", empty.prop("reason")["type"]!!.jsonPrimitive.content)

        val optional = AntigravitySchema.cleanToolSchema(
            schema("""{"type":"object","properties":{"q":{"type":"string"}}}"""),
            claude = true,
        )
        assertEquals(listOf("_"), optional["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("boolean", optional.prop("_")["type"]!!.jsonPrimitive.content)

        val nullSchema = AntigravitySchema.cleanToolSchema(kotlinx.serialization.json.JsonNull, claude = true)
        assertEquals(listOf("reason"), nullSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val gemini = AntigravitySchema.cleanToolSchema(schema("""{"type":"object","properties":{}}"""), claude = false)
        assertNull(gemini["required"])
    }

    @Test
    fun `placeholder args are stripped only where the original schema lacks them`() {
        val original = schema("""{"type":"object","properties":{"q":{"type":"string"},"opts":{"type":"object","properties":{"k":{"type":"string"}}}}}""")
        val args = schema("""{"q":"x","_":true,"reason":"why","opts":{"k":"v","_":false}}""")
        val stripped = AntigravitySchema.stripPlaceholderArgs(args, original).jsonObject
        assertEquals(setOf("q", "opts"), stripped.keys)
        assertEquals(setOf("k"), stripped["opts"]!!.jsonObject.keys)

        val declared = schema("""{"type":"object","properties":{"reason":{"type":"string"}}}""")
        val kept = AntigravitySchema.stripPlaceholderArgs(schema("""{"reason":"real"}"""), declared).jsonObject
        assertEquals("real", kept["reason"]!!.jsonPrimitive.content)
    }
}
