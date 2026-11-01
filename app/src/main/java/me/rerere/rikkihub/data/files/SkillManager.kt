package me.rerere.rikkihub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.datastore.SettingsStore

class SkillManager internal constructor(
    filesRoot: File,
    private val ensureBuiltins: () -> File,
    private val pruneEnabled: suspend (Set<String>) -> Unit,
) {
    /**
     * 平台给的 filesDir 路径拼写可能带软链父级：app 的 mount namespace 里
     * `/data/user/0` 是指向 `/data/data` 的软链（root/shell 看不到）。先 canonicalize
     * 一次再当根，下游 `canonicalFile == absoluteFile.normalize()` 校验才不会误伤；
     * 根/子项本身是逃逸软链的拒绝逻辑全部保留。
     */
    private val filesRoot: File = filesRoot.canonicalFile

    constructor(context: Context, settingsStore: SettingsStore) : this(
        context.filesDir,
        { BuiltinSkills.ensure(context) },
        { available ->
            settingsStore.update { settings ->
                val assistants = settings.assistants.map { assistant ->
                    val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in available }
                    if (pruned == assistant.enabledSkills) assistant else assistant.copy(enabledSkills = pruned)
                }
                if (assistants == settings.assistants) settings else settings.copy(assistants = assistants)
            }
        },
    )
    companion object {
        private const val TAG = "SkillManager"
    }

    fun getSkillsDir(): File {
        val dir = filesRoot.resolve(FileFolders.SKILLS)
        require(dir.canonicalFile == dir.absoluteFile.normalize()) { "Unsafe user skills root" }
        if (!dir.exists()) check(dir.mkdirs())
        return dir
    }

    fun listSkills(): List<SkillMetadata> = BuiltinSkillPublisher.locked(filesRoot) {
        val users = listCatalog(getSkillsDir(), false)
        // Failure is NOT cached: next catalog/read retries. Old builtins remain usable.
        val builtinRoot = runCatching { ensureBuiltins() }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "Builtin publication failed; will retry", it)
            File(filesRoot, FileFolders.BUILTIN_SKILLS)
        }
        (users + listCatalog(builtinRoot, true)).distinctBy { it.name }
    }

    private fun listCatalog(root: File, builtin: Boolean): List<SkillMetadata> {
        if (root.canonicalFile != root.absoluteFile.normalize()) return emptyList()
        return listSkillDirs(root).sortedBy { it.name }.mapNotNull { dir ->
            val skillFile = SkillPaths.resolveSkillFile(dir, "SKILL.md") ?: return@mapNotNull null
            if (!skillFile.isFile) return@mapNotNull null
            parseSkillFile(skillFile, dir, builtin)
        }
    }

    fun findSkill(name: String): SkillMetadata? = listSkills().firstOrNull { it.name == name }

    /** Names in the APK-shipped catalog, whether or not a user skill overrides them. */
    fun builtinSkillNames(): Set<String> = BuiltinSkillPublisher.locked(filesRoot) {
        val builtinRoot = runCatching { ensureBuiltins() }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            File(filesRoot, FileFolders.BUILTIN_SKILLS)
        }
        listCatalog(builtinRoot, true).mapTo(LinkedHashSet()) { it.name }
    }

    /**
     * Regular files of [selection], same rules as the app's file tree: no symlinks, nothing outside
     * the skill directory, directory cycles bounded. Paths use '/'. Null when the selection is stale.
     */
    fun listSkillFiles(selection: SkillMetadata, limit: Int = 2000): List<SkillFileEntry>? {
        if (!isCurrentSelection(selection)) return null
        val rootPath = selection.skillDir.toPath()
        if (java.nio.file.Files.isSymbolicLink(rootPath)) return emptyList()
        val root = selection.skillDir.canonicalFile
        val seen = HashSet<String>()
        val result = mutableListOf<SkillFileEntry>()
        fun walk(dir: File) {
            if (!seen.add(dir.canonicalPath) || result.size >= limit) return
            val items = dir.listFiles().orEmpty().filter {
                !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                    it.canonicalFile == it.absoluteFile.normalize() &&
                    it.canonicalPath.startsWith(root.canonicalPath + File.separator)
            }.sortedBy { it.name }
            items.filter { it.isFile }.forEach { file ->
                if (result.size < limit) {
                    result += SkillFileEntry(file.relativeTo(root).invariantSeparatorsPath, file.length(), file)
                }
            }
            items.filter { it.isDirectory }.forEach(::walk)
        }
        walk(root)
        return result
    }

    /** Copies a builtin skill into user storage so it can be edited; the copy overrides the builtin. */
    fun copyBuiltinToUser(name: String): SkillMetadata? {
        val selection = findSkill(name)?.takeIf { it.isBuiltin } ?: return null
        val files = listSkillFiles(selection) ?: return null
        if (!saveSkillFileBytesAtomically(name, files.associate { it.path to it.file.readBytes() })) return null
        return findSkill(name)?.takeUnless { it.isBuiltin }
    }

    /** Revalidate origin AND directory; never turn a stale user selection into a builtin edit. */
    fun isCurrentSelection(selection: SkillMetadata): Boolean {
        val current = findSkill(selection.name) ?: return false
        return current.isBuiltin == selection.isBuiltin &&
            current.skillDir.canonicalFile == selection.skillDir.canonicalFile
    }

    fun canModify(selection: SkillMetadata): Boolean = !selection.isBuiltin && isCurrentSelection(selection)

    private fun userSelection(name: String, expected: SkillMetadata? = null): SkillMetadata? {
        val selection = expected ?: findSkill(name) ?: return null
        return selection.takeIf { it.name == name && canModify(it) }
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillFile(skillName, "SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillFile(skillName, "SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveUserTarget(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String, expected: SkillMetadata? = null): Boolean = withContext(Dispatchers.IO) {
        val selection = userSelection(name, expected) ?: return@withContext false
        val deleted = deleteUserTree(selection.skillDir)
        // Deleting a user override reveals the builtin; keep that name enabled.
        if (deleted) pruneEnabled(listSkills().mapTo(HashSet()) { it.name })
        deleted
    }

    private fun deleteUserTree(root: File): Boolean = runCatching {
        require(root.canonicalFile == root.absoluteFile.normalize())
        java.nio.file.Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) }
        }
        true
    }.getOrDefault(false)

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        pruneEnabled(skills.mapTo(HashSet()) { it.name })
        skills
    }

    fun getSkillDir(skillName: String): File? = findSkill(skillName)?.skillDir

    /**
     * 用户技能目录下无法识别为技能的目录：空目录、缺 SKILL.md、frontmatter 缺 name 或 description、读取失败。
     * 它们不会出现在 [listSkills] 里，单独列出来让用户清理。隐藏目录（导入时的临时目录）和软链不列。
     */
    fun listInvalidSkillDirs(): List<InvalidSkillDir> =
        listSkillDirs(getSkillsDir()).sortedBy { it.name }.mapNotNull { dir ->
            invalidReason(dir)?.let { InvalidSkillDir(dirName = dir.name, reason = it) }
        }

    /** 删除 [dirName] 这个无法识别的目录；目录名只在用户技能目录的直接子目录里找，且删之前再核对一次仍然无效。 */
    suspend fun deleteInvalidSkillDir(dirName: String): Boolean = withContext(Dispatchers.IO) {
        val dir = listSkillDirs(getSkillsDir()).firstOrNull { it.name == dirName } ?: return@withContext false
        // 列表展示期间目录可能已经在工作区里补全成有效技能
        if (invalidReason(dir) == null) return@withContext false
        deleteUserTree(dir)
    }

    private fun invalidReason(dir: File): InvalidSkillReason? {
        val skillFile = SkillPaths.resolveSkillFile(dir, "SKILL.md")?.takeIf { it.isFile }
            ?: return InvalidSkillReason.MissingSkillMd
        val frontmatter = runCatching { SkillFrontmatterParser.parse(skillFile.readText()) }
            .getOrElse {
                Log.w(TAG, "invalidReason: Failed to read ${skillFile.absolutePath}", it)
                return InvalidSkillReason.ReadFailed
            }
        if (frontmatter["name"].isNullOrBlank()) return InvalidSkillReason.MissingName
        if (frontmatter["description"].isNullOrBlank()) return InvalidSkillReason.MissingDescription
        return null
    }

    /**
     * 单文件保存：临时文件 + fsync + rename 替换原件。失败返回 false 且不损坏
     * 原文件；协程取消正常传播（见 [writeSkillFileTextAtomically]）。
     */
    suspend fun saveSkillFile(
        skillName: String, relativePath: String, content: String, expected: SkillMetadata? = null,
    ): Boolean {
        return try {
            val skillDir = userSelection(skillName, expected)?.skillDir ?: return false
            val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
            writeSkillFileTextAtomically(target, content)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFile: Failed to save $skillName/$relativePath", e)
            false
        }
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveUserTarget(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.let { deleteUserTree(it) }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                deleteUserTree(stagingDir)
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                deleteUserTree(backupDir)
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String, expected: SkillMetadata? = null): Boolean {
        val skillDir = userSelection(skillName, expected)?.skillDir ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? =
        findSkill(skillName)?.let { resolveSkillFile(it, relativePath) }

    fun resolveSkillFile(selection: SkillMetadata, relativePath: String): File? {
        if (!isCurrentSelection(selection)) return null
        return SkillPaths.resolveSkillFile(selection.skillDir, relativePath)
    }

    // Imports/new skills always publish to USER storage, including explicit builtin overrides.
    private fun resolveUserTarget(name: String): File? {
        val user = listCatalog(getSkillsDir(), false).firstOrNull { it.name == name }
        return user?.skillDir ?: SkillPaths.resolveSkillDir(getSkillsDir(), name)
    }

    private fun createTempSkillDir(skillsRoot: File, suffix: String): File? = runCatching {
        // Do not put an untrusted metadata name into a filesystem path. Unique and hidden.
        java.nio.file.Files.createTempDirectory(skillsRoot.toPath(), ".skill-$suffix-").toFile()
    }.getOrNull()

    private fun parseSkillFile(skillFile: File, skillDir: File, builtin: Boolean = false): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                skillDir = skillDir,
                isBuiltin = builtin,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val skillDir: File,
    val isBuiltin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

/** 用户技能目录下无法识别为技能的目录。 */
data class InvalidSkillDir(val dirName: String, val reason: InvalidSkillReason)

enum class InvalidSkillReason { MissingSkillMd, MissingName, MissingDescription, ReadFailed }

data class SkillFileEntry(val path: String, val size: Long, val file: File)
