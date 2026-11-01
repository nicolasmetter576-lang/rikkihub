package me.rerere.rikkihub.data.management.adapters.subagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.management.common.VersionToken
import me.rerere.rikkihub.data.management.ports.ManagementReadPort
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode
import me.rerere.rikkihub.data.management.proposal.ProposalServiceError

/**
 * 子代理预设域的读模型（经 `ManagementReadPort` 物化）。
 *
 * 生产读口期望（PENDING-MUTATION-CONTRACT，见 from-c-requests.md）：
 * - `snapshotIds("room.subagentPresetLibrary")` → 库预设 id（按 sortOrder）；
 * - `readObjectJson("room.subagentLibrary", presetId)` → 完整
 *   `WorkspaceSubagentPreset` 序列化对象（白名单字段，本域无秘密字段）；
 * - `collectionToken("room.subagentPresetLibrary")` → 库实体 CAS token
 *   （revision = entity.revision）；
 * - `objectToken("room.subagentLibrary", presetId)` → `VersionToken(epoch,0,version)`；
 * - `snapshotIds("room.workspaces")` → 全部工作区 id；
 * - `readObjectJson("room.workspaces", id)` → 至少含 `subagentPresets`（编码 JSON
 *   字符串）与 `name`；
 * - `objectToken("room.workspacePresets", wsId)` → 该工作区预设集合 token
 *   （无 revision 列前 = 规范化指纹承载于 revision 字段）。
 */
class SubagentReadModel(private val read: ManagementReadPort, private val epoch: Long) {

    data class Snapshot(
        val libraryToken: VersionToken,
        val libraryPresets: List<WorkspaceSubagentPreset>,
        /** workspaceId → 预设（本地 + 链接投影）。 */
        val workspacePresets: Map<String, List<WorkspaceSubagentPreset>>,
        val knownWorkspaceIds: Set<String>,
        private val workspaceTokens: Map<String, VersionToken>,
        private val objectTokens: Map<String, VersionToken>,
        val epoch: Long,
    ) {
        fun libraryObjectToken(presetId: String): VersionToken =
            objectTokens["lib:$presetId"] ?: VersionToken(epoch, 0, 0)

        fun workspaceCollectionToken(wsId: String): VersionToken =
            workspaceTokens[wsId] ?: VersionToken(epoch, 0, 0)

        fun localObjectToken(wsId: String, presetId: String): VersionToken =
            objectTokens["ws:$wsId:$presetId"] ?: VersionToken(epoch, 0, 0)

        fun libraryPreset(id: String): WorkspaceSubagentPreset? =
            libraryPresets.firstOrNull { it.id == id }

        fun linkedWorkspaces(presetId: String): Set<String> =
            workspacePresets.filterValues { presets -> presets.any { it.libraryPresetId == presetId } }.keys
    }

    suspend fun snapshot(): Snapshot {
        val libIds = read.snapshotIds(SubagentCollections.LIBRARY)
        val library = libIds.map { id ->
            val json = read.readObjectJson("room.subagentLibrary", id)
                ?: throw ProposalServiceError.DomainError(ProposalErrorCode.UNKNOWN_OBJECT,
                    "library preset $id disappeared during plan")
            decodePresetJson(json)
        }
        val libToken = read.collectionToken(SubagentCollections.LIBRARY)
        val wsIds = read.snapshotIds("room.workspaces").toSet()
        val wsPresets = LinkedHashMap<String, List<WorkspaceSubagentPreset>>()
        val wsTokens = LinkedHashMap<String, VersionToken>()
        val objTokens = HashMap<String, VersionToken>()
        for (id in libIds) {
            objTokens["lib:$id"] = read.objectToken("room.subagentLibrary", id)
                ?: VersionToken(epoch, 0, 0)
        }
        for (wsId in wsIds) {
            val wsJson = read.readObjectJson("room.workspaces", wsId) ?: continue
            val presetsJson = (wsJson["subagentPresets"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.let { runCatching { it.jsonPrimitive.content }?.getOrNull() } ?: "[]"
            val presets = WorkspaceSubagentPresets.tryParse(presetsJson)
                ?: throw ProposalServiceError.DomainError(ProposalErrorCode.INVALID_VALUE,
                    "workspace $wsId has corrupt preset JSON; repair it before management writes")
            wsPresets[wsId] = presets
            wsTokens[wsId] = read.objectToken("room.workspacePresets", wsId)
                ?: VersionToken(epoch, 0, 0)
            presets.forEach { p -> objTokens["ws:$wsId:${p.id}"] = VersionToken(epoch, 0, p.version) }
        }
        return Snapshot(
            libraryToken = libToken,
            libraryPresets = library,
            workspacePresets = wsPresets,
            knownWorkspaceIds = wsIds,
            workspaceTokens = wsTokens,
            objectTokens = objTokens,
            epoch = epoch,
        )
    }

    private fun decodePresetJson(json: JsonObject): WorkspaceSubagentPreset {
        return runCatching {
            me.rerere.rikkihub.utils.JsonInstant.decodeFromJsonElement(
                WorkspaceSubagentPreset.serializer(), json)
        }.getOrElse {
            throw ProposalServiceError.DomainError(ProposalErrorCode.INVALID_VALUE,
                "corrupt library preset payload")
        }.also { WorkspaceSubagentPresets.validate(it) }
    }
}
