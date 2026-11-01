package me.rerere.rikkihub.data.management.adapters

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkihub.data.management.common.*
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.management.ports.SettingsManagementPort

/** Closed domain actions; field values pass the per-domain whitelist and typed serializers. */
@Serializable
sealed interface SettingsAction {
    @Serializable data class Create(val fields: Map<String, JsonElement>) : SettingsAction
    @Serializable data class Patch(val id: String, val fields: Map<String, JsonElement>) : SettingsAction
    @Serializable data class Delete(val id: String) : SettingsAction
    @Serializable data class Reorder(val orderedIds: List<String>) : SettingsAction
    @Serializable data class Clone(val id: String, val name: String) : SettingsAction
    @Serializable data class SetField(val fieldId: String, val value: JsonElement) : SettingsAction
    @Serializable data class BindAssistant(val assistantId: String, val targetId: String, val enabled: Boolean) : SettingsAction
    @Serializable data class ResetPrompt(val slot: String) : SettingsAction
}

@Serializable
data class SettingsDomainCommand(
    val collectionId: String,
    val action: SettingsAction,
) : ManagementCommand {
    override val domain: ManagementDomain get() = SettingsManagementPort.domainForCollection(collectionId)
    override fun affectedRefs(): List<ObjectRef> = when (val a = action) {
        is SettingsAction.Patch -> listOf(ObjectRef(domain, a.id))
        is SettingsAction.Delete -> listOf(ObjectRef(domain, a.id))
        is SettingsAction.Clone -> listOf(ObjectRef(domain, a.id))
        else -> emptyList()
    }
    override fun risk(): RiskLevel = when (action) {
        is SettingsAction.Delete, is SettingsAction.Clone, is SettingsAction.SetField,
        is SettingsAction.ResetPrompt, is SettingsAction.BindAssistant -> RiskLevel.HIGH
        is SettingsAction.Reorder -> RiskLevel.LOW
        else -> if (domain == ManagementDomain.ASSISTANT || domain == ManagementDomain.MODEL_AND_PROMPT_DEFAULTS)
            RiskLevel.HIGH else RiskLevel.MEDIUM
    }
}

/**
 * Several Settings commands reviewed and committed as ONE proposal (one DataStore transaction).
 * Commands are planned in order, each on top of the simulated result of the previous ones, so a
 * later command may patch an object an earlier one created or changed. A string value equal to a
 * label of an earlier Create/Clone (`labels[i]`, e.g. `"$0"`) is replaced by that object's
 * host-allocated id when the later command is planned.
 */
@Serializable
data class SettingsBatchCommand(
    val commands: List<SettingsDomainCommand>,
    val labels: List<String> = commands.indices.map { "\$$it" },
) : ManagementCommand {
    init {
        require(commands.size in 1..MAX_COMMANDS) { "A batch holds 1..$MAX_COMMANDS Settings commands" }
        require(labels.size == commands.size && labels.distinct().size == labels.size) { "One distinct label per command" }
    }

    override val domain: ManagementDomain get() = commands.first().domain
    val domains: List<ManagementDomain> get() = commands.map { it.domain }.distinct()
    override fun affectedRefs(): List<ObjectRef> = commands.flatMap { it.affectedRefs() }.distinct()
    override fun risk(): RiskLevel = commands.maxOf { it.risk() }

    companion object {
        const val MAX_COMMANDS = 16
    }
}

/** A command of a [SettingsBatchCommand] failed to plan; [label] tells the caller which one. */
class SettingsBatchStepException(val label: String, override val cause: Throwable) :
    IllegalArgumentException(cause.message, cause)
