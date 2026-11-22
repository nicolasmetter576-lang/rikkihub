package me.rerere.rikkihub.data.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkihub.data.datastore.Settings
import kotlin.uuid.Uuid

@Serializable
enum class CliPermissionMode {
    @SerialName("ask") ASK,
    @SerialName("auto") AUTO,
}

/** A user-added ACP agent. `{system_prompt_file}` in [args] becomes the container path of the assistant prompt. */
@Serializable
data class CustomCliAgentConfig(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val executable: String = "",
    val args: List<String> = emptyList(),
    val envVars: List<CustomHeader> = emptyList(),
    val loginCommand: String = "",
    val installCommand: String = "",
)

/** Extra environment for a built-in agent ([id] = its agent id); a list so settings merge by id. */
@Serializable
data class CliEnvOverride(
    val id: String,
    val envVars: List<CustomHeader> = emptyList(),
)

/**
 * The CLI state of one conversation. Written only through `ChatService.updateSessionMetadata`
 * (its own column); full conversation saves keep the stored value.
 */
@Serializable
data class ConversationCliSession(
    val agentId: String,
    val acpSessionId: String? = null,
    val permissionMode: CliPermissionMode = CliPermissionMode.ASK,
    /** Legacy model/mode choice for agents without config options. */
    val modelId: String? = null,
    val modeId: String? = null,
    /** Chosen session config options (id → value), re-applied when a new agent session starts. */
    val configValues: Map<String, String> = emptyMap(),
    val systemPromptHash: String? = null,
    /** Fingerprint of the chat the agent session has seen; a different one means the chat was edited. */
    val historyFingerprint: String? = null,
    /** The next session must start with the conversation history (fork, restart). */
    val needsHistorySeed: Boolean = false,
    val lastActiveAtMillis: Long = 0L,
) {
    /** Forget the agent-side session; the next turn opens a new one seeded with the history. */
    fun reset(): ConversationCliSession = copy(acpSessionId = null, systemPromptHash = null, needsHistorySeed = true)
}

/** A resolved agent, built-in or custom, ready to launch. */
data class CliAgent(
    val id: String,
    val name: String,
    val executable: String,
    val args: List<String>,
    val envVars: List<CustomHeader>,
    val loginCommand: String,
    val installCommand: String,
    /** Environment variable → ACP auth method to call when the variable is set. */
    val apiKeyAuth: Map<String, String> = emptyMap(),
    val builtIn: Boolean = false,
) {
    val usesPromptFile: Boolean get() = args.any { PROMPT_FILE_PLACEHOLDER in it }

    companion object {
        const val PROMPT_FILE_PLACEHOLDER = "{system_prompt_file}"
        const val AGY = "builtin:agy"
        const val DROID = "builtin:droid"

        /** Ubuntu Base has no curl; both official installers are fetched with it. */
        private const val ENSURE_CURL =
            "command -v curl >/dev/null 2>&1 || { apt-get update && apt-get install -y --no-install-recommends curl ca-certificates; }; "

        val BUILT_IN = listOf(
            CliAgent(
                id = AGY,
                name = "Antigravity",
                executable = "agy",
                args = listOf("acp", "--disable-telemetry"),
                envVars = emptyList(),
                loginCommand = "agy",
                installCommand = ENSURE_CURL + "curl -fsSL https://antigravity.google/cli/install.sh | bash",
                apiKeyAuth = mapOf("GEMINI_API_KEY" to "gemini-api-key"),
                builtIn = true,
            ),
            CliAgent(
                id = DROID,
                name = "Droid",
                executable = "droid",
                args = listOf("exec", "--output-format", "acp", "--append-system-prompt-file", PROMPT_FILE_PLACEHOLDER),
                envVars = emptyList(),
                loginCommand = "droid",
                installCommand = ENSURE_CURL + "curl -fsSL https://app.factory.ai/cli | sh",
                apiKeyAuth = mapOf("FACTORY_API_KEY" to "factory-api-key"),
                builtIn = true,
            ),
        )

        /** Variables a user may not set: the launcher and the container own them. */
        private val RESERVED_ENV = setOf(
            "HOME",
            "PATH",
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
            "TERM",
            "BASH_ENV",
            "ENV",
            "NODE_OPTIONS",
            "PYTHONPATH",
            "PYTHONSTARTUP",
            "PERL5OPT",
            "RUBYOPT",
            "ZDOTDIR",
            "CDPATH",
            "IFS",
        )

        fun isAllowedEnvName(name: String): Boolean =
            name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && name !in RESERVED_ENV && !name.startsWith("PROOT_")

        fun all(settings: Settings): List<CliAgent> = BUILT_IN.map { agent ->
            agent.copy(envVars = settings.builtInCliEnvOverrides.firstOrNull { it.id == agent.id }?.envVars.orEmpty())
        } + settings.customCliAgents.map { config ->
            CliAgent(
                id = config.id,
                name = config.name.ifBlank { config.executable },
                executable = config.executable,
                args = config.args,
                envVars = config.envVars,
                loginCommand = config.loginCommand,
                installCommand = config.installCommand,
            )
        }

        fun find(settings: Settings, id: String): CliAgent? = all(settings).firstOrNull { it.id == id }
    }
}
