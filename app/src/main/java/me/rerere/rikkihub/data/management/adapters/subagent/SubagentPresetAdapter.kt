package me.rerere.rikkihub.data.management.adapters.subagent

import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.management.adapters.AdapterBudget
import me.rerere.rikkihub.data.management.adapters.reject
import me.rerere.rikkihub.data.management.adapters.rejectConflict
import me.rerere.rikkihub.data.management.adapters.rejectInvalid
import me.rerere.rikkihub.data.management.adapters.rejectNotFound
import me.rerere.rikkihub.data.management.common.DiffLine
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSet
import me.rerere.rikkihub.data.management.common.ReadSetEntry
import me.rerere.rikkihub.data.management.common.RetentionNote
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.common.VersionToken
import me.rerere.rikkihub.data.management.coordinator.ManagementDomainAdapter
import me.rerere.rikkihub.data.management.coordinator.PlanContext
import me.rerere.rikkihub.data.management.coordinator.PlannedMutation
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.management.ports.StoreOperation
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode
import me.rerere.rikkihub.utils.JsonInstant

/**
 * 子代理预设域 adapter（`ManagementDomainAdapter` 实现）。
 *
 * - `plan` 纯读 `ctx.read`（锁内最新镜像端口）+ 全量校验（复用
 *   `WorkspaceSubagentPresets.validate/validateAll`，不平行发明）；
 * - 步骤是 typed `SubagentPresetStoreOp`（StoreKind.MAIN_ROOM）——生产 gateway
 *   兑现到 `SubagentPresetRepository` CAS 面 / `WorkspaceRepository.applyConfigPatch`
 *   族（PENDING-MUTATION-ACCEPTANCE）；测试用内存 gateway 执行；
 * - `inverse` 生成逆命令；删除类命令 `needsRecyclePayload=true`（回收 payload
 *   由 coordinator/recovery 保管 before-image）。
 */
class SubagentPresetAdapter : ManagementDomainAdapter {

    override val domain = ManagementDomain.SUBAGENT_PRESET

    override suspend fun plan(ctx: PlanContext, command: ManagementCommand): PlannedMutation {
        require(command is SubagentPresetCommand) {
            "SubagentPresetAdapter only handles SubagentPresetCommand"
        }
        val snap = SubagentReadModel(ctx.read, ctx.storageEpoch).snapshot()
        return when (command) {
            is SubagentPresetCommand.CreateLibraryPreset -> planCreateLibrary(ctx, command, snap)
            is SubagentPresetCommand.PatchLibraryPreset -> planPatchLibrary(ctx, command, snap)
            is SubagentPresetCommand.DeleteLibraryPreset -> planDeleteLibrary(ctx, command, snap)
            is SubagentPresetCommand.RestoreLibraryPreset -> planRestoreLibrary(ctx, command, snap)
            is SubagentPresetCommand.ReorderLibrary -> planReorderLibrary(ctx, command, snap)
            is SubagentPresetCommand.SetPresetScope -> planSetScope(ctx, command, snap)
            is SubagentPresetCommand.CreateLocalPreset -> planCreateLocal(ctx, command, snap)
            is SubagentPresetCommand.PatchLocalPreset -> planPatchLocal(ctx, command, snap)
            is SubagentPresetCommand.DeleteLocalPreset -> planDeleteLocal(ctx, command, snap)
            is SubagentPresetCommand.RestoreLocalPreset -> planRestoreLocal(ctx, command, snap)
            is SubagentPresetCommand.ReorderLocalPresets -> planReorderLocal(ctx, command, snap)
        }
    }

