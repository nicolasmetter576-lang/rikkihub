package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.repository.storageFileReferenceNeedles
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储清理引用查询的形态覆盖测试。
 *
 * 真实环境：message_node.messages 存的是 `JsonInstant.encodeToString(node.messages)`。
 * Room 不可用（无 SQLite JDBC/Robolectric），故用「真实序列化产物 + instr 语义
 * （String.contains）」做等价验证，并锚定回归：旧版带引号 needle 对转义嵌套漏匹配。
 */
class StorageFileReferenceNeedlesTest {

    private val fileUrl = "file:///data/user/0/me.rerere.rikkihub/files/upload/abc-123.png"
    private val relPath = "upload/abc-123.png"

    /** 等价于 message_node.messages 列内容。 */
    private fun persist(vararg parts: UIMessagePart): String =
        JsonInstant.encodeToString(listOf(UIMessage(role = MessageRole.USER, parts = parts.toList())))

    /** instr(messages, needle) > 0 的等价物。 */
    private fun instrHits(messagesJson: String, needle: String) = messagesJson.contains(needle)

    private fun anyNeedleHits(messagesJson: String) =
        storageFileReferenceNeedles(fileUrl, relPath).any { instrHits(messagesJson, it) }

    /** 旧版行为：只用带引号编码 URL 作为 needle。 */
    private fun legacyHits(messagesJson: String) =
        instrHits(messagesJson, JsonInstant.encodeToString(fileUrl))

    @Test
    fun `plain part url hits with and without quotes`() {
        val json = persist(UIMessagePart.Image(url = fileUrl))
        assertTrue(legacyHits(json)) // "\"url\":\"file:///...\"" 精确命中
        assertTrue(anyNeedleHits(json))
    }

    @Test
    fun `tool output nested url hits`() {
        val json = persist(
            UIMessagePart.Tool(
                toolCallId = "t1",
                toolName = "read_file",
                input = "{}",
                output = listOf(UIMessagePart.Image(url = fileUrl)),
            )
        )
        assertTrue(legacyHits(json))
        assertTrue(anyNeedleHits(json))
    }

    @Test
    fun `tool input json string escaped url hits bare needle`() {
        // input 是 JSON 字符串，序列化后 file:// 变成 \"file://...\"
        val inputJson = buildJsonObject { put("path", JsonPrimitive(fileUrl)) }.toString()
        val json = persist(
            UIMessagePart.Tool(toolCallId = "t1", toolName = "read_file", input = inputJson)
        )
        // 回归锚点：旧带引号 needle 命不中（`\"file://` 前有反斜杠）
        assertFalse(legacyHits(json))
        // 新裸 needle 命中
        assertTrue(anyNeedleHits(json))
    }

    @Test
    fun `server tool JsonElement input output hits`() {
        val input = buildJsonObject { put("path", JsonPrimitive(fileUrl)) }
        val output = buildJsonObject { put("result", JsonPrimitive("ok")) }
        val json = persist(
            UIMessagePart.ServerTool(
                toolCallId = "t1",
                toolName = "fs",
                input = input,
                output = output,
                status = ServerToolStatus.COMPLETED,
            )
        )
        // JsonElement 序列化形态：{"path":"file:///..."}（外层引号不接触 file://）
        assertTrue(anyNeedleHits(json))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `tool result content JsonElement hits`() {
        val content = buildJsonObject { put("uri", JsonPrimitive(fileUrl)) }
        val json = persist(
            UIMessagePart.ToolResult(toolCallId = "t1", toolName = "fs", content = content, arguments = JsonObject(emptyMap()))
        )
        assertTrue(anyNeedleHits(json))
    }

    @Test
    fun `relative path storage form hits`() {
        // 历史数据可能存裸 upload/<name>
        val json = persist(UIMessagePart.Image(url = relPath))
        assertFalse(legacyHits(json))
        assertTrue(anyNeedleHits(json))
    }

    @Test
    fun `needle rules do not overmatch foreign upload names`() {
        val json = persist(
            UIMessagePart.Image(url = "file:///data/user/0/pkg/files/upload/xyz-999.png"),
            UIMessagePart.Document(url = "file:///data/user/0/pkg/files/tool_outputs/abc-123.png", fileName = "t"),
        )
        assertFalse(anyNeedleHits(json))
    }

    @Test
    fun `foreign folder absolute path does not hit upload needle`() {
        // 名字相同但位于 skills/ —— upload needle 前缀 "upload/" 会命中…验证行为并记录
        val foreign = persist(
            UIMessagePart.Image(url = "file:///data/user/0/pkg/files/skills/abc-123.png")
        )
        // file:// needle 不命中；upload/abc-123.png needle 也不命中（路径是 skills/abc-123.png）
        assertFalse(anyNeedleHits(foreign))
    }

    @Test
    fun `needles are sanitized and non trivial`() {
        val needles = storageFileReferenceNeedles(fileUrl, relPath)
        assertTrue(needles.size >= 2)
        assertTrue(needles.all { it.length >= 6 })
        // 带引号形态保留（精确），裸形态在外
        assertTrue(needles.any { it.startsWith("\"") })
        assertTrue(needles.contains(fileUrl))
        assertTrue(needles.contains(relPath))
        // 空/短输入不产生误命中 needle
        assertTrue(storageFileReferenceNeedles("a", "b").isEmpty())
    }
}
