package me.rerere.rikkihub.data.management.adapters.skills

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.ports.StoreKind
import me.rerere.rikkihub.data.management.ports.StoreOperation

/**
 * Skills 域存储步骤（StoreKind.USER_FILES）。
 *
 * 生产 gateway 兑现到 mutation 的 `SkillRepository`（统一锁 + catalog CAS +
 * 原子发布 staging+rename + receipt）；所有路径已在 adapter 层经
 * [SkillPathRules] 校验，gateway 侧仍须复核（不信任上游）。
 */
@Serializable
sealed interface SkillStoreOp : StoreOperation {
    override val store: StoreKind get() = StoreKind.USER_FILES

    /** 原子发布整技能树（含 SKILL.md）。 */
    @Serializable
    data class PublishTree(
        val dirName: String,
        /** path → UTF-8 内容；必须含 SKILL.md。 */
        val files: Map<String, String>,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "skill.publish_tree",
    ) : SkillStoreOp

    /** 覆盖写单文件（原子 tmp+rename）。 */
    @Serializable
    data class PutFile(
        val dirName: String,
        val path: String,
        val content: String,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "skill.put_file",
    ) : SkillStoreOp

    @Serializable
    data class DeleteFile(
        val dirName: String,
        val path: String,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "skill.delete_file",
    ) : SkillStoreOp

    /** 重命名路径（单文件或整目录前缀迁移）。 */
    @Serializable
    data class RenamePath(
        val dirName: String,
        val fromPath: String,
        val toPath: String,
        /** true = 目录（所有 `fromPath/` 前缀迁移）。 */
        val isDirectory: Boolean,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "skill.rename_path",
    ) : SkillStoreOp

    /** 整技能目录改名 + frontmatter name 迁移（同事务性发布）。 */
    @Serializable
    data class RenameSkillTree(
        val fromDir: String,
        val toDir: String,
        val newName: String,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "skill.rename_tree",
    ) : SkillStoreOp

    /** 删除用户树（builtin 不在删除面内）。 */
    @Serializable
    data class DeleteTree(
        val dirName: String,
        val effectiveName: String,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "skill.delete_tree",
    ) : SkillStoreOp

    /**
     * Settings 域级联步（enabledSkills 名称迁移）——由 coordinator 交给
     * settings gateway；本域只声明、不执行。
     */
    @Serializable
    data class CascadeEnabledSkillsRename(
        val fromName: String,
        val toName: String,
        override val objectRefs: List<ObjectRef>,
        override val summaryKey: String = "skill.cascade.enabled_skills",
    ) : SkillStoreOp {
        override val store: StoreKind get() = StoreKind.SETTINGS_DATASTORE
    }
}

/** read-set collectionId 约定。 */
object SkillCollections {
    const val CATALOG = "files.skills.catalog"
    fun manifest(name: String) = "files.skill.$name.manifest"
    fun file(skillName: String, path: String) = "files.skill.$skillName.file.$path"
    fun content(dirName: String, path: String) = "files.skillFile.$dirName.$path"
}

object SkillPathRules {
    /** 目录名：不含分隔符/点段/控制字符，长度受限。 */
    fun validateDirName(name: String): Boolean =
        name.isNotBlank() && name.length <= 64 &&
            !name.contains('/') && !name.contains('\\') &&
            name != "." && name != ".." && name.none { it < ' ' }

    /** POSIX 相对路径：无空段/`..`/绝对路径/NUL/反斜杠；`path` 以 `/` 结尾表示目录项。 */
    fun validatePath(path: String, allowDir: Boolean = false): Boolean {
        if (path.isBlank() || path.length > 512) return false
        if (path.startsWith('/') || path.contains('\\') || '\u0000' in path) return false
        if (path.endsWith('/') && !allowDir) return false
        val stripped = path.trimEnd('/')
        if (stripped.isEmpty()) return false
        return stripped.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.length <= 128 }
    }

    fun normalize(path: String): String = path.trimEnd('/')
}