    override suspend fun inverse(ctx: PlanContext, command: ManagementCommand): ManagementCommand? =
        when (command) {
            is SubagentPresetCommand.CreateLibraryPreset ->
                SubagentPresetCommand.DeleteLibraryPreset("\$new0")
            is SubagentPresetCommand.PatchLibraryPreset -> command.copy(
                patch = SubagentPresetPatch() // 占位：完整逆 patch 需要 before 值，由恢复/Undo 提案携带快照重建
            )
            is SubagentPresetCommand.DeleteLibraryPreset,
            is SubagentPresetCommand.DeleteLocalPreset -> null // 恢复材料在回收 payload
            is SubagentPresetCommand.RestoreLibraryPreset ->
                SubagentPresetCommand.DeleteLibraryPreset(command.presetId)
            is SubagentPresetCommand.RestoreLocalPreset ->
                SubagentPresetCommand.DeleteLocalPreset(command.workspaceId, command.presetId)
            is SubagentPresetCommand.ReorderLibrary -> null // Undo = 原 order 的新 Reorder（由审计快照重建）
            is SubagentPresetCommand.SetPresetScope -> null
            is SubagentPresetCommand.CreateLocalPreset ->
                SubagentPresetCommand.DeleteLocalPreset(command.workspaceId, "\$new0")
            is SubagentPresetCommand.PatchLocalPreset -> command.copy(patch = SubagentPresetPatch())
            is SubagentPresetCommand.ReorderLocalPresets -> null
            else -> rejectInvalid("unsupported subagent command")
        }

    override fun needsRecyclePayload(command: ManagementCommand): Boolean =
        command is SubagentPresetCommand.DeleteLibraryPreset ||
            command is SubagentPresetCommand.DeleteLocalPreset

    // ---- 库命令 ------------------------------------------------------------------

