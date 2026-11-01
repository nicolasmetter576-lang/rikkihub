package me.rerere.rikkihub.ui.pages.extensions.skills

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.files.FileUtils
import me.rerere.rikkihub.data.files.InvalidSkillDir
import me.rerere.rikkihub.data.files.SkillImportException
import me.rerere.rikkihub.data.files.SkillImportService
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.files.SkillMetadata

class SkillsVM(
    private val context: Context,
    private val skillManager: SkillManager,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        loadSkills()
    }

    private val _invalidSkillDirs = MutableStateFlow<List<InvalidSkillDir>>(emptyList())
    val invalidSkillDirs = _invalidSkillDirs.asStateFlow()

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            refresh()
        }
    }

    private fun refresh() {
        _skills.value = skillManager.listSkills()
        _invalidSkillDirs.value = skillManager.listInvalidSkillDirs()
    }

    fun deleteInvalidSkillDir(dirName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteInvalidSkillDir(dirName)
            refresh()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            refresh()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(selection: SkillMetadata) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!skillManager.canModify(selection)) return@launch
            skillManager.deleteSkill(selection.name, selection)
            refresh()
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    private val importService = SkillImportService(skillManager)

    fun importSkillFromFile(context: Context, uri: Uri, onResult: (Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = FileUtils.getFileNameFromUri(appContext, uri).orEmpty()
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "无法读取文件") }
                        return@launch
                    }
                val importedNames = importService.importFile(fileName, bytes)
                refresh()
                withContext(Dispatchers.Main) {
                    onResult(true, importedNames.joinToString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "未知错误") }
            }
        }
    }

    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = importService.importGitHub(repoUrl)
                refresh()
                withContext(Dispatchers.Main) { onResult(true, name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SkillImportException) {
                val message = if (e.code == SkillImportException.CODE_GITHUB_RATE_LIMIT) {
                    context.getString(R.string.skills_github_rate_limit)
                } else {
                    e.message ?: "未知错误"
                }
                withContext(Dispatchers.Main) { onResult(false, message) }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "未知错误") }
            }
        }
    }
}
