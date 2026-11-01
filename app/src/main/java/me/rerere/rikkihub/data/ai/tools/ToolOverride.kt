package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.Serializable
import me.rerere.ai.core.Tool
import me.rerere.rikkihub.utils.toLocalString
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.security.MessageDigest

@Serializable
data class ToolOverride(
    val name: String? = null,
    val description: String? = null,
    val systemPrompt: String? = null,
) {
    fun isBlank(): Boolean =
        name.isNullOrEmpty() && description.isNullOrEmpty() && systemPrompt.isNullOrEmpty()
}

fun interpolateToolText(text: String, placeholders: Map<String, String>): String {
    if (text.isEmpty() || placeholders.isEmpty()) return text
    var result = text
    placeholders.forEach { (key, value) ->
        result = result.replace("{{$key}}", value)
    }
    return result
}

fun extraMountPromptLines(exposedTargets: Set<String>): String = buildString {
    append(me.rerere.rikkihub.data.workspace.deviceDirectoryPromptLines(exposedTargets))
    if ("/skills" in exposedTargets) {
        appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    } else {
        appendLine("- This workspace does not mount `/skills`. Use `use_skill` for installed skills.")
    }
    append(me.rerere.rikkihub.data.workspace.builtinSkillsMountGuidance(exposedTargets))
    if ("/upload" in exposedTargets) {
        appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
    } else {
        appendLine("- This workspace does not mount `/upload`. Chat attachments are provided in the conversation text, not as files under `/upload`.")
    }
    if ("/tool_outputs" in exposedTargets) {
        appendLine("- Truncated tool output may be saved at `/tool_outputs`. Use the shell to read those files if a tool result says the full output was saved there.")
    }
}

fun builtinToolPlaceholders(
    cwd: String? = null,
    skillsXml: String = "",
    workspaceName: String = "",
    extraMounts: String = "",
): Map<String, String> {
    val zone = ZoneId.systemDefault()
    val offset = OffsetDateTime.now().offset
    val cwdValue = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace").orEmpty()
    val cwdClause = if (cwdValue.isNotBlank()) " Defaults to '$cwdValue'." else ""
    val cwdLine = if (cwd.isNullOrBlank()) {
        ""
    } else {
        "- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.\n"
    }
    return mapOf(
        "today" to LocalDate.now().toLocalString(true),
        "timezone" to zone.id,
        "utc_offset" to offset.id,
        "cwd" to cwd.orEmpty(),
        "cwd_clause" to cwdClause,
        "cwd_line" to cwdLine,
        "skills" to skillsXml,
        "workspace_name" to workspaceName,
        "extra_mounts" to extraMounts,
    )
}

fun Tool.applyBuiltinOverride(
    overrides: Map<String, ToolOverride>,
    placeholders: Map<String, String> = emptyMap(),
): Tool {
    val spec = BuiltinToolCatalog[name] ?: return this
    val override = overrides[spec.id]
    val resolvedName = spec.resolvedName(overrides)
    val descriptionSource = override?.description?.takeIf { it.isNotBlank() } ?: spec.defaultDescription
    val originalSystemPrompt = systemPrompt
    val customPrompt = override?.systemPrompt
    return copy(
        name = resolvedName,
        description = interpolateToolText(descriptionSource, placeholders),
        systemPrompt = { model, messages ->
            val original = originalSystemPrompt(model, messages)
            val custom = customPrompt?.let { interpolateToolText(it, placeholders) }
            when {
                custom.isNullOrBlank() -> original
                spec.mergeSystemPrompt && original.isNotBlank() -> "$custom\n$original"
                else -> custom
            }
        },
    )
}

fun Iterable<Tool>.applyBuiltinOverrides(
    overrides: Map<String, ToolOverride>,
    placeholders: Map<String, String> = emptyMap(),
): List<Tool> = map { it.applyBuiltinOverride(overrides, placeholders) }

fun resolveBuiltinToolId(
    name: String,
    overrides: Map<String, ToolOverride> = emptyMap(),
): String {
    if (name in BuiltinToolCatalog.ids) return name
    overrides.entries.firstOrNull { it.value.name == name }?.let { return it.key }
    return name
}

/** Stable local fingerprint of request-build overrides, not request contents or provider wire. */
fun toolOverridesFingerprint(overrides: Map<String, ToolOverride>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        val size = bytes?.size ?: -1
        digest.update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte()))
        if (bytes != null) digest.update(bytes)
    }
    overrides.toSortedMap().forEach { (id, override) ->
        field(id); field(override.name); field(override.description); field(override.systemPrompt)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** 与 Agent Skills 规范的 description 上限一致 */
internal const val MAX_SKILL_DESCRIPTION_LENGTH = 1024

/** 转义注入技能提示 XML 的文本（导入技能的 name/description 不受信任） */
internal fun escapeSkillXmlText(value: String): String = buildString(value.length) {
    for (c in value) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}

fun formatAvailableSkillsXml(
    skills: List<Pair<String, String>>,
): String = buildString {
    skills.forEach { (name, description) ->
        appendLine("  <skill>")
        // 技能可能来自第三方导入，转义并限长，防止 name/description 闭合标签注入任意系统提示
        appendLine("    <name>${escapeSkillXmlText(name)}</name>")
        appendLine("    <description>${escapeSkillXmlText(description.take(MAX_SKILL_DESCRIPTION_LENGTH))}</description>")
        appendLine("  </skill>")
    }
}.trimEnd()
