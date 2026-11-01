package me.rerere.rikkihub.data.management.adapters.subagent

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.ai.tools.SubagentPresetGenerationConfig
import me.rerere.rikkihub.data.ai.tools.SubagentPresetModelSelection
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.ports.ManagementCommand

/**
 * 子代理预设域命令集（`ManagementCommand`）。
 *
 * 不变量（adapter 校验，存储层复核）：
 * - id/key/libraryPresetId/version/executionRevision 由宿主分配/管理——
 *   Create 命令只携带 [SubagentPresetDraft] 可写字段；宿主分配的新 id 经
 *   `PlanContext.hostIds` 冻结进提案 payload；
 * - 库命令（Library*）作用于 `subagent_preset_library`（Room CAS）；
 *   本地命令（Local*）作用于单工作区 `subagent_presets` JSON 列
 *   （经 `applyConfigPatch` 族事务）；
 * - 链接投影（libraryPresetId != null）只能经库命令驱动，本地 patch 拒绝；
 * - key 不可变；FIXED 模式才允许 modelId。
 */

/** Create 用可写字段草案（不含宿主分配字段）。 */
@Serializable
data class SubagentPresetDraft(
    val key: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val enabled: Boolean = true,
    val modelSelection: SubagentPresetModelSelection = SubagentPresetModelSelection.WORKSPACE_DEFAULT,
    val modelId: String? = null,
    val generationConfig: SubagentPresetGenerationConfig = SubagentPresetGenerationConfig(),
    val acceptContext: Boolean = true,
    val maxSteps: Int = 24,
)

/**
 * Patch 可写字段；null = 不动。key/id/libraryPresetId/version 不在其中。
 * [clearModelId] 显式清除 modelId（配合 modelSelection != FIXED）。
 */
@Serializable
data class SubagentPresetPatch(
    val name: String? = null,
    val description: String? = null,
    val systemPrompt: String? = null,
    val enabled: Boolean? = null,
    val modelSelection: SubagentPresetModelSelection? = null,
    val modelId: String? = null,
    val clearModelId: Boolean = false,
    val generationConfig: SubagentPresetGenerationConfig? = null,
    val acceptContext: Boolean? = null,
    val maxSteps: Int? = null,
)

@Serializable
sealed interface SubagentPresetCommand : ManagementCommand {

    @Serializable
    data class CreateLibraryPreset(
        val draft: SubagentPresetDraft,
        /** 初始作用域（绑定到这些工作区，可为空）。 */
        val workspaceIds: Set<String> = emptySet(),
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() = (workspaceIds.map { ObjectRef(domain, "workspace:$it") } +
            listOf(ObjectRef(domain, "library:\$new0")))
        override fun risk() = RiskLevel.MEDIUM
    }

    @Serializable
    data class PatchLibraryPreset(
        val presetId: String,
        val patch: SubagentPresetPatch,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() = listOf(ObjectRef(domain, "library:$presetId"))
        override fun risk() = if (patch.enabled == false || patch.acceptContext == false)
            RiskLevel.HIGH else RiskLevel.MEDIUM
    }

    @Serializable
    data class DeleteLibraryPreset(
        val presetId: String,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() = listOf(ObjectRef(domain, "library:$presetId"))
        override fun risk() = RiskLevel.HIGH
    }

    /**
     * 恢复：tombstone 快照重建库定义（新 generation）。
     * [snapshotJson] 由删除提案的回收 payload 携带，模型不得自由编造。
     */
    @Serializable
    data class RestoreLibraryPreset(
        val presetId: String,
        val snapshotJson: String,
        val workspaceIds: Set<String> = emptySet(),
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() = listOf(ObjectRef(domain, "library:$presetId"))
        override fun risk() = RiskLevel.MEDIUM
    }

    /** 全局排序：整集合 CAS。 */
    @Serializable
    data class ReorderLibrary(
        val orderedPresetIds: List<String>,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() = listOf(ObjectRef(domain, "library"))
        override fun risk() = RiskLevel.LOW
    }

    /** 绑定/解绑工作区（作用域修改）。 */
    @Serializable
    data class SetPresetScope(
        val presetId: String,
        val workspaceIds: Set<String>,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() =
            listOf(ObjectRef(domain, "library:$presetId")) +
                workspaceIds.map { ObjectRef(domain, "workspace:$it") }
        override fun risk() = RiskLevel.MEDIUM
    }

    @Serializable
    data class CreateLocalPreset(
        val workspaceId: String,
        val draft: SubagentPresetDraft,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() = listOf(ObjectRef(domain, "workspace:$workspaceId"))
        override fun risk() = RiskLevel.MEDIUM
    }

    @Serializable
    data class PatchLocalPreset(
        val workspaceId: String,
        val presetId: String,
        val patch: SubagentPresetPatch,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() =
            listOf(ObjectRef(domain, "workspace:$workspaceId:$presetId"))
        override fun risk() = if (patch.enabled == false || patch.acceptContext == false)
            RiskLevel.HIGH else RiskLevel.MEDIUM
    }

    @Serializable
    data class DeleteLocalPreset(
        val workspaceId: String,
        val presetId: String,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() =
            listOf(ObjectRef(domain, "workspace:$workspaceId:$presetId"))
        override fun risk() = RiskLevel.HIGH
    }

    @Serializable
    data class RestoreLocalPreset(
        val workspaceId: String,
        val presetId: String,
        val snapshotJson: String,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() =
            listOf(ObjectRef(domain, "workspace:$workspaceId:$presetId"))
        override fun risk() = RiskLevel.MEDIUM
    }

    @Serializable
    data class ReorderLocalPresets(
        val workspaceId: String,
        val orderedPresetIds: List<String>,
    ) : SubagentPresetCommand {
        override val domain = ManagementDomain.SUBAGENT_PRESET
        override fun affectedRefs() = listOf(ObjectRef(domain, "workspace:$workspaceId"))
        override fun risk() = RiskLevel.LOW
    }
}
