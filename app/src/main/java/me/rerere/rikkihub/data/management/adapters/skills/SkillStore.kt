package me.rerere.rikkihub.data.management.adapters.skills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkihub.data.management.common.VersionToken
import me.rerere.rikkihub.data.management.ports.ManagementReadPort
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode
import me.rerere.rikkihub.data.management.proposal.ProposalServiceError

/**
 * Skills 域的读模型（经 `ManagementReadPort` 物化）。
 *
 * 生产读口期望（PENDING-MUTATION-CONTRACT，见 from-c-requests.md）：
 * - `snapshotIds("files.skills.catalog")` → 有效 catalog 的**有效名**列表
 *   （user 遮蔽 builtin 后只剩一项）；
 * - `readObjectJson("files.skills.catalog", name)` →
 *   `{name, dirName, origin:"user"|"builtin", files:[{path,digest,sizeBytes,isDirectory}],
 *     revision, generation, shadowedBuiltinRevision}`；
 *   `shadowedBuiltinRevision` 非 null 表示该 user 项遮蔽了 builtin（其值即 builtin 代次）；
 * - `readObjectJson("files.skillFile", "<dirName>/<path>")` →
 *   `{content: String}`（白名单正文投影，预算截断由 adapter 控制）；
 * - `collectionToken("files.skills.catalog")` → catalog 集合 token；
 * - `objectToken("files.skills.catalog", name)` → 有效项 token。
 */
class SkillReadModel(private val read: ManagementReadPort, private val epoch: Long) {

    data class Snapshot(
        val catalogToken: VersionToken,
        val entries: List<SkillCatalogEntry>,
        private val objectTokens: Map<String, VersionToken>,
        val epoch: Long,
    ) {
        /** 有效 catalog 项（遮蔽后唯一生效者）。 */
        fun effective(name: String): SkillCatalogEntry? = entries.firstOrNull { it.name == name }
        fun userEntry(name: String): SkillCatalogEntry? = effective(name)?.takeIf { !it.isBuiltin }
        fun builtinEntry(name: String): SkillCatalogEntry? = effective(name)?.takeIf { it.isBuiltin }

        /** 该有效项遮蔽着 builtin（snapshot 内 user 项带 shadowedBuiltinRevision 标记）。 */
        fun isShadowed(name: String): Boolean =
            entries.any { it.name == name && !it.isBuiltin && it.shadowedBuiltinRevision != null }

        fun token(name: String): VersionToken =
            objectTokens[name] ?: VersionToken(epoch, 0, 0)
    }

    suspend fun snapshot(): Snapshot {
        val ids = read.snapshotIds(SkillCollections.CATALOG)
        val entries = ids.map { id ->
            val json = read.readObjectJson(SkillCollections.CATALOG, id)
                ?: throw ProposalServiceError.DomainError(ProposalErrorCode.UNKNOWN_OBJECT,
                    "skill catalog entry $id disappeared during plan")
            decodeEntry(json)
        }
        val tokens = ids.associateWith { id ->
            read.objectToken(SkillCollections.CATALOG, id) ?: VersionToken(epoch, 0, 0)
        }
        return Snapshot(
            catalogToken = read.collectionToken(SkillCollections.CATALOG),
            entries = entries,
            objectTokens = tokens,
            epoch = epoch,
        )
    }

    /** 读文件正文（null = 不存在）。 */
    suspend fun fileContent(dirName: String, path: String): String? {
        val json = read.readObjectJson(SkillCollections.CATALOG, "file:$dirName/$path")
            ?: return null
        return (json["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    private fun decodeEntry(json: JsonObject): SkillCatalogEntry {
        fun str(key: String) = (json[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        fun lng(key: String) = (json[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
        val name = str("name") ?: throw ProposalServiceError.DomainError(
            ProposalErrorCode.INVALID_VALUE, "skill entry missing name")
        val files = (json["files"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            SkillFileEntry(
                path = (o["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null,
                digest = (o["digest"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                sizeBytes = (o["sizeBytes"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0,
                isDirectory = (o["isDirectory"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
            )
        }.orEmpty()
        return SkillCatalogEntry(
            name = name,
            dirName = str("dirName") ?: name,
            isBuiltin = str("origin") == "builtin",
            files = files,
            revision = lng("revision") ?: 0,
            generation = lng("generation") ?: 0,
            shadowedBuiltinRevision = lng("shadowedBuiltinRevision"),
        )
    }
}

data class SkillCatalogEntry(
    val name: String,
    val dirName: String,
    val isBuiltin: Boolean,
    val files: List<SkillFileEntry>,
    val revision: Long,
    val generation: Long,
    /** 非 null：该 user 项遮蔽的 builtin 发布代次。 */
    val shadowedBuiltinRevision: Long?,
) {
    fun file(path: String): SkillFileEntry? = files.firstOrNull { it.path == path }
    val skillMd: SkillFileEntry? get() = file("SKILL.md")
}

data class SkillFileEntry(
    val path: String,
    val digest: String,
    val sizeBytes: Long,
    val isDirectory: Boolean = false,
)
