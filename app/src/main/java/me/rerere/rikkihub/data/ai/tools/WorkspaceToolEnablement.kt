package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.utils.JsonInstant

/**
 * 工作区逐工具启用开关的纯解码/合并模型.
 *
 * 存储: `workspaces.tool_enablement` 列, JSON 对象, 键是稳定内建 ID.
 * 语义: 默认 `"{}"`, 缺键 = 启用; 显式 `false` = 禁用; `true` = 显式启用.
 * 合法存储必须是非空 JSON 对象 (迁移默认即 "{}"); 任何 null/blank/
 * 非 JSON/非对象/未知键/非 boolean 值都是损坏配置, 一律视为不可用:
 * [isToolEnabled] 返回 false (fail-closed), 绝不静默全开.
 */
object WorkspaceToolEnablement {

    /**
     * 可配置的稳定 ID, 按模式区分 (两个模式共 7 个):
     * - 普通模式: workspace_read_file / workspace_write_file / workspace_edit_file /
     *   workspace_shell / subagent
     * - 极简模式: bash / str_replace_editor / subagent
     */
    val normalIds: List<String> = listOf(
        BuiltinToolIds.WORKSPACE_READ_FILE,
        BuiltinToolIds.WORKSPACE_WRITE_FILE,
        BuiltinToolIds.WORKSPACE_EDIT_FILE,
        BuiltinToolIds.WORKSPACE_SHELL,
        BuiltinToolIds.SUBAGENT,
    )

    val minimalIds: List<String> = listOf(
        BuiltinToolIds.BASH,
        BuiltinToolIds.STR_REPLACE_EDITOR,
        BuiltinToolIds.SUBAGENT,
    )

    /** 两个模式的并集, 共 7 个稳定 ID; 写入校验用 */
    val configurableIds: Set<String> = (normalIds + minimalIds).toSet()

    /** 按工作区模式给出可配置 ID 列表 */
    fun idsFor(harnessMinimal: Boolean): List<String> =
        if (harnessMinimal) minimalIds else normalIds

    /**
     * 严格解码存储 JSON. 存储约定是非空 JSON 对象 (迁移默认 "{}"),
     * null/blank/非 JSON/非对象/未知键/非 boolean 值一律抛
     * [IllegalArgumentException], 不容忍空串静默全开.
     * 返回的 map 只含可配置键, 键序按存储顺序.
     */
    fun parse(json: String?): Map<String, Boolean> {
        require(!json.isNullOrBlank()) { "tool_enablement must be a non-empty JSON object" }
        val element = try {
            JsonInstant.parseToJsonElement(json)
        } catch (error: Throwable) {
            throw IllegalArgumentException("tool_enablement is not valid JSON")
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("tool_enablement must be a JSON object")
        val result = LinkedHashMap<String, Boolean>(obj.size)
        for ((key, value) in obj) {
            require(key in configurableIds) { "Unknown workspace tool id: $key" }
            // 严格 boolean 字面量: 不依赖 booleanOrNull (kotlinx 1.x 对 "1" 等
            // 非 boolean 字面量可能返回 false 而非 null), 也不接受字符串 "true"
            val prim = value as? JsonPrimitive
                ?: throw IllegalArgumentException("tool_enablement.$key must be a boolean")
            val enabled = when {
                prim.isString -> throw IllegalArgumentException("tool_enablement.$key must be a boolean")
                prim.content == "true" -> true
                prim.content == "false" -> false
                else -> throw IllegalArgumentException("tool_enablement.$key must be a boolean")
            }
            result[key] = enabled
        }
        return result
    }

    /** 宽松解码: 损坏返回 null; 供读取路径判定「配置是否健康」 */
    fun tryParse(json: String?): Map<String, Boolean>? = runCatching { parse(json) }.getOrNull()

    /** 合并 patch 生成新 JSON; [patch] 的键必须是可配置 ID */
    fun mergedJson(existingJson: String, patch: Map<String, Boolean>): String {
        patch.keys.forEach { id ->
            require(id in configurableIds) { "Unknown workspace tool id: $id" }
        }
        return JsonInstant.encodeToString(parse(existingJson) + patch)
    }

    /** 全量替换 JSON (校验所有键); 供损坏配置修复或重置使用 */
    fun validatedJson(overrides: Map<String, Boolean>): String {
        overrides.keys.forEach { id ->
            require(id in configurableIds) { "Unknown workspace tool id: $id" }
        }
        return JsonInstant.encodeToString(overrides)
    }
}

/** 该工具的启用状态: 缺键启用, 显式 false 禁用; [toolId] 应为可配置稳定 ID */
fun Map<String, Boolean>.toolEnabled(toolId: String): Boolean = this[toolId] ?: true

/**
 * 工作区实体维度的判定入口.
 * - [toolId] 不是可配置稳定 ID → false (不认识的工具不会由此放行);
 * - 配置损坏 → false (fail-closed, 调用方据此报错/拒绝);
 * - 缺键 → true, 显式 false → false.
 * T1 执行守卫应使用此入口, 不要自行 runCatching 放行.
 */
fun WorkspaceEntity.isToolEnabled(toolId: String): Boolean {
    if (toolId !in WorkspaceToolEnablement.configurableIds) return false
    return WorkspaceToolEnablement.tryParse(toolEnablement)?.toolEnabled(toolId) ?: false
}

/** 严格解码实体上的配置; 损坏抛 [IllegalArgumentException] */
fun WorkspaceEntity.toolEnablementOverrides(): Map<String, Boolean> =
    WorkspaceToolEnablement.parse(toolEnablement)