    private fun planCreateLibrary(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.CreateLibraryPreset,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        if (snap.libraryPresets.size >= MAX_LIBRARY_PRESETS)
            reject(ProposalErrorCode.BUDGET_EXCEEDED, "library is full")
        val draft = materializeDraft(cmd.draft, ctx.hostIds.newObjectId())
        if (snap.libraryPresets.any { it.key == draft.key })
            rejectConflict("preset key '${draft.key}' already exists in the library")
        cmd.workspaceIds.forEach { wsId ->
            if (wsId !in snap.knownWorkspaceIds)
                rejectNotFound("target workspace $wsId does not exist")
            if (snap.workspacePresets[wsId].orEmpty().any { it.libraryPresetId == null && it.key == draft.key })
                rejectConflict("workspace $wsId already has an independent preset key '${draft.key}'")
        }
        val after = draft.copy(sortOrder = (snap.libraryPresets.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        val readSet = ReadSet(listOf(
            ReadSetEntry.Collection(SubagentCollections.LIBRARY, snap.libraryToken),
        ) + cmd.workspaceIds.map {
            ReadSetEntry.Collection(SubagentCollections.workspacePresets(it), snap.workspaceCollectionToken(it))
        })
        val ops = listOf<StoreOperation>(SubagentPresetStoreOp.LibraryCreate(
            preset = after, workspaceIds = cmd.workspaceIds,
            objectRefs = listOf(ObjectRef(domain, "library:${after.id}")),
        ))
        return PlannedMutation(
            readSet = readSet, steps = ops,
            diffSummary = listOf(DiffLine("subagentPresets.library['${draft.key}']", null, summarize(after))) +
                cmd.workspaceIds.map { DiffLine("workspaces[$it].subagentPresets[]", null, "linked '${draft.key}'") },
            risk = cmd.risk(),
            retention = RetentionNote(),
            cascadeTargets = cmd.workspaceIds.map { ObjectRef(domain, "workspace:$it") },
            requiresEnhancedConfirm = false,
            hostAssignedIds = mapOf("\$new0" to draft.id),
        )
    }

    private fun planPatchLibrary(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.PatchLibraryPreset,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val old = snap.libraryPreset(cmd.presetId)
            ?: rejectNotFound("library preset ${cmd.presetId} not found")
        val next = applyPatch(old, cmd.patch)
        if (next == old) rejectInvalid("patch does not change anything")
        val execChanged = !WorkspaceSubagentPresets.sameExecution(old, next)
        val revoke = old.enabled && (!next.enabled || (old.acceptContext && !next.acceptContext))
        val linked = snap.linkedWorkspaces(old.id)
        val readSet = ReadSet(listOf(
            ReadSetEntry.Object(ObjectRef(domain, "library:${old.id}"), snap.libraryObjectToken(old.id)),
            ReadSetEntry.Collection(SubagentCollections.LIBRARY, snap.libraryToken),
        ) + linked.map {
            ReadSetEntry.Collection(SubagentCollections.workspacePresets(it), snap.workspaceCollectionToken(it))
        })
        return PlannedMutation(
            readSet = readSet,
            steps = listOf(SubagentPresetStoreOp.LibraryPatch(
                preset = next, expectedVersion = old.version,
                objectRefs = listOf(ObjectRef(domain, "library:${old.id}")))),
            diffSummary = diffPreset(old, next, "subagentPresets.library['${old.key}']"),
            risk = if (revoke) RiskLevel.HIGH else RiskLevel.MEDIUM,
            retention = RetentionNote(),
            cascadeTargets = linked.map { ObjectRef(domain, "workspace:$it") },
            requiresEnhancedConfirm = revoke,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planDeleteLibrary(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.DeleteLibraryPreset,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val old = snap.libraryPreset(cmd.presetId)
            ?: rejectNotFound("library preset ${cmd.presetId} not found")
        val linked = snap.linkedWorkspaces(old.id)
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "library:${old.id}"), snap.libraryObjectToken(old.id)),
                ReadSetEntry.Collection(SubagentCollections.LIBRARY, snap.libraryToken),
            ) + linked.map {
                ReadSetEntry.Collection(SubagentCollections.workspacePresets(it), snap.workspaceCollectionToken(it))
            }),
            steps = listOf(SubagentPresetStoreOp.LibraryDelete(
                presetId = old.id, linkedWorkspaceIds = linked,
                objectRefs = listOf(ObjectRef(domain, "library:${old.id}")))),
            diffSummary = listOf(DiffLine("subagentPresets.library['${old.key}']", summarize(old), null)) +
                linked.map { DiffLine("workspaces[$it].subagentPresets[]", "linked '${old.key}'", null) },
            risk = RiskLevel.HIGH,
            retention = RetentionNote(recycleDays = 30),
            cascadeTargets = linked.map { ObjectRef(domain, "workspace:$it") },
            requiresEnhancedConfirm = true,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planRestoreLibrary(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.RestoreLibraryPreset,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        if (snap.libraryPreset(cmd.presetId) != null)
            rejectConflict("preset ${cmd.presetId} still exists")
        val restored = runCatching {
            JsonInstant.decodeFromString(WorkspaceSubagentPreset.serializer(), cmd.snapshotJson)
                .also { WorkspaceSubagentPresets.validate(it) }
        }.getOrElse { rejectInvalid("invalid tombstone snapshot") }
        if (restored.id != cmd.presetId) rejectInvalid("snapshot id does not match restore target")
        if (snap.libraryPresets.any { it.key == restored.key })
            rejectConflict("preset key '${restored.key}' is occupied")
        val rebound = cmd.workspaceIds.filter { it in snap.knownWorkspaceIds }.toSet()
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Collection(SubagentCollections.LIBRARY, snap.libraryToken),
            ) + rebound.map {
                ReadSetEntry.Collection(SubagentCollections.workspacePresets(it), snap.workspaceCollectionToken(it))
            }),
            steps = listOf(SubagentPresetStoreOp.LibraryRestore(
                preset = restored, workspaceIds = rebound,
                objectRefs = listOf(ObjectRef(domain, "library:${restored.id}")))),
            diffSummary = listOf(DiffLine("subagentPresets.library['${restored.key}']", null, summarize(restored))) +
                rebound.map { DiffLine("workspaces[$it].subagentPresets[]", null, "linked '${restored.key}'") },
            risk = RiskLevel.MEDIUM, retention = RetentionNote(),
            cascadeTargets = rebound.map { ObjectRef(domain, "workspace:$it") },
            requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planReorderLibrary(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.ReorderLibrary,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val current = snap.libraryPresets.map { it.id }
        if (cmd.orderedPresetIds.sorted() != current.sorted())
            rejectConflict("reorder must list every library preset exactly once")
        val order = cmd.orderedPresetIds.withIndex().associate { it.value to it.index }
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Collection(SubagentCollections.LIBRARY, snap.libraryToken))),
            steps = listOf(SubagentPresetStoreOp.LibraryReorder(
                orderedPresetIds = cmd.orderedPresetIds,
                objectRefs = listOf(ObjectRef(domain, "library")))),
            diffSummary = snap.libraryPresets.map { p ->
                DiffLine("subagentPresets.library['${p.key}'].sortOrder",
                    p.sortOrder.toString(), order.getValue(p.id).toString())
            },
            risk = RiskLevel.LOW, retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planSetScope(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.SetPresetScope,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val old = snap.libraryPreset(cmd.presetId)
            ?: rejectNotFound("library preset ${cmd.presetId} not found")
        val unknown = cmd.workspaceIds - snap.knownWorkspaceIds
        if (unknown.isNotEmpty()) rejectNotFound("unknown workspaces: ${unknown.joinToString()}")
        val current = snap.linkedWorkspaces(old.id)
        if (current == cmd.workspaceIds) rejectInvalid("scope already equals the requested set")
        val detached = current - cmd.workspaceIds
        val attached = cmd.workspaceIds - current
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "library:${old.id}"), snap.libraryObjectToken(old.id)),
                ReadSetEntry.Collection(SubagentCollections.LIBRARY, snap.libraryToken),
            ) + (current + cmd.workspaceIds).map {
                ReadSetEntry.Collection(SubagentCollections.workspacePresets(it), snap.workspaceCollectionToken(it))
            }),
            steps = listOf(SubagentPresetStoreOp.LibrarySetScope(
                presetId = old.id, attachWorkspaceIds = attached, detachWorkspaceIds = detached,
                objectRefs = listOf(ObjectRef(domain, "library:${old.id}")))),
            diffSummary = attached.map { DiffLine("workspaces[$it].subagentPresets[]", null, "linked '${old.key}'") } +
                detached.map { DiffLine("workspaces[$it].subagentPresets[]", "linked '${old.key}'", null) },
            risk = if (detached.isNotEmpty()) RiskLevel.HIGH else RiskLevel.MEDIUM,
            retention = RetentionNote(),
            cascadeTargets = (attached + detached).map { ObjectRef(domain, "workspace:$it") },
            requiresEnhancedConfirm = detached.isNotEmpty(),
            hostAssignedIds = emptyMap(),
        )
    }

    // ---- 本地（工作区）命令 -------------------------------------------------------

    private fun requireWorkspace(snap: SubagentReadModel.Snapshot, wsId: String): List<WorkspaceSubagentPreset> {
        if (wsId !in snap.knownWorkspaceIds) rejectNotFound("workspace $wsId does not exist")
        return snap.workspacePresets[wsId].orEmpty()
    }

    private fun planCreateLocal(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.CreateLocalPreset,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val ws = requireWorkspace(snap, cmd.workspaceId)
        val draft = materializeDraft(cmd.draft, ctx.hostIds.newObjectId())
        if (ws.any { it.key == draft.key })
            rejectConflict("workspace already has preset key '${draft.key}'")
        if (ws.size >= WorkspaceSubagentPresets.MAX_PRESETS)
            reject(ProposalErrorCode.BUDGET_EXCEEDED, "workspace preset capacity reached")
        val after = draft.copy(sortOrder = (ws.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Collection(SubagentCollections.workspacePresets(cmd.workspaceId),
                    snap.workspaceCollectionToken(cmd.workspaceId)))),
            steps = listOf(SubagentPresetStoreOp.LocalUpsert(
                workspaceId = cmd.workspaceId, preset = after,
                objectRefs = listOf(ObjectRef(domain, "workspace:${cmd.workspaceId}")))),
            diffSummary = listOf(DiffLine("workspaces[${cmd.workspaceId}].presets['${draft.key}']",
                null, summarize(after))),
            risk = RiskLevel.MEDIUM, retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = mapOf("\$new0" to draft.id),
        )
    }

    private fun planPatchLocal(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.PatchLocalPreset,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val ws = requireWorkspace(snap, cmd.workspaceId)
        val old = ws.firstOrNull { it.id == cmd.presetId }
            ?: rejectNotFound("preset ${cmd.presetId} not found in workspace")
        if (old.libraryPresetId != null)
            reject(ProposalErrorCode.FORBIDDEN_FIELD,
                "linked shared projections are edited via the library, not locally")
        val next = applyPatch(old, cmd.patch)
        if (next == old) rejectInvalid("patch does not change anything")
        val revoke = old.enabled && (!next.enabled || (old.acceptContext && !next.acceptContext))
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "workspace:${cmd.workspaceId}:${old.id}"),
                    snap.localObjectToken(cmd.workspaceId, old.id)),
                ReadSetEntry.Collection(SubagentCollections.workspacePresets(cmd.workspaceId),
                    snap.workspaceCollectionToken(cmd.workspaceId)))),
            steps = listOf(SubagentPresetStoreOp.LocalUpsert(
                workspaceId = cmd.workspaceId,
                preset = next.copy(version = old.version + 1),
                objectRefs = listOf(ObjectRef(domain, "workspace:${cmd.workspaceId}:${old.id}")))),
            diffSummary = diffPreset(old, next, "workspaces[${cmd.workspaceId}].presets['${old.key}']"),
            risk = if (revoke) RiskLevel.HIGH else RiskLevel.MEDIUM,
            retention = RetentionNote(), cascadeTargets = emptyList(),
            requiresEnhancedConfirm = revoke,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planDeleteLocal(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.DeleteLocalPreset,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val ws = requireWorkspace(snap, cmd.workspaceId)
        val old = ws.firstOrNull { it.id == cmd.presetId }
            ?: rejectNotFound("preset ${cmd.presetId} not found in workspace")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "workspace:${cmd.workspaceId}:${old.id}"),
                    snap.localObjectToken(cmd.workspaceId, old.id)),
                ReadSetEntry.Collection(SubagentCollections.workspacePresets(cmd.workspaceId),
                    snap.workspaceCollectionToken(cmd.workspaceId)))),
            steps = listOf(SubagentPresetStoreOp.LocalDelete(
                workspaceId = cmd.workspaceId, presetId = old.id,
                objectRefs = listOf(ObjectRef(domain, "workspace:${cmd.workspaceId}:${old.id}")))),
            diffSummary = listOf(DiffLine("workspaces[${cmd.workspaceId}].presets['${old.key}']",
                summarize(old), null)),
            risk = RiskLevel.HIGH, retention = RetentionNote(recycleDays = 30),
            cascadeTargets = emptyList(),
            requiresEnhancedConfirm = old.enabled,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planRestoreLocal(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.RestoreLocalPreset,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val ws = requireWorkspace(snap, cmd.workspaceId)
        if (ws.any { it.id == cmd.presetId }) rejectConflict("preset ${cmd.presetId} still exists")
        val restored = runCatching {
            JsonInstant.decodeFromString(WorkspaceSubagentPreset.serializer(), cmd.snapshotJson)
                .also { WorkspaceSubagentPresets.validate(it) }
        }.getOrElse { rejectInvalid("invalid tombstone snapshot") }
        if (restored.id != cmd.presetId) rejectInvalid("snapshot id does not match restore target")
        if (ws.any { it.key == restored.key })
            rejectConflict("preset key '${restored.key}' is occupied")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Collection(SubagentCollections.workspacePresets(cmd.workspaceId),
                    snap.workspaceCollectionToken(cmd.workspaceId)))),
            steps = listOf(SubagentPresetStoreOp.LocalUpsert(
                workspaceId = cmd.workspaceId, preset = restored,
                objectRefs = listOf(ObjectRef(domain, "workspace:${cmd.workspaceId}:${restored.id}")))),
            diffSummary = listOf(DiffLine("workspaces[${cmd.workspaceId}].presets['${restored.key}']",
                null, summarize(restored))),
            risk = RiskLevel.MEDIUM, retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planReorderLocal(
        ctx: PlanContext,
        cmd: SubagentPresetCommand.ReorderLocalPresets,
        snap: SubagentReadModel.Snapshot,
    ): PlannedMutation {
        val ws = requireWorkspace(snap, cmd.workspaceId)
        val current = ws.map { it.id }
        if (cmd.orderedPresetIds.sorted() != current.sorted())
            rejectConflict("reorder must list every workspace preset exactly once")
        val order = cmd.orderedPresetIds.withIndex().associate { it.value to it.index }
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Collection(SubagentCollections.workspacePresets(cmd.workspaceId),
                    snap.workspaceCollectionToken(cmd.workspaceId)))),
            steps = listOf(SubagentPresetStoreOp.LocalReorder(
                workspaceId = cmd.workspaceId, orderedPresetIds = cmd.orderedPresetIds,
                objectRefs = listOf(ObjectRef(domain, "workspace:${cmd.workspaceId}")))),
            diffSummary = ws.map { p ->
                DiffLine("workspaces[${cmd.workspaceId}].presets['${p.key}'].sortOrder",
                    p.sortOrder.toString(), order.getValue(p.id).toString())
            },
            risk = RiskLevel.LOW, retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    // ---- 工具 ----------------------------------------------------------------------

    private fun materializeDraft(draft: SubagentPresetDraft, hostId: String): WorkspaceSubagentPreset {
        val preset = WorkspaceSubagentPresets.normalize(
            WorkspaceSubagentPreset(
                id = hostId, key = draft.key, name = draft.name, description = draft.description,
                systemPrompt = draft.systemPrompt, enabled = draft.enabled,
                modelSelection = draft.modelSelection, modelId = draft.modelId,
                generationConfig = draft.generationConfig, acceptContext = draft.acceptContext,
                maxSteps = draft.maxSteps, sortOrder = 0, version = 1, executionRevision = 1,
            )
        )
        runCatching { WorkspaceSubagentPresets.validate(preset) }
            .getOrElse { rejectInvalid(it.message ?: "invalid draft") }
        return preset
    }

    private fun applyPatch(old: WorkspaceSubagentPreset, patch: SubagentPresetPatch): WorkspaceSubagentPreset {
        val merged = old.copy(
            name = patch.name ?: old.name,
            description = patch.description ?: old.description,
            systemPrompt = patch.systemPrompt ?: old.systemPrompt,
            enabled = patch.enabled ?: old.enabled,
            modelSelection = patch.modelSelection ?: old.modelSelection,
            modelId = if (patch.clearModelId) null else patch.modelId ?: old.modelId,
            generationConfig = patch.generationConfig ?: old.generationConfig,
            acceptContext = patch.acceptContext ?: old.acceptContext,
            maxSteps = patch.maxSteps ?: old.maxSteps,
        )
        val normalized = WorkspaceSubagentPresets.normalize(merged)
        require(normalized.key == old.key) { "preset key is immutable" }
        runCatching { WorkspaceSubagentPresets.validate(normalized) }
            .getOrElse { rejectInvalid(it.message ?: "invalid patch") }
        return normalized
    }

    private fun diffPreset(old: WorkspaceSubagentPreset, next: WorkspaceSubagentPreset, target: String): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        fun d(field: String, a: Any?, b: Any?) {
            if (a != b) out += DiffLine("$target.$field", a?.toString(), b?.toString())
        }
        d("name", old.name, next.name); d("description", old.description, next.description)
        d("enabled", old.enabled, next.enabled)
        d("modelSelection", old.modelSelection, next.modelSelection)
        d("modelId", old.modelId, next.modelId)
        d("generationConfig", old.generationConfig, next.generationConfig)
        d("acceptContext", old.acceptContext, next.acceptContext)
        d("maxSteps", old.maxSteps, next.maxSteps)
        if (old.systemPrompt != next.systemPrompt)
            out += DiffLine("$target.systemPrompt", "<${old.systemPrompt.length} chars>", "<${next.systemPrompt.length} chars>")
        return out
    }

    private fun summarize(p: WorkspaceSubagentPreset): String =
        "key='${p.key}' name='${p.name}' enabled=${p.enabled} steps=${p.maxSteps}"

    companion object {
        private const val MAX_LIBRARY_PRESETS = 64
    }
}
