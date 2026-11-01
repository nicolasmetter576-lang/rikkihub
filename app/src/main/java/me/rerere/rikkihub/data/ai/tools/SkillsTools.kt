package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import me.rerere.rikkihub.data.files.SkillMetadata
import me.rerere.rikkihub.data.files.SkillPaths

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    exposedTargets: Set<String> = emptySet(),
): List<Tool> {
    val available = allSkills.sortedBy { it.isBuiltin }.distinctBy { it.name }
        .filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = BuiltinToolIds.USE_SKILL,
            description = BuiltinToolCatalog.require(BuiltinToolIds.USE_SKILL).defaultDescription,
            systemPrompt = { _, _ ->
                interpolateToolText(
                    text = BuiltinToolCatalog.require(BuiltinToolIds.USE_SKILL).defaultSystemPrompt.orEmpty(),
                    placeholders = mapOf(
                        "skills" to formatEffectiveSkillsXml(available, exposedTargets)
                    ),
                )
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val skill = available.firstOrNull { skill -> skill.name == name }
                    ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                require(skill.skillDir.canonicalFile == skill.skillDir.absoluteFile.normalize()) {
                    "Skill directory changed or is unsafe"
                }
                val content = if (path.isNullOrBlank()) {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, "SKILL.md")
                        ?: error("Unsafe SKILL.md path")
                    require(target.isFile) { "Skill '$name' not found" }
                    SkillFrontmatterParser.extractBody(target.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.isFile) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}

/** Guest paths come from the selected physical directory, never an Android host path. */
fun formatEffectiveSkillsXml(skills: List<SkillMetadata>, exposedTargets: Set<String>): String {
    val effective = skills.sortedBy { it.isBuiltin }.distinctBy { it.name }
    return buildString {
        append(formatAvailableSkillsXml(effective.map { it.name to it.description }))
        val located = effective.mapNotNull { skill ->
            val root = if (skill.isBuiltin) "/builtin_skills" else "/skills"
            if (root !in exposedTargets) return@mapNotNull null
            "<skill_location><name>${escapeSkillXmlText(skill.name)}</name>" +
                "<path>${escapeSkillXmlText("$root/${skill.skillDir.name}/SKILL.md")}</path>" +
                "<origin>${if (skill.isBuiltin) "builtin" else "user"}</origin></skill_location>"
        }
        if (located.isNotEmpty()) append("\n<skill_locations>\n${located.joinToString("\n")}\n</skill_locations>")
        val guidance = me.rerere.rikkihub.data.workspace.builtinSkillsMountGuidance(exposedTargets)
        if (guidance.isNotBlank()) append("\n$guidance")
    }
}
