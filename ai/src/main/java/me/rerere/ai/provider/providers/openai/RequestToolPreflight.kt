package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.stream.ToolStreamProtocolException

/**
 * Chat Completions 请求发送前的最终 wire 工具表预检。
 *
 * 检查对象是 `mergeCustomBody` 之后的最终请求体，而不是扫描 customBody 的
 * 字面键：customBody 对非对象值是整体替换，一旦覆盖 `tools`，`params.tools`
 * 就不再是实际发出的工具表。
 *
 * 校验规则：
 * - `params.tools` 自身注册名必须非空且唯一——同名注册在执行端会被
 *   `tools.find` 静默取第一个，属于配置歧义，发送前显式拒绝；
 * - `functions`/`function_call` legacy 键覆盖 `tools` 的语义不受支持，明确拒绝；
 * - 最终 `tools` 允许删减为子集或重排；每个条目必须是
 *   `{"type":"function","function":{...}}`，name 非空唯一、且来自
 *   `params.tools`；wire `function.parameters` 必须与请求构造时已经物化的
 *   原声明 JsonElement 结构相等（`parameters()` 可能动态生成，禁止二次执行
 *   来"重新"取值比对）。description 等非 schema 字段允许修改；
 * - `tool_choice` 的 `"auto"`/`"none"`/`"required"` 字符串按协议接受；
 *   命名形式 `{"type":"function","function":{"name":...}}` 必须引用最终
 *   wire 暴露的工具名；其余形态非法。
 *
 * 工具表为空的纯文本请求正常通过；失败一律在发 HTTP 前抛
 * [ToolStreamProtocolException]，消息只携带错误码与工具名，不包含密钥或
 * 工具参数正文。
 */
internal object RequestToolPreflight {

    /**
     * @return 最终 wire 上实际暴露的工具名集合（供 decoder 按真实工具表消歧，
     *         而不是直接信任 `params.tools`）。
     */
    fun finalWireToolNames(
        finalBody: JsonObject,
        declaredTools: List<Pair<String, JsonObject>>,
    ): Set<String> {
        val declaredByName = linkedMapOf<String, JsonObject>()
        declaredTools.forEach { (name, declaration) ->
            if (name.isBlank()) {
                fail("invalid_tool_registration", "registered tool name must not be blank")
            }
            if (declaredByName.put(name, declaration) != null) {
                fail("duplicate_tool_registration", "duplicate registered tool name '$name'")
            }
        }

        // legacy functions/function_call 覆盖 tools 的语义不受支持，显式拒绝
        if ("functions" in finalBody || "function_call" in finalBody) {
            fail(
                "unsupported_legacy_functions",
                "custom body keys 'functions'/'function_call' are not supported; use 'tools'/'tool_choice'",
            )
        }

        val wireNames = when (val tools = finalBody["tools"]) {
            null, JsonNull -> emptySet()
            is JsonArray -> {
                val names = LinkedHashSet<String>(tools.size)
                tools.forEach { entry ->
                    val obj = entry as? JsonObject
                        ?: fail("invalid_tools_override", "tools entries must be objects")
                    val type = obj.stringFieldOrNull("type")
                    if (type != null && type != "function") {
                        fail("invalid_tools_override", "unsupported tool type '$type'")
                    }
                    val fn = obj["function"] as? JsonObject
                        ?: fail("invalid_tools_override", "tool entry is missing a function object")
                    val name = fn.stringFieldOrNull("name")
                    if (name.isNullOrBlank()) {
                        fail("invalid_tools_override", "tool entry is missing a non-empty function.name")
                    }
                    if (!names.add(name)) {
                        fail("invalid_tools_override", "duplicate tool name '$name' in final wire tools")
                    }
                    val declared = declaredByName[name]
                        ?: fail("unknown_tool", "tool '$name' is not registered for this request")
                    // schema 必须与原声明逐字节结构相等：customBody 只允许删减/重排
                    // 工具条目，不允许改写声明（含 parameters 任意嵌套字段）。
                    if (fn["parameters"] != declared["parameters"]) {
                        fail("tool_schema_conflict", "tool '$name' schema differs from its declaration")
                    }
                }
                names
            }

            else -> fail("invalid_tools_override", "tools override must be an array")
        }

        validateToolChoice(finalBody["tool_choice"], wireNames)
        return wireNames
    }

    private fun validateToolChoice(choice: JsonElement?, wireNames: Set<String>) {
        when (choice) {
            null, JsonNull -> Unit
            is JsonPrimitive -> {
                val mode = choice.contentOrNull
                if (mode !in PROTOCOL_TOOL_CHOICE_MODES) {
                    fail("invalid_tool_choice", "unsupported tool_choice mode '$mode'")
                }
            }

            is JsonObject -> {
                if (choice.stringFieldOrNull("type") != "function") {
                    fail("invalid_tool_choice", "unsupported tool_choice object shape")
                }
                val fn = choice["function"] as? JsonObject
                    ?: fail("invalid_tool_choice", "tool_choice is missing a function object")
                val name = fn.stringFieldOrNull("name")
                if (name.isNullOrBlank()) {
                    fail("invalid_tool_choice", "tool_choice requires a non-empty function.name")
                }
                // 命名工具必须引用最终 wire 暴露名，而不是 params.tools：
                // customBody 删减子集后指向被裁掉的工具同样非法。
                if (name !in wireNames) {
                    fail("unknown_tool_choice", "tool_choice references unexposed tool '$name'")
                }
            }

            else -> fail("invalid_tool_choice", "tool_choice must be a string mode or an object")
        }
    }

    /** 非字符串字段（对象/数组）按缺失处理，交由外层规则拒绝，不抛裸 IAE。 */
    private fun JsonObject.stringFieldOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun fail(code: String, detail: String): Nothing =
        throw ToolStreamProtocolException(code, "chat completions tool preflight[$code]: $detail")

    private val PROTOCOL_TOOL_CHOICE_MODES = setOf("auto", "none", "required")
}
