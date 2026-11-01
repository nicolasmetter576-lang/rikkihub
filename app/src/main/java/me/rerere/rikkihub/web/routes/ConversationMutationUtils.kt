package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.dto.ConversationMetadataPatch

/**
 * Parses and validates the merge-patch body.
 *
 * Unknown keys are rejected instead of silently dropped, matching the
 * settings allowlist contract: a misspelled field must not look like a
 * successful save. Setting a non-null customSystemPrompt additionally
 * requires [allowConversationSystemPrompt] of the owning assistant;
 * clearing it is always allowed so a disabled assistant cannot wedge a
 * conversation with a stale override.
 */
fun parseConversationMetadataPatch(
    body: JsonObject,
    allowConversationSystemPrompt: Boolean,
): ConversationMetadataPatch {
    val allowedKeys = setOf("title", "customSystemPrompt", "workspaceCwd", "workspaceCwdScoped")
    body.keys.firstOrNull { it !in allowedKeys }?.let { key ->
        throw BadRequestException("Unknown field: $key")
    }

    var patch = ConversationMetadataPatch()

    body["title"]?.let { value ->
        val title = (value as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?: throw BadRequestException("title must be a string")
        if (title.isEmpty()) {
            throw BadRequestException("title must not be blank")
        }
        patch = patch.copy(patchedTitle = true, title = title)
    }

    body["customSystemPrompt"]?.let { value ->
        // App 侧空串同样归为清除（ConversationSystemPromptButton 的 ifBlank -> null）
        val prompt = when {
            value is JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content.ifBlank { null }
            else -> throw BadRequestException("customSystemPrompt must be a string or null")
        }
        if (prompt != null && !allowConversationSystemPrompt) {
            throw BadRequestException("Conversation system prompt is not enabled for this assistant")
        }
        patch = patch.copy(patchedCustomSystemPrompt = true, customSystemPrompt = prompt)
    }

    body["workspaceCwd"]?.let { value ->
        val cwd = when {
            value is JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content
            else -> throw BadRequestException("workspaceCwd must be a string or null")
        }
        if (cwd != null) {
            if (!cwd.startsWith("/")) {
                throw BadRequestException("workspaceCwd must be an absolute path")
            }
            if (cwd.split('/').any { it == ".." }) {
                throw BadRequestException("workspaceCwd must not contain '..' segments")
            }
        }
        patch = patch.copy(patchedWorkspaceCwd = true, workspaceCwd = cwd)
    }

    body["workspaceCwdScoped"]?.let { value ->
        val scoped = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw BadRequestException("workspaceCwdScoped must be a boolean")
        patch = patch.copy(workspaceCwdScoped = scoped)
    }

    if (!patch.patchedTitle && !patch.patchedCustomSystemPrompt && !patch.patchedWorkspaceCwd &&
        patch.workspaceCwdScoped == null) {
        throw BadRequestException("Empty patch")
    }

    return patch
}
