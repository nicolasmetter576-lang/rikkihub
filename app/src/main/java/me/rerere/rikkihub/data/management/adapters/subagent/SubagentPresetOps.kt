package me.rerere.rikkihub.data.management.adapters.subagent

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.ports.StoreKind
import me.rerere.rikkihub.data.management.ports.StoreOperation

/**
 * 子代理预设域的存储步骤（StoreKind.MAIN_ROOM）。
 *
 * 生产 gateway（待 mutation 接线）：
 * - Library* → `SubagentPresetRepository` 的 CAS 面（library entity revision CAS +
 *   `publishPresetLibraryBatch` 事务内 workspace 投影 materialize）；
 * - Local* → `WorkspaceRepository.applyConfigPatch` 族（subagent_presets JSON 的
 *   同事务 validateAll + 定向 SQL）；
 * - 每步幂等：operationId → operation_receipts。
 */
@Serializable
sealed interface SubagentPresetStoreOp : StoreOperation {
    override val store: StoreKind get() = StoreKind.MAIN_ROOM

    @Serializable
    data class LibraryCreate(
        val preset: WorkspaceSubagentPreset,
        val workspaceIds: Set<String>,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.library.create",
    ) : SubagentPresetStoreOp

    @Serializable
    data class LibraryPatch(
        val preset: WorkspaceSubagentPreset,
        /** patch 前期望的对象版本（存储层复核）。 */
        val expectedVersion: Long,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.library.patch",
    ) : SubagentPresetStoreOp

    @Serializable
    data class LibraryDelete(
        val presetId: String,
        /** 预期链接工作区集合（detach 全部）。 */
        val linkedWorkspaceIds: Set<String>,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.library.delete",
    ) : SubagentPresetStoreOp

    @Serializable
    data class LibraryRestore(
        val preset: WorkspaceSubagentPreset,
        val workspaceIds: Set<String>,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.library.restore",
    ) : SubagentPresetStoreOp

    @Serializable
    data class LibraryReorder(
        val orderedPresetIds: List<String>,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.library.reorder",
    ) : SubagentPresetStoreOp

    @Serializable
    data class LibrarySetScope(
        val presetId: String,
        val attachWorkspaceIds: Set<String>,
        val detachWorkspaceIds: Set<String>,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.library.setScope",
    ) : SubagentPresetStoreOp

    @Serializable
    data class LocalUpsert(
        val workspaceId: String,
        val preset: WorkspaceSubagentPreset,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.local.upsert",
    ) : SubagentPresetStoreOp

    @Serializable
    data class LocalDelete(
        val workspaceId: String,
        val presetId: String,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.local.delete",
    ) : SubagentPresetStoreOp

    @Serializable
    data class LocalReorder(
        val workspaceId: String,
        val orderedPresetIds: List<String>,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "subagent.local.reorder",
    ) : SubagentPresetStoreOp
}

/** read-set collectionId 约定（本域）。 */
object SubagentCollections {
    /** 全局库集合（list order / membership CAS）。 */
    const val LIBRARY = "room.subagentPresetLibrary"

    /** 单工作区预设集合 token：`room.workspacePresets.<workspaceId>`。 */
    fun workspacePresets(workspaceId: String) = "room.workspacePresets.$workspaceId"

    /** 库预设对象 token：`room.subagentLibrary` + presetId。 */
    fun libraryPreset(presetId: String) = "room.subagentLibrary.$presetId"

    /** 工作区本地预设对象 token：`room.workspacePreset.<workspaceId>.<presetId>`。 */
    fun localPreset(workspaceId: String, presetId: String) =
        "room.workspacePreset.$workspaceId.$presetId"
}
