package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.rikkihub.data.ai.tools.SUBAGENT_MAX_FIELD_CHARS
import me.rerere.rikkihub.data.ai.tools.SUBAGENT_MAX_STEPS
import me.rerere.rikkihub.data.ai.tools.subagentPresetDirectory
import me.rerere.rikkihub.data.ai.tools.SubagentPresetGenerationConfig
import me.rerere.rikkihub.data.ai.tools.SubagentPresetModelSelection
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import kotlin.uuid.Uuid

/** Text numbers deliberately stay text until explicit save: blank and invalid drafts are not persisted. */
data class WorkspaceSubagentPresetDraft(
    val preset: WorkspaceSubagentPreset,
    val expectedVersion: Long? = null,
    /** null means the workspace whose detail page owns the editor; copies have an explicit target. */
    val workspaceId: String? = null,
    val temperature: String = preset.generationConfig.temperature?.toString().orEmpty(),
    val topP: String = preset.generationConfig.topP?.toString().orEmpty(),
    val maxTokens: String = preset.generationConfig.maxTokens?.toString().orEmpty(),
    val maxSteps: String = preset.maxSteps.toString(),
) {
    fun validationError(): WorkspaceSubagentPresetDraftError? {
        val normalized = WorkspaceSubagentPresets.normalize(preset)
        if (!normalized.key.matches(Regex("[a-z][a-z0-9_-]{0,31}")) || normalized.key == "default") {
            return WorkspaceSubagentPresetDraftError.KEY
        }
        if (normalized.name.isBlank() || normalized.name.length > WorkspaceSubagentPresets.MAX_NAME_CHARS) {
            return WorkspaceSubagentPresetDraftError.NAME
        }
        if (normalized.description.isBlank() || normalized.description.length > WorkspaceSubagentPresets.MAX_DESCRIPTION_CHARS) {
            return WorkspaceSubagentPresetDraftError.DESCRIPTION
        }
        if (normalized.systemPrompt.isBlank() || normalized.systemPrompt.length > SUBAGENT_MAX_FIELD_CHARS) {
            return WorkspaceSubagentPresetDraftError.PROMPT
        }
        if (normalized.modelSelection == SubagentPresetModelSelection.FIXED &&
            runCatching { Uuid.parse(normalized.modelId.orEmpty()) }.isFailure
        ) return WorkspaceSubagentPresetDraftError.MODEL
        if (!workspaceSubagentPresetOptionalFloatValid(temperature, 0f, 2f)) {
            return WorkspaceSubagentPresetDraftError.TEMPERATURE
        }
        if (!workspaceSubagentPresetOptionalFloatValid(topP, 0f, 1f, exclusiveMin = true)) {
            return WorkspaceSubagentPresetDraftError.TOP_P
        }
        if (maxTokens.length > WORKSPACE_SUBAGENT_PRESET_NUMBER_CHARS ||
            (maxTokens.isNotBlank() && workspaceSubagentPresetPositiveInt(maxTokens) == null)
        ) {
            return WorkspaceSubagentPresetDraftError.MAX_TOKENS
        }
        if (workspaceSubagentPresetPositiveInt(maxSteps)?.let { it in 1..SUBAGENT_MAX_STEPS } != true) {
            return WorkspaceSubagentPresetDraftError.MAX_STEPS
        }
        return null
    }

    fun toPreset(): WorkspaceSubagentPreset {
        require(validationError() == null) { "Invalid subagent preset draft" }
        return WorkspaceSubagentPresets.normalize(preset).copy(
            modelId = preset.modelId?.trim()?.takeIf { preset.modelSelection == SubagentPresetModelSelection.FIXED },
            maxSteps = workspaceSubagentPresetPositiveInt(maxSteps)!!,
            generationConfig = SubagentPresetGenerationConfig(
                temperature = temperature.trim().takeIf { it.isNotEmpty() }?.toFloat(),
                topP = topP.trim().takeIf { it.isNotEmpty() }?.toFloat(),
                maxTokens = maxTokens.trim().takeIf { it.isNotEmpty() }?.toInt(),
                reasoningLevel = preset.generationConfig.reasoningLevel,
            ),
        ).also(WorkspaceSubagentPresets::validate)
    }
}

enum class WorkspaceSubagentPresetDraftError {
    KEY, NAME, DESCRIPTION, PROMPT, MODEL, TEMPERATURE, TOP_P, MAX_TOKENS, MAX_STEPS,
}

data class WorkspaceSubagentPresetEditorState(
    val draft: WorkspaceSubagentPresetDraft? = null,
    val conflict: Boolean = false,
)

const val WORKSPACE_SUBAGENT_PRESET_NUMBER_CHARS = 32

fun workspaceSubagentPresetBoundedText(value: String, maxChars: Int): String = value.take(maxChars)

fun workspaceSubagentPresetPositiveInt(value: String): Int? = value
    .takeIf { it.length <= WORKSPACE_SUBAGENT_PRESET_NUMBER_CHARS }?.trim()
    ?.takeIf { it.matches(Regex("[0-9]+")) }
    ?.toIntOrNull()?.takeIf { it > 0 }

fun workspaceSubagentPresetOptionalFloatValid(
    value: String,
    min: Float,
    max: Float,
    exclusiveMin: Boolean = false,
): Boolean {
    if (value.length > WORKSPACE_SUBAGENT_PRESET_NUMBER_CHARS) return false
    val text = value.trim()
    if (text.isEmpty()) return true
    val parsed = text.toFloatOrNull() ?: return false
    return parsed.isFinite() && parsed <= max && (if (exclusiveMin) parsed > min else parsed >= min)
}

/** Always generate a different immutable key for a clone, even when the source is in another workspace. */
fun workspaceSubagentPresetCopyKey(key: String, existingKeys: Set<String>): String {
    val base = key.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        .let { if (it.firstOrNull()?.let { first -> first in 'a'..'z' } == true) it else "preset_$it" }
    var index = 1
    while (true) {
        val suffix = if (index == 1) "_copy" else "_copy$index"
        val candidate = base.take(WorkspaceSubagentPresets.MAX_KEY_CHARS - suffix.length) + suffix
        if (candidate !in existingKeys && candidate != key && candidate != "default") return candidate
        index++
    }
}

fun workspaceSubagentPresetClone(
    preset: WorkspaceSubagentPreset,
    existingKeys: Set<String>,
): WorkspaceSubagentPreset = preset.copy(
    id = Uuid.random().toString(),
    key = workspaceSubagentPresetCopyKey(preset.key, existingKeys),
    version = 1,
    executionRevision = 1,
    sortOrder = 0,
    libraryPresetId = null,
)

/** External Room changes can mark a draft stale, but never replace any of its fields. */
fun workspaceSubagentPresetDraftConflict(
    draft: WorkspaceSubagentPresetDraft,
    saved: List<WorkspaceSubagentPreset>?,
): Boolean = saved == null || (draft.expectedVersion != null &&
    saved.firstOrNull { it.id == draft.preset.id }?.version != draft.expectedVersion)

fun workspaceSubagentPresetCataloguePreview(presets: List<WorkspaceSubagentPreset>): String =
    subagentPresetDirectory(presets.filter { it.enabled }.sortedWith(compareBy({ it.sortOrder }, { it.key })))
