package me.rerere.rikkihub.ui.pages.extensions.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.files.SkillMetadata
import java.io.File
import java.nio.file.Files

data class SkillFile(
    val file: File,
    val relativePath: String,
)

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

class SkillDetailVM(
    private val skillManager: SkillManager,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val resultDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private var skillName = ""
    private val _skill = MutableStateFlow<SkillMetadata?>(null)
    val skill = _skill.asStateFlow()
    private val _readOnly = MutableStateFlow(true)
    val readOnly = _readOnly.asStateFlow()

    fun init(name: String) {
        if (skillName == name) return
        skillName = name
        _skill.value = null
        _readOnly.value = true
        _tree.value = emptyList()
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(ioDispatcher) {
            val selection = _skill.value ?: skillManager.findSkill(skillName) ?: return@launch
            _skill.value = selection
            _readOnly.value = !skillManager.canModify(selection)
            _tree.value = if (skillManager.isCurrentSelection(selection)) buildSkillTree(selection.skillDir) else emptyList()
        }
    }

    fun readFile(skillFile: SkillFile): String {
        val selection = _skill.value ?: return ""
        val target = skillManager.resolveSkillFile(selection, skillFile.relativePath) ?: return ""
        return if (target.isFile) target.readText() else ""
    }

    // Returns null on success, error message on failure
    fun saveFile(relativePath: String, content: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val selection = _skill.value
                if (selection == null || !skillManager.canModify(selection)) {
                    _readOnly.value = true
                    withContext(resultDispatcher) { onResult("This skill is read-only or the selected origin changed") }
                    return@launch
                }
                if (relativePath == "SKILL.md") {
                    val name = SkillFrontmatterParser.parse(content)["name"]
                    if (name != skillName) {
                        withContext(resultDispatcher) { onResult("不允许修改技能名称（name 字段必须为 \"$skillName\"）") }
                        return@launch
                    }
                }
                val success = skillManager.saveSkillFile(skillName, relativePath, content, selection)
                if (success) loadFiles()
                withContext(resultDispatcher) { onResult(if (success) null else "保存失败") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 解析/保存过程中的意外异常转为失败反馈，不逃出协程
                withContext(resultDispatcher) { onResult("保存失败") }
            }
        }
    }

    fun deleteFile(skillFile: SkillFile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val selection = _skill.value
            val success = selection != null && skillManager.canModify(selection) &&
                skillManager.deleteSkillFile(skillName, skillFile.relativePath, selection)
            if (success) loadFiles()
            withContext(resultDispatcher) { onResult(success) }
        }
    }
}

/** Skip links (including in-tree aliases), reject escaping roots, and bound directory cycles. */
internal fun buildSkillTree(root: File): List<SkillFileNode> {
    // 根本身是软链仍拒绝（不解引用）；仅父级含平台软链拼写时取 canonical 根继续
    if (Files.isSymbolicLink(root.toPath())) return emptyList()
    val root = root.canonicalFile
    val seen = HashSet<String>()
    fun walk(dir: File): List<SkillFileNode> {
        if (!seen.add(dir.canonicalPath)) return emptyList()
        val items = dir.listFiles().orEmpty().filter {
            !Files.isSymbolicLink(it.toPath()) &&
                it.canonicalFile == it.absoluteFile.normalize() &&
                it.canonicalPath.startsWith(root.canonicalPath + File.separator)
        }
        val dirs = items.filter { it.isDirectory }.sortedBy { it.name }.map {
            SkillFileNode.DirNode(it.name, it.relativeTo(root).path, walk(it))
        }
        val files = items.filter { it.isFile }.sortedWith(compareBy({ it.name != "SKILL.md" }, { it.name })).map {
            SkillFileNode.FileNode(SkillFile(it, it.relativeTo(root).path))
        }
        return dirs + files
    }
    return walk(root)
}
