package me.rerere.rikkihub.data.management.adapters.skills

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.ports.ManagementCommand

/**
 * 用户 Skills 域命令集。
 *
 * 硬约束（adapter 校验，存储层复核）：
 * - 一切写只作用于**用户树**；builtin（`/builtin_skills`）是只读发布资产，
 *   对 builtin 的「修改」必须显式走 [CreateBuiltinOverride]；
 * - 路径全部相对技能根：禁绝对路径/`..`/反斜杠/NUL；
 * - SKILL.md 不可单文件删除/改名（否则留下"宣称有效的损坏 catalog"）；
 * - frontmatter `name` 变化必须走 [RenameSkill]（声明式引用迁移 + 级联
 *   enabledSkills 说明步），不允许 PatchTextFile 偷渡；
 * - 删除用户 override 会重新显露 builtin——enabledSkills 按现存契约保留名称。
 */
@Serializable
sealed interface SkillCommand : ManagementCommand {

    fun ref(id: String) = ObjectRef(ManagementDomain.USER_SKILL, id)

    @Serializable
    data class CreateSkill(
        val dirName: String,
        val skillMd: String,
        val files: Map<String, String> = emptyMap(),
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("skill:$dirName"))
        override fun risk() = RiskLevel.MEDIUM
    }

    @Serializable
    data class CreateBuiltinOverride(
        val skillName: String,
        val skillMd: String,
        val files: Map<String, String> = emptyMap(),
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("skill:$skillName"))
        override fun risk() = RiskLevel.HIGH
    }

    @Serializable
    data class PatchTextFile(
        val skillName: String,
        val path: String,
        val newContent: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("file:$skillName/$path"))
        override fun risk() = RiskLevel.MEDIUM
    }

    @Serializable
    data class CreateFile(
        val skillName: String,
        val path: String,
        val content: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("file:$skillName/$path"))
        override fun risk() = RiskLevel.LOW
    }

    @Serializable
    data class DeleteFile(
        val skillName: String,
        val path: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("file:$skillName/$path"))
        override fun risk() = RiskLevel.HIGH
    }

    @Serializable
    data class RenameFile(
        val skillName: String,
        val fromPath: String,
        val toPath: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("file:$skillName/$fromPath"))
        override fun risk() = RiskLevel.LOW
    }

    @Serializable
    data class CreateDirectory(
        val skillName: String,
        val path: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("dir:$skillName/$path"))
        override fun risk() = RiskLevel.LOW
    }

    @Serializable
    data class DeleteDirectory(
        val skillName: String,
        val path: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("dir:$skillName/$path"))
        override fun risk() = RiskLevel.MEDIUM
    }

    @Serializable
    data class RenameDirectory(
        val skillName: String,
        val fromPath: String,
        val toPath: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("dir:$skillName/$fromPath"))
        override fun risk() = RiskLevel.MEDIUM
    }

    @Serializable
    data class RenameSkill(
        val fromName: String,
        val toName: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("skill:$fromName"), ref("skill:$toName"))
        override fun risk() = RiskLevel.HIGH
    }

    @Serializable
    data class DeleteSkill(
        val skillName: String,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("skill:$skillName"))
        override fun risk() = RiskLevel.HIGH
    }

    /**
     * 恢复：snapshot 由回收 payload 提供（目录清单 + 文件内容 + 遮蔽的
     * builtin 版本）。builtin 版本漂移 → CONFLICT，不自动重遮蔽新版本。
     */
    @Serializable
    data class RestoreSkill(
        val skillName: String,
        val snapshot: SkillTreeSnapshot,
    ) : SkillCommand {
        override val domain = ManagementDomain.USER_SKILL
        override fun affectedRefs() = listOf(ref("skill:$skillName"))
        override fun risk() = RiskLevel.MEDIUM
    }
}

/** 删除时宿主保存的恢复材料（回收 payload 的一部分）。 */
@Serializable
data class SkillTreeSnapshot(
    val dirName: String,
    val files: Map<String, String>,
    /** 删除时遮蔽的 builtin 发布代次（null = 删除前未遮蔽 builtin）。 */
    val shadowedBuiltinRevision: Long?,
)
