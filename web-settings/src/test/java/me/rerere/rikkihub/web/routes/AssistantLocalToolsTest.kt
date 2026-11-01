package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.tools.local.LocalToolOption
import kotlinx.serialization.SerializationException
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PUT /assistants/{id} 的 localTools 字段级契约。
 *
 * 路由先 `call.receive<JsonObject>()` 取原始 body，`localTools` 键只在
 * 显式出现时才进入 merge——因为 Kotlin 侧 `Assistant.localTools` 默认值是
 * 空列表（2.8.0 前是 `[TimeInfo]`），直接 receive<Assistant> 会把“旧客户端没发这个键”误当作
 * “客户端要把工具重置回默认”，从而覆盖掉已清空的配置。
 */
class AssistantLocalToolsTest {

    @Test
    fun `default assistant serializes localTools with type discriminators`() {
        val raw = JsonInstant.encodeToJsonElement(Assistant()).jsonObject
        val tools = raw["localTools"] as? kotlinx.serialization.json.JsonArray
        // encodeDefaults = true：键必须存在；2.8.0 起默认不开启任何本地工具（含 time_info）
        assertEquals(emptyList<JsonPrimitive>(), tools?.map { it.jsonObject["type"] })
    }

    @Test
    fun `decode without localTools silently injects the default — hence raw presence check`() {
        // 佐证：解码路径把缺键变成默认空列表，所以 PUT 必须按原始键存在性处理，
        // 不能信 incoming.localTools。
        val decoded = JsonInstant.decodeFromJsonElement<Assistant>(
            buildJsonObject { put("name", "x") }
        )
        assertEquals(emptyList<LocalToolOption>(), decoded.localTools)
        // 而 presence 解析对同一份 body 返回 null → merge 保留 existing
        assertNull(parseLocalToolsField(buildJsonObject { put("name", "x") }))
    }

    @Test
    fun `empty array clears all local tools`() {
        val parsed = parseLocalToolsField(
            buildJsonObject { put("localTools", buildJsonArray {}) }
        )
        assertEquals(emptyList<LocalToolOption>(), parsed)
    }

    @Test
    fun `unknown tool type is rejected`() {
        val error = runCatching {
            parseLocalToolsField(
                buildJsonObject {
                    put("localTools", buildJsonArray {
                        add(buildJsonObject { put("type", "web_browser") })
                    })
                }
            )
        }.exceptionOrNull()
        assertTrue(error is BadRequestException)
    }

    @Test
    fun `non string type field is rejected`() {
        val error = runCatching {
            parseLocalToolsField(
                buildJsonObject {
                    put("localTools", buildJsonArray {
                        add(buildJsonObject { put("type", 1) })
                    })
                }
            )
        }.exceptionOrNull()
        assertTrue(error is BadRequestException)
    }

    @Test
    fun `non object entry is rejected`() {
        val error = runCatching {
            parseLocalToolsField(
                buildJsonObject {
                    put("localTools", buildJsonArray { add(JsonPrimitive("tts")) })
                }
            )
        }.exceptionOrNull()
        assertTrue(error is BadRequestException)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `every sealed subclass is reachable from wire names`() {
        val wireNames = listOf(
            "javascript_engine", "time_info", "clipboard", "tts",
            "ask_user", "screen_time", "calendar", "chart_display",
        )
        val parsed = parseLocalToolsField(
            buildJsonObject {
                put("localTools", buildJsonArray {
                    wireNames.forEach { add(buildJsonObject { put("type", it) }) }
                })
            }
        )!!
        assertEquals(8, parsed.size)
        // 覆盖全部 8 个 sealed subclass，防漏映射
        assertEquals(
            LocalToolOption::class.sealedSubclasses.map { it.objectInstance }.toSet(),
            parsed.toSet(),
        )
    }

    // ---- parseAssistantUpdate：PUT 路由实际调用的生产函数（非复制逻辑） ----

    @Test
    fun `serialization exceptions are IllegalArgumentException — reviewer claim is a false positive`() {
        // 复审称 SerializationException 属 IllegalStateException、catch 不到 → 500。
        // 实测（kotlinx-serialization 1.11.0 javap + 反射）：继承自 IllegalArgumentException，
        // JsonDecodingException / MissingFieldException 均在其下，现有 catch 已覆盖，无需扩大。
        assertTrue(IllegalArgumentException::class.java.isAssignableFrom(SerializationException::class.java))
        assertTrue(
            IllegalArgumentException::class.java.isAssignableFrom(
                kotlinx.serialization.json.JsonException::class.java,
            ),
        )
    }

    @Test
    fun `parseAssistantUpdate maps malformed ordinary field to 400`() {
        // 复审点名 payload：temperature 是 Float?，传字符串 → JsonDecodingException → 400
        val error = runCatching {
            parseAssistantUpdate(buildJsonObject { put("temperature", "hot") })
        }.exceptionOrNull()
        assertTrue(error is BadRequestException)
    }

    @Test
    fun `parseAssistantUpdate maps malformed localTools to 400 through the same path`() {
        val error = runCatching {
            parseAssistantUpdate(
                buildJsonObject {
                    put("localTools", buildJsonArray { add(buildJsonObject { put("type", "bogus") }) })
                },
            )
        }.exceptionOrNull()
        assertTrue(error is BadRequestException)
    }

    @Test
    fun `parseAssistantUpdate keeps absent localTools as null for the merge`() {
        val (incoming, localTools) = parseAssistantUpdate(
            buildJsonObject { put("name", "kept") },
        )
        assertEquals("kept", incoming.name)
        // 缺键：null → mergeAssistant 保留 existing；解码侧已被默认值填充
        assertNull(localTools)
        assertEquals(emptyList<LocalToolOption>(), incoming.localTools)
    }

    @Test
    fun `parseAssistantUpdate returns parsed tools when the key is present`() {
        val (incoming, localTools) = parseAssistantUpdate(
            buildJsonObject {
                put("localTools", buildJsonArray { add(buildJsonObject { put("type", "calendar") }) })
            },
        )
        assertEquals(listOf(LocalToolOption.Calendar), localTools)
        // 同一份 body 解码出的 incoming.localTools 与 presence 解析一致
        assertEquals(localTools, incoming.localTools)
    }

    @Test
    fun `parseAssistantUpdate tolerates unrelated unknown keys`() {
        // ignoreUnknownKeys：未知字段不炸，localTools 照常解析
        val (_, localTools) = parseAssistantUpdate(
            buildJsonObject {
                put("unknownFutureField", buildJsonObject { put("x", 1) })
                put("localTools", buildJsonArray {})
            },
        )
        assertEquals(emptyList<LocalToolOption>(), localTools)
    }
}
