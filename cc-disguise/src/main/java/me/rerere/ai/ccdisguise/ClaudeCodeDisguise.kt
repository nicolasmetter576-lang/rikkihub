package me.rerere.ai.ccdisguise

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request

object ClaudeCodeDisguise {
    const val IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."
    const val BETA_TAG = "claude-code-20250219"
    const val USER_AGENT = "claude-cli/1.0.119 (external, cli)"
    const val X_APP = "cli"

    // ── Config-aware overloads (preferred) ──────────────────────

    fun applyBody(body: JsonObject, config: ClaudeCodeDisguiseConfig): JsonObject {
        val applySystem = config.systemPromptEnabled && config.systemPrompt.isNotBlank()
        val userId = config.effectiveUserId()
        if (!applySystem && userId.isBlank()) return body
        val map = body.toMutableMap()
        if (applySystem) {
            map["system"] = prependIdentity(map["system"], config.systemPrompt)
        }
        if (userId.isNotBlank()) {
            map["metadata"] = mergeUserId(map["metadata"], userId)
        }
        return JsonObject(map)
    }

    fun applyHeaders(builder: Request.Builder, existingBeta: String?, config: ClaudeCodeDisguiseConfig): Request.Builder {
        val safe = config.sanitized()
        return builder
            .header("User-Agent", safe.userAgent)
            .header("x-app", safe.xApp)
            .header("anthropic-beta", mergeBeta(existingBeta, safe.betaTags()))
    }

    // ── Legacy overloads (for tests & backward compat) ──────────

    fun applyBody(body: JsonObject, userId: String): JsonObject {
        val map = body.toMutableMap()
        map["system"] = prependIdentity(map["system"])
        map["metadata"] = mergeUserId(map["metadata"], userId)
        return JsonObject(map)
    }

    fun applyHeaders(builder: Request.Builder, existingBeta: String?): Request.Builder {
        return builder
            .header("User-Agent", USER_AGENT)
            .header("x-app", X_APP)
            .header("anthropic-beta", mergeBeta(existingBeta))
    }

    fun mergeBeta(existing: String?): String {
        return mergeBeta(existing, listOf(BETA_TAG))
    }

    /**
     * Merges [configTags] with any existing beta tags from model custom headers.
     * Preserves order, trims whitespace, deduplicates.
     */
    fun mergeBeta(existing: String?, configTags: List<String>): String {
        val existingTags = existing
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (tag in existingTags + configTags) {
            if (seen.add(tag)) {
                result.add(tag)
            }
        }
        return result.joinToString(",").ifEmpty { BETA_TAG }
    }

    internal fun prependIdentity(existing: JsonElement?, identityText: String = IDENTITY): JsonArray {
        val blocks: MutableList<JsonElement> = when (existing) {
            is JsonPrimitive -> {
                val text = existing.content
                if (text.isEmpty()) {
                    mutableListOf()
                } else {
                    mutableListOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    )
                }
            }

            is JsonArray -> existing.mapTo(mutableListOf()) { it }
            else -> mutableListOf()
        }
        val alreadyPresent = blocks.any { block ->
            block is JsonObject && block["text"]?.jsonPrimitive?.contentOrNull == identityText
        }
        if (!alreadyPresent) {
            blocks.add(
                0,
                buildJsonObject {
                    put("type", "text")
                    put("text", identityText)
                }
            )
        }
        return JsonArray(blocks)
    }

    internal fun mergeUserId(
        existing: JsonElement?,
        userId: String,
    ): JsonObject {
        val map = if (existing is JsonObject) {
            existing.toMutableMap()
        } else {
            mutableMapOf()
        }
        map["user_id"] = JsonPrimitive(userId)
        return JsonObject(map)
    }
}
