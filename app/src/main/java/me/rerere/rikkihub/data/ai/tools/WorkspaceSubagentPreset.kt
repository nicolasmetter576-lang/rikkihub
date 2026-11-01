package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.utils.JsonInstant
import java.security.MessageDigest
import kotlin.uuid.Uuid

@Serializable
enum class SubagentPresetModelSelection {
    @SerialName("workspace_default") WORKSPACE_DEFAULT,
    @SerialName("parent") PARENT,
    @SerialName("fixed") FIXED,
}

@Serializable
data class SubagentPresetGenerationConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
)

/** A worker definition or frozen workspace projection, never a chat Assistant/credential. */
@Serializable
data class WorkspaceSubagentPreset(
    val id: String = Uuid.random().toString(),
    val key: String = "",
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val enabled: Boolean = true,
    val modelSelection: SubagentPresetModelSelection = SubagentPresetModelSelection.WORKSPACE_DEFAULT,
    val modelId: String? = null,
    val generationConfig: SubagentPresetGenerationConfig = SubagentPresetGenerationConfig(),
    val acceptContext: Boolean = true,
    val maxSteps: Int = SUBAGENT_DEFAULT_STEPS,
    val sortOrder: Int = 0,
    /** Optimistic edit version: every persisted modification increments this value. */
    val version: Long = 1,
    /** Incremented only when execution semantics change; display-only edits do not revoke calls. */
    val executionRevision: Long = 1,
    /** Server-owned provenance of a shared library projection; null means workspace-local. */
    val libraryPresetId: String? = null,
)

class SubagentPresetConflictException(message: String = "Subagent preset was changed by another editor") :
    IllegalStateException(message)

/** Strict, bounded storage codec. Corruption must not be silently replaced with an empty catalog. */
object WorkspaceSubagentPresets {
    const val MAX_PRESETS = 16
    const val MAX_NAME_CHARS = 48
    const val MAX_DESCRIPTION_CHARS = 240
    const val MAX_KEY_CHARS = 32
    const val DEFAULT_AGENT = "default"
    private val keyPattern = Regex("[a-z][a-z0-9_-]{0,31}")
    private val storageFields = setOf("id", "key", "name", "description", "systemPrompt", "enabled",
        "modelSelection", "modelId", "generationConfig", "acceptContext", "maxSteps", "sortOrder",
        "version", "executionRevision", "libraryPresetId")
    private val generationFields = setOf("temperature", "topP", "maxTokens", "reasoningLevel")

    fun normalize(preset: WorkspaceSubagentPreset): WorkspaceSubagentPreset = preset.copy(
        key = preset.key.trim(),
        name = preset.name.trim(),
        description = preset.description.trim(),
        modelId = preset.modelId?.trim()?.takeIf(String::isNotEmpty),
    )

    fun validate(preset: WorkspaceSubagentPreset) {
        require(runCatching { Uuid.parse(preset.id) }.isSuccess) { "Invalid subagent preset id" }
        preset.libraryPresetId?.let {
            require(runCatching { Uuid.parse(it) }.isSuccess) { "Invalid library preset reference" }
        }
        require(preset.key.matches(keyPattern) && preset.key != DEFAULT_AGENT) {
            "Preset key must be 1-$MAX_KEY_CHARS lowercase ASCII letters, digits, '_' or '-', start with a letter, and not be 'default'"
        }
        require(preset.name.isNotBlank() && preset.name.length <= MAX_NAME_CHARS) {
            "Preset name must contain 1-$MAX_NAME_CHARS characters"
        }
        require(preset.description.isNotBlank() && preset.description.length <= MAX_DESCRIPTION_CHARS) {
            "Preset description must contain 1-$MAX_DESCRIPTION_CHARS characters"
        }
        require(preset.systemPrompt.isNotBlank() && preset.systemPrompt.length <= SUBAGENT_MAX_FIELD_CHARS) {
            "Preset system prompt must contain 1-$SUBAGENT_MAX_FIELD_CHARS characters"
        }
        require(preset.version in 1 until Long.MAX_VALUE && preset.executionRevision in 1 until Long.MAX_VALUE) {
            "Invalid subagent preset version"
        }
        require(listOf(preset.name, preset.description, preset.systemPrompt).none { '\u0000' in it }) {
            "Preset text must not contain NUL characters"
        }
        require(preset.maxSteps in 1..SUBAGENT_MAX_STEPS) { "Preset maxSteps must be between 1 and $SUBAGENT_MAX_STEPS" }
        require(preset.sortOrder >= 0) { "Preset sortOrder must not be negative" }
        when (preset.modelSelection) {
            SubagentPresetModelSelection.FIXED -> require(
                preset.modelId != null && runCatching { Uuid.parse(preset.modelId) }.isSuccess
            ) { "Fixed preset model must be a model UUID" }
            else -> require(preset.modelId == null) { "Only a fixed-model preset may set modelId" }
        }
        preset.generationConfig.temperature?.let {
            require(it.isFinite() && it in 0f..2f) { "Preset temperature must be between 0 and 2" }
        }
        preset.generationConfig.topP?.let {
            require(it.isFinite() && it > 0f && it <= 1f) { "Preset topP must be greater than 0 and at most 1" }
        }
        preset.generationConfig.maxTokens?.let {
            require(it > 0) { "Preset maxTokens must be a positive integer" }
        }
    }

