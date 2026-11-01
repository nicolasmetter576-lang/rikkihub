package me.rerere.rikkihub.data.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Bounds for one import. The app imports local files the user picked and stays unbounded. */
data class SkillImportLimits(
    val maxTotalBytes: Long,
    val maxFiles: Int,
    val maxFileBytes: Long,
) {
    companion object {
        val UNLIMITED = SkillImportLimits(Long.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE)

        /** Network uploads: a zip bomb must not exhaust the phone's memory. */
        val WEB = SkillImportLimits(maxTotalBytes = 64L * 1024 * 1024, maxFiles = 1000, maxFileBytes = 16L * 1024 * 1024)
    }
}

/**
 * Import failure with a stable [code] for the web; [message] keeps the app's existing wording.
 * [names] lists the conflicting skills for [CODE_EXISTS].
 */
class SkillImportException(
    val code: String,
    message: String,
    val names: List<String> = emptyList(),
) : Exception(message) {
    companion object {
        const val CODE_INVALID = "SKILL_INVALID"
        const val CODE_TOO_LARGE = "SKILL_TOO_LARGE"
        const val CODE_EXISTS = "SKILL_EXISTS"
        const val CODE_SAVE_FAILED = "SKILL_SAVE_FAILED"
        const val CODE_GITHUB_URL = "GITHUB_URL_INVALID"
        const val CODE_GITHUB_FAILED = "GITHUB_DOWNLOAD_FAILED"
        const val CODE_GITHUB_RATE_LIMIT = "GITHUB_RATE_LIMIT"
    }
}

private val GITHUB_URL_REGEX = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")

/**
 * zip / SKILL.md / GitHub import shared by the app's skills page and the web.
 * Moved from SkillsVM unchanged; [SkillImportLimits] and [overwrite] are the only additions.
 */
