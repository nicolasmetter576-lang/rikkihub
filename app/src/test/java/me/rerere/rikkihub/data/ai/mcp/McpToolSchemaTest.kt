package me.rerere.rikkihub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpToolSchemaTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** 以 SDK 真实序列化（inputSchema JSON）构造 ToolSchema，而非直接调构造函数。 */
    private fun decodeSchema(inputSchema: String): ToolSchema =
        Json.decodeFromString(inputSchema)

    private fun convert(properties: String, defs: String? = null): JsonObject {
        val inputSchema = buildString {
            append("""{"type":"object","required":["trigger"],"properties":""")
            append(properties)
            if (defs != null) {
                append(""","${'$'}defs":""")
                append(defs)
            }
            append("}")
        }
        val schema = Json.decodeFromString<ToolSchema>(inputSchema).toSchema() as InputSchema.Obj
        assertEquals(listOf("trigger"), schema.required)
        return schema.properties
    }

    private fun assertNoDanglingRef(element: JsonObject) {
        assertFalse(
            "dangling \$ref leaked into converted schema: $element",
            element.toString().contains("\"${'$'}ref\"")
        )
    }

    @Test
    fun `sdk wire uses dollar defs serial name`() {
        // SDK 0.15 的 ToolSchema 在 wire 上就是 "$defs"（见 ToolSchema$$serializer），
        // 真实 MCP 服务器发的是这个键；若序列化名漂移，$defs 会静默丢失，本测试会失败。
        val schema = decodeSchema(
            """{"type":"object","properties":{},"${'$'}defs":{"A":{"type":"string"}}}"""
        )
        assertEquals(json("""{"A":{"type":"string"}}"""), schema.defs)
    }

    @Test
    fun `schema without refs is kept as is`() {
        val properties = """{"query":{"type":"string","description":"keyword"}}"""

        assertEquals(json(properties), convert(properties))
    }

    @Test
    fun `refs are inlined and sibling keywords override the definition`() {
        val result = convert(
            properties = """
                {
                  "trigger": {"${'$'}ref": "#/${'$'}defs/Trigger", "description": "override"},
                  "items": {"type": "array", "items": {"${'$'}ref": "#/${'$'}defs/Trigger"}},
                  "choice": {"anyOf": [{"${'$'}ref": "#/${'$'}defs/Trigger"}, {"type": "null"}]}
                }
            """,
            defs = """
                {
                  "Trigger": {
                    "type": "object",
                    "description": "original",
                    "properties": {"spec": {"${'$'}ref": "#/${'$'}defs/Spec"}}
                  },
                  "Spec": {"type": "string", "enum": ["a", "b"]}
                }
            """,
        )

        val trigger = """{"type":"object","description":"original","properties":{"spec":{"type":"string","enum":["a","b"]}}}"""
        assertEquals(
            json(
                """
                {
                  "trigger": {"type":"object","description":"override","properties":{"spec":{"type":"string","enum":["a","b"]}}},
                  "items": {"type": "array", "items": $trigger},
                  "choice": {"anyOf": [$trigger, {"type": "null"}]}
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `literal data containing a ref field is kept as is`() {
        val properties = """
            {
              "trigger": {
                "enum": [{"${'$'}ref": "document.json"}],
                "const": {"${'$'}ref": "#/${'$'}defs/Spec"},
                "default": {"${'$'}ref": "document.json"},
                "examples": [{"${'$'}ref": "#/${'$'}defs/Spec"}]
              }
            }
        """

        assertEquals(json(properties), convert(properties, defs = """{"Spec": {"type": "string"}}"""))
    }

    @Test
    fun `properties named like keywords are still treated as schemas`() {
        val result = convert(
            properties = """
                {
                  "default": {"${'$'}ref": "#/${'$'}defs/Spec"},
                  "trigger": {"type": "object", "properties": {"enum": {"${'$'}ref": "#/${'$'}defs/Spec"}}}
                }
            """,
            defs = """{"Spec": {"type": "string"}}""",
        )

        assertEquals(
            json(
                """
                {
                  "default": {"type": "string"},
                  "trigger": {"type": "object", "properties": {"enum": {"type": "string"}}}
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `cyclic refs are cut instead of expanding forever`() {
        val result = convert(
            properties = """{"trigger": {"${'$'}ref": "#/${'$'}defs/Node"}}""",
            defs = """
                {
                  "Node": {
                    "type": "object",
                    "properties": {"children": {"type": "array", "items": {"${'$'}ref": "#/${'$'}defs/Node"}}}
                  }
                }
            """,
        )

        assertEquals(
            json(
                """
                {
                  "trigger": {
                    "type": "object",
                    "properties": {"children": {"type": "array", "items": {"type": "object"}}}
                  }
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `unresolvable refs are dropped so no dangling ref is sent`() {
        val result = convert(
            properties = """
                {
                  "trigger": {"${'$'}ref": "#/${'$'}defs/Missing", "description": "kept"},
                  "remote": {"${'$'}ref": "https://example.com/schema.json"}
                }
            """,
        )

        assertEquals(json("""{"trigger": {"description": "kept"}, "remote": {}}"""), result)
        assertNoDanglingRef(result)
    }

    // ---------- B16 边界 ----------

    @Test
    fun `bare hash ref resolves to the synthetic root`() {
        // "#" 是合法的 JSON Pointer（空 pointer → root），根是 {type, properties, $defs}
        val result = convert(
            properties = """{"trigger": {"${'$'}ref": "#"}}""",
            defs = """{"Spec": {"type": "string"}}""",
        )

        val trigger = result.getValue("trigger").jsonObject
        assertEquals(JsonPrimitive("object"), trigger.getValue("type"))
        assertEquals(Json.parseToJsonElement("""["trigger"]"""), trigger["required"])
        // 根里展开 properties 时回到自身，循环截断为 {"type":"object"}（不含 $defs）
        assertEquals(
            json("""{"type":"object"}"""),
            trigger.getValue("properties").jsonObject.getValue("trigger"),
        )
        assertNoDanglingRef(result)
    }

    @Test
    fun `anchor fragment is not a json pointer and is dropped`() {
        // "#anchor" / "#Trigger" 是 URI fragment 名称，不是 JSON Pointer，不能当根解析
        val result = convert(
            properties = """
                {
                  "a": {"${'$'}ref": "#anchor"},
                  "b": {"${'$'}ref": "#Trigger"},
                  "c": {"${'$'}ref": "#%2F%24defs%2FSpec", "description": "d"}
                }
            """,
            defs = """{"Spec": {"type": "string"}}""",
        )

        // "#%2F%24defs%2FSpec" 百分号解码后是 "#/$defs/Spec"，合法指针，正常展开
        assertEquals(
            json(
                """
                {
                  "a": {},
                  "b": {},
                  "c": {"type": "string", "description": "d"}
                }
                """
            ),
            result,
        )
        assertNoDanglingRef(result)
    }

    @Test
    fun `pointer tokens support percent encoding and tilde escapes`() {
        // RFC 6901：fragment 整体 percent 解码；%2F 解码出的 '/' 是分隔符，
        // 所以 x%2Fy → defs.x.y（token 内字面斜杠要用 ~1），UTF-8 字节序列还原中文键
        val result = convert(
            properties = """
                {
                  "tilde": {"${'$'}ref": "#/${'$'}defs/a~0b"},
                  "slash": {"${'$'}ref": "#/${'$'}defs/a~1b"},
                  "encoded": {"${'$'}ref": "#/${'$'}defs/%E4%B8%AD%E6%96%87"},
                  "encodedDelimiter": {"${'$'}ref": "#/${'$'}defs/x%2Fy"}
                }
            """,
            defs = """
                {
                  "a~b": {"type": "string"},
                  "a/b": {"type": "integer"},
                  "中文": {"type": "number"},
                  "x": {"y": {"type": "boolean"}}
                }
            """,
        )

        assertEquals(
            json(
                """
                {
                  "tilde": {"type": "string"},
                  "slash": {"type": "integer"},
                  "encoded": {"type": "number"},
                  "encodedDelimiter": {"type": "boolean"}
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `invalid tilde escapes are rejected`() {
        // "~2" 不是合法 JSON Pointer 转义，不能定位到同名字面量键。
        val result = convert(
            properties = """{"trigger": {"${'$'}ref": "#/${'$'}defs/a~2b"}}""",
            defs = """{"a~2b": {"type": "string"}, "a/b": {"type": "integer"}}""",
        )

        assertEquals(json("""{"trigger": {}}"""), result)
    }

    @Test
    fun `negative array index in pointer does not resolve`() {
        val result = convert(
            properties = """
                {
                  "neg": {"${'$'}ref": "#/prefixItems/-1"},
                  "ok": {"type": "array", "prefixItems": [{"${'$'}ref": "#/${'$'}defs/S"}]}
                }
            """,
            defs = """{"S": {"type": "string"}}""",
        )

        assertEquals(
            json(
                """
                {
                  "neg": {},
                  "ok": {"type": "array", "prefixItems": [{"type": "string"}]}
                }
                """
            ),
            result,
        )
        assertNoDanglingRef(result)
    }

    @Test
    fun `non string ref values are removed`() {
        val result = convert(
            properties = """
                {
                  "num": {"${'$'}ref": 42, "description": "d"},
                  "obj": {"${'$'}ref": {"id": "x"}},
                  "bool": {"${'$'}ref": true}
                }
            """,
        )

        assertEquals(
            json("""{"num": {"description": "d"}, "obj": {}, "bool": {}}"""),
            result,
        )
        assertNoDanglingRef(result)
    }

    @Test
    fun `legacy dependencies map expands schemas but keeps string arrays`() {
        val result = convert(
            properties = """
                {
                  "trigger": {
                    "type": "object",
                    "dependencies": {
                      "credit_card": {"properties": {"billing": {"${'$'}ref": "#/${'$'}defs/Addr"}}},
                      "name": ["first", "last"]
                    }
                  }
                }
            """,
            defs = """{"Addr": {"type": "string"}}""",
        )

        assertEquals(
            json(
                """
                {
                  "trigger": {
                    "type": "object",
                    "dependencies": {
                      "credit_card": {"properties": {"billing": {"type": "string"}}},
                      "name": ["first", "last"]
                    }
                  }
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `boolean ref targets keep their semantics`() {
        val result = convert(
            properties = """
                {
                  "allow": {"${'$'}ref": "#/${'$'}defs/Yes", "description": "kept"},
                  "deny": {"${'$'}ref": "#/${'$'}defs/No"}
                }
            """,
            defs = """{"Yes": true, "No": false}""",
        )

        // true → 无约束（仅保留同级 description）；false → 仍为永不匹配的布尔 Schema。
        assertEquals(json("""{"description": "kept"}"""), result.getValue("allow"))
        assertEquals(JsonPrimitive(false), result.getValue("deny"))
        assertNoDanglingRef(result)
    }

    @Test(timeout = 5_000)
    fun `diamond ref blowup is bounded and never leaves dangling refs`() {
        // D0..D30 每层引用下一层两次：朴素展开是 2^31 级别，必须有界且不残留 $ref
        val defs = buildJsonObject {
            for (i in 0 until 30) {
                put("D$i", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        for (name in listOf("x", "y")) put(name, buildJsonObject {
                            put("${'$'}ref", "#/${'$'}defs/D${i + 1}")
                        })
                    })
                })
            }
            put("D30", json("""{"type":"string"}"""))
        }.toString()

        val result = convert(
            properties = """{"trigger": {"${'$'}ref": "#/${'$'}defs/D0"}}""",
            defs = defs,
        )

        assertNoDanglingRef(result)
        // 耗尽后明确退化为 {}，输出仍是有限大小
        assertEquals(setOf("trigger"), result.keys)
        org.junit.Assert.assertTrue(result.toString().length < 100_000)
    }

    @Test
    fun `percent decoding preserves plus supplementary unicode and rejects malformed escapes`() {
        val result = convert(
            properties = """{
                "plus": {"${'$'}ref":"#/${'$'}defs/a+b"},
                "emoji": {"${'$'}ref":"#/${'$'}defs/😀%20x"},
                "bad": {"${'$'}ref":"#/${'$'}defs/a%ZZ"}
            }""",
            defs = """{"a+b":{"type":"integer"},"😀 x":{"type":"string"},"a%ZZ":{"type":"number"}}""",
        )
        assertEquals(json("""{"plus":{"type":"integer"},"emoji":{"type":"string"},"bad":{}}"""), result)
    }

    @Test
    fun `array json pointer index grammar is enforced`() {
        val result = convert(
            properties = """{
                "source":{"prefixItems":[{"type":"string"},{"type":"integer"}]},
                "ok":{"${'$'}ref":"#/properties/source/prefixItems/1"},
                "negative":{"${'$'}ref":"#/properties/source/prefixItems/-1"},
                "signed":{"${'$'}ref":"#/properties/source/prefixItems/+1"},
                "padded":{"${'$'}ref":"#/properties/source/prefixItems/01"}
            }""",
        )
        assertEquals(json("""{"type":"integer"}"""), result["ok"])
        for (name in listOf("negative", "signed", "padded")) assertEquals(json("{}"), result[name])
    }

    @Test
    fun `false reference cannot be weakened by sibling not`() {
        val result = convert(
            properties = """{"trigger":{"${'$'}ref":"#/${'$'}defs/No","not":{"type":"string"}}}""",
            defs = """{"No":false}""",
        )
        assertEquals(json("""{"trigger":{"allOf":[false,{"not":{"type":"string"}}]}}"""), result)
    }

    @Test
    fun `property literally named dollar ref still expands`() {
        // properties 下合法的属性名可以叫 "$ref"，其值本身是 Schema，照常处理
        val result = convert(
            properties = """
                {
                  "${'$'}ref": {"type": "string"},
                  "trigger": {"type": "object", "properties": {"${'$'}ref": {"${'$'}ref": "#/${'$'}defs/S"}}}
                }
            """,
            defs = """{"S": {"type": "integer"}}""",
        )

        assertEquals(
            json(
                """
                {
                  "${'$'}ref": {"type": "string"},
                  "trigger": {"type": "object", "properties": {"${'$'}ref": {"type": "integer"}}}
                }
                """
            ),
            result,
        )
    }

    @Test
    fun `non standard root type no longer breaks decoding and converts as an object`() {
        // 官方 0.15.0 遇到根 type 为数组时整个 tools/list 解析失败（上游 c907608）
        val schema = decodeSchema(
            """{"type":["object","null"],"properties":{"q":{"type":"string"}},"required":["q"]}"""
        ).toSchema() as InputSchema.Obj
        assertEquals(json("""{"q":{"type":"string"}}"""), schema.properties)
        assertEquals(listOf("q"), schema.required)
    }

    @Test
    fun `missing root type is treated as an object`() {
        val schema = decodeSchema("""{"properties":{"q":{"type":"string"}}}""").toSchema() as InputSchema.Obj
        assertEquals(json("""{"q":{"type":"string"}}"""), schema.properties)
    }

    @Test
    fun `tools list with a non standard schema decodes every tool`() {
        val result = Json { ignoreUnknownKeys = true }.decodeFromString<io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult>(
            """{"tools":[
                {"name":"ok","inputSchema":{"type":"object","properties":{}}},
                {"name":"odd","inputSchema":{"type":["object","null"],"properties":{"a":{"type":"integer"}}}}
            ]}"""
        )
        assertEquals(listOf("ok", "odd"), result.tools.map { it.name })
    }
}