    fun validateAll(presets: List<WorkspaceSubagentPreset>, maximum: Int = MAX_PRESETS) {
        require(presets.size <= maximum) { "At most $maximum subagent presets are allowed" }
        presets.forEach(::validate)
        require(presets.map { it.id }.distinct().size == presets.size) { "Duplicate subagent preset id" }
        require(presets.map { it.key }.distinct().size == presets.size) { "Duplicate subagent preset key" }
        val links = presets.mapNotNull { it.libraryPresetId }
        require(links.distinct().size == links.size) { "Duplicate shared preset association" }
    }

    fun parse(json: String, maximum: Int = MAX_PRESETS): List<WorkspaceSubagentPreset> {
        val presets = try {
            val array = JsonInstant.parseToJsonElement(json) as? JsonArray
                ?: throw IllegalArgumentException("subagent_presets must be an array")
            require(array.size <= maximum) { "Too many subagent presets" }
            array.forEach { element ->
                val obj = element as? JsonObject ?: throw IllegalArgumentException("Preset must be an object")
                // Future permission fields must not be silently ignored by an older runtime.
                require(obj.keys.all { it in storageFields }) { "Unknown stored preset field" }
                // A constructor-generated UUID must never repair a missing stored identity: otherwise
                // reading the same corrupt row twice would produce different signed implementations.
                listOf("id", "key", "name", "description", "systemPrompt").forEach { field ->
                    require((obj[field] as? JsonPrimitive)?.isString == true) { "Preset $field must be a string" }
                }
                obj["libraryPresetId"]?.takeUnless { it == JsonNull }?.let { raw ->
                    require((raw as? JsonPrimitive)?.isString == true) { "libraryPresetId must be a string or null" }
                }
                listOf("enabled", "acceptContext").forEach { field ->
                    obj[field]?.let { raw ->
                        val primitive = raw as? JsonPrimitive
                        require(primitive != null && !primitive.isString && primitive.content in setOf("true", "false")) {
                            "Preset $field must be a boolean"
                        }
                    }
                }
                listOf("version", "executionRevision", "maxSteps", "sortOrder").forEach { field ->
                    obj[field]?.let { raw ->
                        val primitive = raw as? JsonPrimitive
                        require(primitive != null && !primitive.isString && primitive.content.toLongOrNull() != null) {
                            "Preset $field must be an integer"
                        }
                    }
                }
                obj["generationConfig"]?.let { raw ->
                    val config = raw as? JsonObject
                        ?: throw IllegalArgumentException("Preset generationConfig must be an object")
                    require(config.keys.all { it in generationFields }) { "Unknown stored generation parameter" }
                    listOf("temperature", "topP", "maxTokens").forEach { field ->
                        config[field]?.takeUnless { it == JsonNull }?.let { value ->
                            val primitive = value as? JsonPrimitive
                            require(primitive != null && !primitive.isString && primitive.content.toFloatOrNull() != null) {
                                "Preset generationConfig.$field must be a number or null"
                            }
                            if (field == "maxTokens") require(primitive.content.toIntOrNull() != null) {
                                "Preset maxTokens must be an integer or null"
                            }
                        }
                    }
                }
            }
            JsonInstant.decodeFromString<List<WorkspaceSubagentPreset>>(json)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("subagent_presets must be a valid preset array")
        }
        validateAll(presets, maximum)
        return presets
    }

    fun tryParse(json: String): List<WorkspaceSubagentPreset>? = runCatching { parse(json) }.getOrNull()

    fun encode(presets: List<WorkspaceSubagentPreset>, maximum: Int = MAX_PRESETS): String {
        validateAll(presets, maximum)
        return JsonInstant.encodeToString(presets)
    }

    fun sameExecution(a: WorkspaceSubagentPreset, b: WorkspaceSubagentPreset): Boolean =
        a.id == b.id && a.libraryPresetId == b.libraryPresetId && a.key == b.key &&
            a.systemPrompt == b.systemPrompt && a.enabled == b.enabled &&
            a.modelSelection == b.modelSelection && a.modelId == b.modelId &&
            a.generationConfig == b.generationConfig && a.acceptContext == b.acceptContext && a.maxSteps == b.maxSteps