class SkillImportService(
    private val skillManager: SkillManager,
    private val download: (String) -> ByteArray? = ::downloadGitHubBytes,
) {
    /** Imports a zip (one or more skills) or a single SKILL.md; returns the imported names. */
    fun importFile(
        fileName: String,
        bytes: ByteArray,
        limits: SkillImportLimits = SkillImportLimits.UNLIMITED,
        overwrite: Boolean = true,
    ): List<String> {
        if (bytes.size > limits.maxTotalBytes) tooLarge()
        return if (isZipFile(fileName, bytes)) importZip(bytes, limits, overwrite) else importMarkdown(bytes, overwrite)
    }

    /** Imports one skill directory from a github.com tree URL; returns its name. */
    fun importGitHub(
        repoUrl: String,
        limits: SkillImportLimits = SkillImportLimits.UNLIMITED,
        overwrite: Boolean = true,
    ): String {
        val info = parseGitHubUrl(repoUrl)
            ?: throw SkillImportException(SkillImportException.CODE_GITHUB_URL, "无效的 GitHub 仓库链接")
        val files = mutableListOf<Pair<String, String>>()
        if (!listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files, limits)) {
            throw SkillImportException(SkillImportException.CODE_GITHUB_FAILED, "读取 GitHub 目录失败")
        }
        val skillMdEntry = files.find { it.first == "SKILL.md" }
            ?: throw SkillImportException(SkillImportException.CODE_INVALID, "目录中未找到 SKILL.md")
        val skillMdBytes = downloadBounded(skillMdEntry.second, limits)
            ?: throw SkillImportException(SkillImportException.CODE_GITHUB_FAILED, "下载 SKILL.md 失败，请检查链接或网络")
        // 只有 SKILL.md 需要解码做 frontmatter 解析，其余文件按字节原样保存
        val frontmatter = SkillFrontmatterParser.parse(skillMdBytes.toString(Charsets.UTF_8))
        val name = frontmatter["name"]
        if (name.isNullOrBlank()) {
            throw SkillImportException(SkillImportException.CODE_INVALID, "SKILL.md 格式错误：缺少 name 字段")
        }
        checkConflicts(listOf(name), overwrite)
        // 按字节下载保存，避免图片等二进制附属文件被当作 UTF-8 文本解码而损坏
        val fileContents = LinkedHashMap<String, ByteArray>()
        var total = 0L
        for ((relativePath, downloadUrl) in files) {
            val content = if (relativePath == "SKILL.md") skillMdBytes else downloadBounded(downloadUrl, limits)
            content ?: throw SkillImportException(SkillImportException.CODE_GITHUB_FAILED, "下载文件失败：$relativePath")
            total += content.size
            if (total > limits.maxTotalBytes) tooLarge()
            fileContents[relativePath] = content
        }
        if (!skillManager.saveSkillFileBytesAtomically(name, fileContents)) {
            throw SkillImportException(SkillImportException.CODE_SAVE_FAILED, "保存失败")
        }
        return name
    }

    private fun downloadBounded(url: String, limits: SkillImportLimits): ByteArray? {
        val bytes = try {
            download(url)
        } catch (_: GitHubRateLimitException) {
            throw SkillImportException(SkillImportException.CODE_GITHUB_RATE_LIMIT, "GitHub API 请求次数已达上限，请稍后再试")
        } ?: return null
        if (bytes.size > limits.maxFileBytes) tooLarge()
        return bytes
    }

    private fun importMarkdown(bytes: ByteArray, overwrite: Boolean): List<String> {
        val content = bytes.toString(Charsets.UTF_8)
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.trim()
        if (name.isNullOrBlank()) {
            throw SkillImportException(SkillImportException.CODE_INVALID, "SKILL.md 格式错误：缺少 name 字段")
        }
        if (frontmatter["description"].isNullOrBlank()) {
            throw SkillImportException(SkillImportException.CODE_INVALID, "SKILL.md 格式错误：缺少 description 字段")
        }
        checkConflicts(listOf(name), overwrite)
        val saved = skillManager.saveSkill(name, content)
            ?: throw SkillImportException(SkillImportException.CODE_SAVE_FAILED, "保存失败，请检查技能格式")
        return listOf(saved.name)
    }

    private fun importZip(bytes: ByteArray, limits: SkillImportLimits, overwrite: Boolean): List<String> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        val path = normalizeZipEntryPath(entry.name)
                        if (path != null) {
                            if (files.size + 1 > limits.maxFiles) tooLarge()
                            // Decompressed size is counted while reading, not trusted from the header.
                            val content = readBounded(zipInput, limits.maxFileBytes)
                            total += content.size
                            if (total > limits.maxTotalBytes) tooLarge()
                            files[path] = content
                        }
                    }
                } finally {
                    zipInput.closeEntry()
                }
            }
        }

        val skillMdPaths = files.keys
            .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .sorted()
        if (skillMdPaths.isEmpty()) {
            throw SkillImportException(SkillImportException.CODE_INVALID, "压缩包中未找到 SKILL.md")
        }
        val skillBasePaths = skillMdPaths.map { it.substringBeforeLast('/', missingDelimiterValue = "") }

        val planned = skillMdPaths.map { skillMdPath ->
            val skillContent = files[skillMdPath]?.toString(Charsets.UTF_8)
                ?: throw SkillImportException(SkillImportException.CODE_INVALID, "读取失败：$skillMdPath")
            val frontmatter = SkillFrontmatterParser.parse(skillContent)
            val name = frontmatter["name"]?.trim()
            if (name.isNullOrBlank()) {
                throw SkillImportException(SkillImportException.CODE_INVALID, "$skillMdPath 格式错误：缺少 name 字段")
            }
            if (frontmatter["description"].isNullOrBlank()) {
                throw SkillImportException(SkillImportException.CODE_INVALID, "$skillMdPath 格式错误：缺少 description 字段")
            }
            skillMdPath to name
        }
        checkConflicts(planned.map { it.second }, overwrite)

        val importedNames = mutableListOf<String>()
        for ((skillMdPath, name) in planned) {
            val basePath = skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")
            val skillFiles = LinkedHashMap<String, ByteArray>()
            for ((path, content) in files) {
                if (isInsideNestedSkill(path, basePath, skillBasePaths)) continue
                val relativePath = relativeToSkillBase(path, basePath) ?: continue
                val targetPath = if (relativePath.equals("SKILL.md", ignoreCase = true)) "SKILL.md" else relativePath
                skillFiles[targetPath] = content
            }
            if (!skillManager.saveSkillFileBytesAtomically(name, skillFiles)) {
                throw SkillImportException(SkillImportException.CODE_SAVE_FAILED, "保存失败：$name")
            }
            importedNames += name
        }
        return importedNames.distinct()
    }

    /** With [overwrite] off, an existing user skill of the same name is a conflict (builtins are not). */
    private fun checkConflicts(names: List<String>, overwrite: Boolean) {
        if (overwrite) return
        val existing = skillManager.listSkills().filterNot { it.isBuiltin }.map { it.name }.toSet()
        val conflicts = names.distinct().filter { it in existing }
        if (conflicts.isNotEmpty()) {
            throw SkillImportException(SkillImportException.CODE_EXISTS, "已存在同名技能：${conflicts.joinToString()}", conflicts)
        }
    }

    private fun tooLarge(): Nothing =
        throw SkillImportException(SkillImportException.CODE_TOO_LARGE, "导入内容超出大小或文件数量上限")

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) tooLarge()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun isInsideNestedSkill(path: String, basePath: String, skillBasePaths: List<String>): Boolean {
        return skillBasePaths.any { otherBasePath ->
            otherBasePath != basePath &&
                isPathInsideBase(path, otherBasePath) &&
                (basePath.isBlank() || isPathInsideBase(otherBasePath, basePath))
        }
    }

    private fun isPathInsideBase(path: String, basePath: String): Boolean {
        return basePath.isBlank() || path == basePath || path.startsWith("$basePath/")
    }

    private fun relativeToSkillBase(path: String, basePath: String): String? {
        if (basePath.isBlank()) return path
        if (path == basePath) return null
        return path.removePrefix("$basePath/").takeIf { it != path }
    }

    private fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isZipFile(fileName: String, bytes: ByteArray): Boolean {
        return fileName.endsWith(".zip", ignoreCase = true) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x03, 0x04) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x05, 0x06) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x07, 0x08)
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { index -> (this[index].toInt() and 0xFF) == values[index] }
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
        limits: SkillImportLimits,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadBounded(apiUrl, SkillImportLimits.UNLIMITED)?.toString(Charsets.UTF_8) ?: return false
        val array = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return false
        for (element in array) {
            val item = element as? JsonObject ?: return false
            val type = item.string("type") ?: return false
            val itemPath = item.string("path") ?: return false
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.string("download_url")?.takeIf { it.isNotBlank() } ?: return false
                    if (((item["size"] as? JsonPrimitive)?.longOrNull ?: 0) > limits.maxFileBytes) tooLarge()
                    result.add(relativePath to downloadUrl)
                    if (result.size > limits.maxFiles) tooLarge()
                }
                "dir" -> {
                    if (!listFilesRecursively(owner, repo, branch, itemPath, basePath, result, limits)) return false
                }
            }
        }
        return true
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private data class GitHubRepoInfo(val owner: String, val repo: String, val branch: String, val path: String)

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo[/tree/branch[/sub/path]]
        val match = GITHUB_URL_REGEX.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }
}
