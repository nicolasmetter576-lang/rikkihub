package me.rerere.rikkihub.data.management.adapters.skills

import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import me.rerere.rikkihub.data.management.adapters.AdapterBudget
import me.rerere.rikkihub.data.management.adapters.reject
import me.rerere.rikkihub.data.management.adapters.rejectConflict
import me.rerere.rikkihub.data.management.adapters.rejectForbidden
import me.rerere.rikkihub.data.management.adapters.rejectInvalid
import me.rerere.rikkihub.data.management.adapters.rejectNotFound
import me.rerere.rikkihub.data.management.common.DiffLine
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSet
import me.rerere.rikkihub.data.management.common.ReadSetEntry
import me.rerere.rikkihub.data.management.common.RetentionNote
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.coordinator.ManagementDomainAdapter
import me.rerere.rikkihub.data.management.coordinator.PlanContext
import me.rerere.rikkihub.data.management.coordinator.PlannedMutation
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.management.ports.StoreOperation
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode

/**
 * 用户 Skills 域 adapter（`ManagementDomainAdapter` 实现）。
 *
 * - builtin 树只读：builtin 修改必须显式 [SkillCommand.CreateBuiltinOverride]；
 * - SKILL.md 单文件删/改名拒绝（防"宣称有效的损坏 catalog"）；
 * - frontmatter `name` 变化必须走 [SkillCommand.RenameSkill]；
 * - 步骤是 typed `SkillStoreOp`（StoreKind.USER_FILES；enabledSkills 级联步为
 *   SETTINGS_DATASTORE）——生产 gateway = mutation 的 SkillRepository
 *   （PENDING-MUTATION-ACCEPTANCE）。
 */
class SkillAdapter : ManagementDomainAdapter {

    override val domain = ManagementDomain.USER_SKILL

    override suspend fun plan(ctx: PlanContext, command: ManagementCommand): PlannedMutation {
        require(command is SkillCommand) { "SkillAdapter only handles SkillCommand" }
        val snap = SkillReadModel(ctx.read, ctx.storageEpoch).snapshot()
        val reader = SkillReadModel(ctx.read, ctx.storageEpoch)
        return when (command) {
            is SkillCommand.CreateSkill -> planCreateSkill(ctx, command, snap)
            is SkillCommand.CreateBuiltinOverride -> planCreateOverride(command, snap)
            is SkillCommand.PatchTextFile -> planPatchFile(command, snap)
            is SkillCommand.CreateFile -> planCreateFile(command, snap)
            is SkillCommand.DeleteFile -> planDeleteFile(command, snap)
            is SkillCommand.RenameFile -> planRenameFile(command, snap)
            is SkillCommand.CreateDirectory -> planCreateDir(command, snap)
            is SkillCommand.DeleteDirectory -> planDeleteDir(command, snap)
            is SkillCommand.RenameDirectory -> planRenameDir(command, snap)
            is SkillCommand.RenameSkill -> planRenameSkill(command, snap)
            is SkillCommand.DeleteSkill -> planDeleteSkill(command, snap)
            is SkillCommand.RestoreSkill -> planRestoreSkill(command, snap)
        }
    }

    override suspend fun inverse(ctx: PlanContext, command: ManagementCommand): ManagementCommand? =
        when (command) {
            is SkillCommand.CreateSkill -> SkillCommand.DeleteSkill("\$new0")
            is SkillCommand.CreateBuiltinOverride -> SkillCommand.DeleteSkill(command.skillName)
            is SkillCommand.CreateFile ->
                SkillCommand.DeleteFile(command.skillName, command.path)
            is SkillCommand.CreateDirectory ->
                SkillCommand.DeleteDirectory(command.skillName, command.path)
            is SkillCommand.DeleteFile, is SkillCommand.DeleteDirectory,
            is SkillCommand.DeleteSkill, is SkillCommand.PatchTextFile -> null // before-image 在回收 payload
            is SkillCommand.RenameFile ->
                SkillCommand.RenameFile(command.skillName, command.toPath, command.fromPath)
            is SkillCommand.RenameDirectory ->
                SkillCommand.RenameDirectory(command.skillName, command.toPath, command.fromPath)
            is SkillCommand.RenameSkill ->
                SkillCommand.RenameSkill(command.toName, command.fromName)
            is SkillCommand.RestoreSkill -> SkillCommand.DeleteSkill(command.skillName)
            else -> rejectInvalid("unsupported skill command")
        }