    /** Used in the already-signed implementation identity; never sent as a model argument. */
    fun executionFingerprint(presets: List<WorkspaceSubagentPreset>?): String {
        if (presets == null) return "invalid"
        val executionOnly = presets.sortedBy { it.id }.map {
            it.copy(name = "", description = "", sortOrder = 0, version = 1)
        }
        return sha256(JsonInstant.encodeToString(executionOnly))
    }

    /** Empty legacy workspaces keep their original implementation identity. */
    fun implementationId(json: String, workspaceModelId: String?, workspaceAllowContext: Boolean): String {
        val presets = tryParse(json)
        if (presets != null && presets.isEmpty()) return BuiltinToolIds.SUBAGENT
        return "subagent.presets.v1:" + sha256(
            executionFingerprint(presets) + "\n" + workspaceModelId.orEmpty() + "\n" + workspaceAllowContext
        )
    }

    /** Disabling/removing a role or reducing context access revokes the subagent resource family. */
    fun requiresRevocation(before: List<WorkspaceSubagentPreset>?, after: List<WorkspaceSubagentPreset>): Boolean {
        if (before == null) return true
        val byId = after.associateBy { it.id }
        return before.any { old ->
            val next = byId[old.id]
            old.enabled && (next == null || !next.enabled || (old.acceptContext && !next.acceptContext))
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Fixed references never fall back to another model, including when their provider is disabled. */
fun resolveSubagentPresetModel(
    settings: Settings,
    parentModel: Model,
    workspaceModelId: String?,
    preset: WorkspaceSubagentPreset,
): Model? {
    val reference = when (preset.modelSelection) {
        SubagentPresetModelSelection.WORKSPACE_DEFAULT -> workspaceModelId
        SubagentPresetModelSelection.PARENT -> null
        SubagentPresetModelSelection.FIXED -> preset.modelId
    }
    val model = resolveSubagentModel(settings, parentModel, reference) ?: return null
    return model.takeIf {
        settings.providers.any { provider -> provider.enabled && provider.models.any { listed -> listed.id == it.id } } &&
            it.findProvider(settings.providers)?.enabled == true
    }
}

/** Built-in role templates are copied explicitly; migrations never auto-enable them. */
fun workspaceSubagentPresetTemplates(): List<WorkspaceSubagentPreset> = listOf(
    WorkspaceSubagentPreset(
        key = "explorer", name = "Explorer",
        description = "Locate implementations and trace call chains; return relevant files and concrete evidence.",
        systemPrompt = """
            You are a repository exploration subagent. Investigate only the delegated scope.
            Read the relevant implementations and callers before drawing conclusions. Do not modify files.
            Return a concise architecture summary, exact file locations, evidence, and remaining uncertainties.
            Do not claim that builds or tests passed unless you actually ran them.
        """.trimIndent(),
    ),
    WorkspaceSubagentPreset(
        key = "developer", name = "Developer",
        description = "Implement a clearly scoped change and report the modifications and verification results.",
        systemPrompt = """
            You are an implementation subagent. Implement the delegated task while preserving unrelated changes.
            Inspect existing conventions first, keep changes focused, and add appropriate regression tests.
            Never reset, stash, or discard existing work. Do not expand the task without justification.
            Report changed files, tests actually run, failures or environment blockers, and remaining risks.
        """.trimIndent(),
    ),
    WorkspaceSubagentPreset(
        key = "reviewer", name = "Reviewer",
        description = "Review correctness, concurrency, and security; provide reproducible evidence and fixes.",
        systemPrompt = """
            You are a code review subagent. Review only the delegated scope and do not modify files.
            Inspect implementations and callers. Focus on correctness, state consistency, concurrency,
            authorization boundaries, and compatibility. Do not present speculation as a confirmed defect.
            For each finding give severity, file location, triggering conditions, impact, and a fix suggestion.
            Finish with confirmed findings, unverified risks, and the reviewed scope. If no reliable issue
            was found, say so rather than inventing findings. Never fabricate test results.
        """.trimIndent(),
    ),
    WorkspaceSubagentPreset(
        key = "tester", name = "Tester",
        description = "Design and add regression tests; distinguish verified results from environment blockers.",
        systemPrompt = """
            You are a regression testing subagent. Inspect the delegated behavior and existing test conventions.
            Add focused tests for the expected behavior, edge cases, failure paths, and compatibility.
            Prefer deterministic offline tests. Do not call real external APIs unless explicitly authorized.
            Report files changed, exact test commands and outcomes, untested scenarios, and environment blockers.
        """.trimIndent(),
    ),
)
