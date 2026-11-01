package me.rerere.ai.ccdisguise

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User ID mode for CC disguise.
 */
@Serializable
enum class UserIdMode {
    @SerialName("auto")
    AUTO,

    @SerialName("manual")
    MANUAL,
}

/**
 * Global configuration for Claude Code disguise.
 *
 * Shared across all models; the per-model [me.rerere.ai.provider.Model.ccDisguise] toggle
 * determines whether the disguise is applied at request time.
 */
@Serializable
data class ClaudeCodeDisguiseConfig(
    /** Whether to prepend the identity system block. */
    val systemPromptEnabled: Boolean = true,

    /** The identity text inserted at system index 0. */
    val systemPrompt: String = ClaudeCodeDisguise.IDENTITY,

    /** AUTO uses [autoUserId]; MANUAL uses [manualUserId]. */
    val userIdMode: UserIdMode = UserIdMode.AUTO,

    /** Stable auto-generated user ID, persisted across launches. Empty means not yet initialized. */
    val autoUserId: String = "",

    /** User-supplied user ID for MANUAL mode. */
    val manualUserId: String = "",

    /** CLI User-Agent header value. */
    val userAgent: String = ClaudeCodeDisguise.USER_AGENT,

    /** x-app header value. */
    val xApp: String = ClaudeCodeDisguise.X_APP,

    /** Comma-separated anthropic-beta tags. */
    val anthropicBeta: String = ClaudeCodeDisguise.BETA_TAG,
) {
    companion object {
        /** Default config with all hardcoded defaults. */
        val DEFAULT = ClaudeCodeDisguiseConfig()
    }

    /**
     * Returns the effective user ID based on [userIdMode].
     * Falls back to [autoUserId] if manual is empty.
     */
    fun effectiveUserId(): String {
        return when (userIdMode) {
            UserIdMode.AUTO -> autoUserId
            UserIdMode.MANUAL -> manualUserId.ifBlank { autoUserId }
        }
    }

    /**
     * Parses [anthropicBeta] into individual trimmed, non-empty tags.
     */
    fun betaTags(): List<String> {
        return anthropicBeta
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Sanitizes config values, replacing invalid/empty values with safe defaults.
     * Prevents OkHttp request build failures from corrupted config.
     */
    fun sanitized(): ClaudeCodeDisguiseConfig {
        val safeUserAgent = userAgent.trimCrLf().ifBlank { ClaudeCodeDisguise.USER_AGENT }
        val safeXApp = xApp.trimCrLf().ifBlank { ClaudeCodeDisguise.X_APP }
        val safeBeta = anthropicBeta.trimCrLf().let { raw ->
            val tags = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tags.isEmpty()) ClaudeCodeDisguise.BETA_TAG else tags.joinToString(",")
        }
        val safeAutoUserId = autoUserId.ifBlank { "" } // caller is responsible for init
        return copy(
            userAgent = safeUserAgent,
            xApp = safeXApp,
            anthropicBeta = safeBeta,
            autoUserId = safeAutoUserId,
        )
    }
}

/**
 * Strips CR and LF characters that are illegal in HTTP header values.
 */
private fun String.trimCrLf(): String = this.replace("\r", "").replace("\n", "")