    override fun needsRecyclePayload(command: ManagementCommand): Boolean =
        command is SkillCommand.DeleteFile || command is SkillCommand.DeleteDirectory ||
            command is SkillCommand.DeleteSkill || command is SkillCommand.PatchTextFile

    // ---- 计划 ---------------------------------------------------------------------

    private fun planCreateSkill(
        ctx: PlanContext, cmd: SkillCommand.CreateSkill, snap: SkillReadModel.Snapshot,
    ): PlannedMutation {
        if (!SkillPathRules.validateDirName(cmd.dirName))
            rejectInvalid("invalid skill directory name '${cmd.dirName}': 1..64 characters, no / or \\")
        if (snap.entries.any { it.dirName == cmd.dirName })
            rejectConflict("directory '${cmd.dirName}' already exists")
        val name = frontmatterName(cmd.skillMd)
            ?: rejectInvalid("SKILL.md must declare a non-blank frontmatter 'name'")
        if (snap.effective(name) != null)
            rejectConflict("skill name '$name' is already in use")
        validateFiles(cmd.files)
        val ref = ObjectRef(domain, "skill:$name")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf<StoreOperation>(SkillStoreOp.PublishTree(
                dirName = cmd.dirName,
                files = mapOf("SKILL.md" to cmd.skillMd) + cmd.files,
                objectRefs = listOf(ref))),
            diffSummary = listOf(DiffLine("skills.catalog['$name']", null,
                "dir='${cmd.dirName}' files=${cmd.files.size + 1}")),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planCreateOverride(
        cmd: SkillCommand.CreateBuiltinOverride, snap: SkillReadModel.Snapshot,
    ): PlannedMutation {
        val builtin = snap.builtinEntry(cmd.skillName)
            ?: rejectNotFound("no builtin skill named '${cmd.skillName}'")
        if (snap.userEntry(cmd.skillName) != null)
            rejectConflict("user override for '${cmd.skillName}' already exists")
        val name = frontmatterName(cmd.skillMd)
        if (name != cmd.skillName)
            rejectInvalid("override frontmatter name must stay '${cmd.skillName}'")
        validateFiles(cmd.files)
        val ref = ObjectRef(domain, "skill:${cmd.skillName}")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ref, snap.token(cmd.skillName)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.PublishTree(
                dirName = cmd.skillName,
                files = mapOf("SKILL.md" to cmd.skillMd) + cmd.files,
                objectRefs = listOf(ref))),
            diffSummary = listOf(
                DiffLine("skills.catalog['${cmd.skillName}'].origin", "builtin", "user override"),
                DiffLine("skills.catalog['${cmd.skillName}'].SKILL.md",
                    "<builtin ${builtin.skillMd?.sizeBytes ?: 0}B>", "<override ${cmd.skillMd.length} chars>"),
            ),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = true,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planPatchFile(cmd: SkillCommand.PatchTextFile, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.skillName) ?: return userRequired(cmd.skillName, snap)
        if (!SkillPathRules.validatePath(cmd.path)) rejectInvalid("invalid path '${cmd.path}': use a relative path inside the skill like ref/notes.md: no leading /, no .., no backslash, at most 512 characters")
        val file = entry.file(cmd.path) ?: rejectNotFound("file ${cmd.path} not found")
        if (file.isDirectory) rejectInvalid("path is a directory")
        if (cmd.newContent.length > AdapterBudget.MAX_FILE_CHARS)
            reject(ProposalErrorCode.BUDGET_EXCEEDED, "file exceeds ${AdapterBudget.MAX_FILE_CHARS} chars")
        if (cmd.path == "SKILL.md") {
            val newName = frontmatterName(cmd.newContent)
            if (newName != entry.name)
                rejectInvalid("frontmatter 'name' changes require the RenameSkill command")
        }
        val ref = ObjectRef(domain, "file:${entry.name}/${cmd.path}")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "skill:${entry.name}"), snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.PutFile(entry.dirName, cmd.path, cmd.newContent, listOf(ref))),
            diffSummary = listOf(DiffLine("skill['${entry.name}'].${cmd.path}",
                "<${file.sizeBytes}B ${file.digest.take(8)}>", "<${cmd.newContent.length} chars>")),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planCreateFile(cmd: SkillCommand.CreateFile, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.skillName) ?: return userRequired(cmd.skillName, snap)
        if (!SkillPathRules.validatePath(cmd.path)) rejectInvalid("invalid path '${cmd.path}': use a relative path inside the skill like ref/notes.md: no leading /, no .., no backslash, at most 512 characters")
        if (entry.file(cmd.path) != null) rejectConflict("file ${cmd.path} already exists")
        if (cmd.content.length > AdapterBudget.MAX_FILE_CHARS)
            reject(ProposalErrorCode.BUDGET_EXCEEDED, "file exceeds size limit")
        val ref = ObjectRef(domain, "file:${entry.name}/${cmd.path}")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "skill:${entry.name}"), snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.PutFile(entry.dirName, cmd.path, cmd.content, listOf(ref))),
            diffSummary = listOf(DiffLine("skill['${entry.name}'].${cmd.path}", null,
                "<${cmd.content.length} chars>")),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planDeleteFile(cmd: SkillCommand.DeleteFile, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.skillName) ?: return userRequired(cmd.skillName, snap)
        if (!SkillPathRules.validatePath(cmd.path)) rejectInvalid("invalid path '${cmd.path}': use a relative path inside the skill like ref/notes.md: no leading /, no .., no backslash, at most 512 characters")
        if (cmd.path == "SKILL.md")
            rejectForbidden("deleting SKILL.md would corrupt the catalog entry; use DeleteSkill")
        val file = entry.file(cmd.path) ?: rejectNotFound("file not found")
        if (file.isDirectory) rejectInvalid("path is a directory; use DeleteDirectory")
        val ref = ObjectRef(domain, "file:${entry.name}/${cmd.path}")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "skill:${entry.name}"), snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.DeleteFile(entry.dirName, cmd.path, listOf(ref))),
            diffSummary = listOf(DiffLine("skill['${entry.name}'].${cmd.path}",
                "<${file.sizeBytes}B>", null)),
            risk = cmd.risk(), retention = RetentionNote(recycleDays = 30),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = true,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planRenameFile(cmd: SkillCommand.RenameFile, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.skillName) ?: return userRequired(cmd.skillName, snap)
        if (!SkillPathRules.validatePath(cmd.fromPath) || !SkillPathRules.validatePath(cmd.toPath))
            rejectInvalid("invalid path")
        if (cmd.fromPath == "SKILL.md" || cmd.toPath == "SKILL.md")
            rejectForbidden("SKILL.md cannot be renamed (catalog anchor)")
        if (entry.file(cmd.fromPath)?.isDirectory != false)
            rejectNotFound("source file not found (or is a directory)")
        if (entry.file(cmd.toPath) != null) rejectConflict("target path already exists")
        val ref = ObjectRef(domain, "file:${entry.name}/${cmd.fromPath}")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "skill:${entry.name}"), snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.RenamePath(entry.dirName, cmd.fromPath, cmd.toPath,
                isDirectory = false, objectRefs = listOf(ref))),
            diffSummary = listOf(DiffLine("skill['${entry.name}'].path", cmd.fromPath, cmd.toPath)),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planCreateDir(cmd: SkillCommand.CreateDirectory, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.skillName) ?: return userRequired(cmd.skillName, snap)
        if (!SkillPathRules.validatePath(cmd.path, allowDir = true)) rejectInvalid("invalid directory path")
        val norm = SkillPathRules.normalize(cmd.path)
        if (entry.file(norm) != null || entry.file("$norm/") != null)
            rejectConflict("path already exists")
        val ref = ObjectRef(domain, "dir:${entry.name}/$norm")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "skill:${entry.name}"), snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            // 目录是隐式清单项；生产 gateway 物化目录占位（内容写时 mkdirs）。
            steps = listOf(SkillStoreOp.PutFile(entry.dirName, "$norm/.keep",
                "", listOf(ref), summaryKey = "skill.mkdir")),
            diffSummary = listOf(DiffLine("skill['${entry.name}'].$norm/", null, "directory")),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planDeleteDir(cmd: SkillCommand.DeleteDirectory, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.skillName) ?: return userRequired(cmd.skillName, snap)
        val norm = SkillPathRules.normalize(cmd.path)
        entry.file("$norm/") ?: rejectNotFound("directory not found")
        val children = entry.files.filter { it.path.startsWith("$norm/") && it.path != "$norm/" }
        if (children.isNotEmpty()) rejectInvalid("directory is not empty (${children.size} entries)")
        val ref = ObjectRef(domain, "dir:${entry.name}/$norm")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "skill:${entry.name}"), snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.DeleteFile(entry.dirName, "$norm/.keep",
                listOf(ref), summaryKey = "skill.rmdir")),
            diffSummary = listOf(DiffLine("skill['${entry.name}'].$norm/", "directory", null)),
            risk = cmd.risk(), retention = RetentionNote(recycleDays = 30),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planRenameDir(cmd: SkillCommand.RenameDirectory, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.skillName) ?: return userRequired(cmd.skillName, snap)
        val from = SkillPathRules.normalize(cmd.fromPath)
        val to = SkillPathRules.normalize(cmd.toPath)
        if (!SkillPathRules.validatePath("$from/", allowDir = true) ||
            !SkillPathRules.validatePath("$to/", allowDir = true))
            rejectInvalid("invalid directory path")
        if (entry.file("$from/") == null && entry.files.none { it.path.startsWith("$from/") })
            rejectNotFound("source directory not found")
        if (to.startsWith("$from/") || entry.file("$to/") != null ||
            entry.files.any { it.path.startsWith("$to/") })
            rejectConflict("target directory occupied or nested in source")
        val ref = ObjectRef(domain, "dir:${entry.name}/$from")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ObjectRef(domain, "skill:${entry.name}"), snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.RenamePath(entry.dirName, "$from/", "$to/",
                isDirectory = true, objectRefs = listOf(ref), summaryKey = "skill.rename_dir")),
            diffSummary = listOf(DiffLine("skill['${entry.name}'].dir", "$from/", "$to/")),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planRenameSkill(cmd: SkillCommand.RenameSkill, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.fromName) ?: return userRequired(cmd.fromName, snap)
        if (!SkillPathRules.validateDirName(cmd.toName)) rejectInvalid("invalid new name '${cmd.toName}': 1..64 characters, no / or \\")
        if (snap.effective(cmd.toName) != null)
            rejectConflict("name '${cmd.toName}' is already in use")
        val fromRef = ObjectRef(domain, "skill:${cmd.fromName}")
        val toRef = ObjectRef(domain, "skill:${cmd.toName}")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(fromRef, snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(
                SkillStoreOp.RenameSkillTree(entry.dirName, cmd.toName, cmd.toName,
                    listOf(fromRef, toRef)),
                SkillStoreOp.CascadeEnabledSkillsRename(cmd.fromName, cmd.toName, listOf(fromRef)),
            ),
            diffSummary = listOf(
                DiffLine("skills.catalog.name", cmd.fromName, cmd.toName),
                DiffLine("assistants[].enabledSkills", "'${cmd.fromName}'", "'${cmd.toName}'"),
            ),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = listOf(ObjectRef(ManagementDomain.ASSISTANT, "enabledSkills")),
            requiresEnhancedConfirm = true,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planDeleteSkill(cmd: SkillCommand.DeleteSkill, snap: SkillReadModel.Snapshot): PlannedMutation {
        val entry = snap.userEntry(cmd.skillName) ?: return userRequired(cmd.skillName, snap)
        val shadowed = snap.isShadowed(entry.name)
        val ref = ObjectRef(domain, "skill:${entry.name}")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Object(ref, snap.token(entry.name)),
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.DeleteTree(entry.dirName, entry.name, listOf(ref))),
            diffSummary = buildList {
                add(DiffLine("skills.catalog['${entry.name}']",
                    "user tree ${entry.files.size} entries", null))
                if (shadowed) add(DiffLine("skills.catalog['${entry.name}'].origin",
                    "user override", "builtin (revealed)"))
            },
            risk = cmd.risk(), retention = RetentionNote(recycleDays = 30),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = true,
            hostAssignedIds = emptyMap(),
        )
    }

    private fun planRestoreSkill(cmd: SkillCommand.RestoreSkill, snap: SkillReadModel.Snapshot): PlannedMutation {
        if (snap.effective(cmd.skillName) != null)
            rejectConflict("skill '${cmd.skillName}' still exists")
        if (!SkillPathRules.validateDirName(cmd.snapshot.dirName))
            rejectInvalid("invalid snapshot directory name")
        if (snap.entries.any { it.dirName == cmd.snapshot.dirName })
            rejectConflict("directory '${cmd.snapshot.dirName}' is occupied")
        // builtin 版本匹配：删除时遮蔽的 builtin 已变化 → CONFLICT，不自动重遮蔽。
        cmd.snapshot.shadowedBuiltinRevision?.let { deletedAt ->
            val current = snap.entries.firstOrNull { it.name == cmd.skillName && it.isBuiltin }
            if (current == null)
                rejectConflict("builtin '${cmd.skillName}' no longer exists")
            // 生产读口的 builtin revision 即 builtin 发布代次
            if (current.revision != deletedAt)
                rejectConflict("builtin assets changed since deletion ($deletedAt → ${current.revision}); " +
                    "re-create the override explicitly")
        }
        val name = frontmatterName(cmd.snapshot.files["SKILL.md"] ?: "")
            ?: rejectInvalid("snapshot has no valid SKILL.md")
        if (name != cmd.skillName) rejectInvalid("snapshot name '$name' does not match restore target")
        validateFiles(cmd.snapshot.files - "SKILL.md")
        val ref = ObjectRef(domain, "skill:${cmd.skillName}")
        return PlannedMutation(
            readSet = ReadSet(listOf(
                ReadSetEntry.Collection(SkillCollections.CATALOG, snap.catalogToken))),
            steps = listOf(SkillStoreOp.PublishTree(cmd.snapshot.dirName, cmd.snapshot.files, listOf(ref))),
            diffSummary = listOf(DiffLine("skills.catalog['${cmd.skillName}']", null,
                "restored ${cmd.snapshot.files.size} files")),
            risk = cmd.risk(), retention = RetentionNote(),
            cascadeTargets = emptyList(), requiresEnhancedConfirm = false,
            hostAssignedIds = emptyMap(),
        )
    }

    // ---- helpers -------------------------------------------------------------------

    private fun userRequired(name: String, snap: SkillReadModel.Snapshot): Nothing {
        val entry = snap.effective(name) ?: rejectNotFound("skill '$name' not found")
        if (entry.isBuiltin) rejectForbidden(
            "builtin skill '$name' is read-only; create an explicit builtin override first")
        rejectNotFound("skill '$name' not found")
    }

    private fun frontmatterName(content: String): String? =
        SkillFrontmatterParser.parse(content)["name"]?.takeIf { it.isNotBlank() }

    private fun validateFiles(files: Map<String, String>) {
        if (files.size > AdapterBudget.MAX_TARGETS)
            reject(ProposalErrorCode.BUDGET_EXCEEDED, "too many files")
        files.forEach { (path, content) ->
            if (!SkillPathRules.validatePath(path)) rejectInvalid("invalid path '$path'")
            if (content.length > AdapterBudget.MAX_FILE_CHARS)
                reject(ProposalErrorCode.BUDGET_EXCEEDED, "file '$path' too large")
        }
    }
}
