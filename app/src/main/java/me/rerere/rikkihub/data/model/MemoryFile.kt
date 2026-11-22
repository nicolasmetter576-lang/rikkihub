package me.rerere.rikkihub.data.model

import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import java.security.MessageDigest

/**
 * 记忆库里的一个 Markdown 文件。
 *
 * [version] 由内容算出，写操作用它做乐观锁：内容没变版本就不变。
 */
data class MemoryFile(
    val path: String,
    val content: String,
    val updatedAt: Long,
) {
    val version: String by lazy { memoryVersionOf(content) }

    internal val parsedFrontmatter by lazy { SkillFrontmatterParser.parse(content) }

    /** frontmatter 里的一句话描述，用于清单 */
    val description: String get() = parsedFrontmatter["description"]?.trim().orEmpty()

    val aliases: List<String> get() = parsedFrontmatter.getList("aliases")

    /** 去掉 frontmatter 后的正文 */
    val body: String get() = SkillFrontmatterParser.extractBody(content)

    /** 给用户看的正文：`[stated]` 是写给模型的标记，界面上不显示 */
    val displayBody: String get() = body.replace(statedMarkerRegex, "$1")

    /**
     * 编辑页里的正文。frontmatter 读不出来时（比如某个值里有没加引号的冒号）整段原文都算正文：
     * 那一段在各个输入框里都看不到，不留在正文里的话一保存就被悄悄换掉了。
     */
    val editableBody: String get() = if (parsedFrontmatter.toMap().isEmpty()) content else body

    /** 所在目录，根目录下的文件是 "/" */
    val directory: String get() = path.substringBeforeLast('/').ifEmpty { "/" }

    /** 去掉目录和 .md 后缀的文件名 */
    val name: String get() = path.substringAfterLast('/').removeSuffix(".md")

    /** 界面上显示的标题：frontmatter 里的 title，没写时从文件名推出来（frontend-tooling 显示成 Frontend Tooling） */
    val title: String get() = parsedFrontmatter["title"]?.trim()?.ifEmpty { null } ?: memoryTitleOf(name)

    /**
     * 用编辑页分开填的各项拼出新的文件内容。
     *
     * name 跟着路径走，不由用户填；留空的项不写；frontmatter 里其他的键原样保留。
     */
    fun contentWith(title: String, description: String, aliases: List<String>, body: String): String {
        val values = linkedMapOf<String, Any?>("name" to name)
        title.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["title"] = it }
        description.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["description"] = it }
        aliases.takeIf { it.isNotEmpty() }?.let { values["aliases"] = it }
        parsedFrontmatter.toMap().forEach { (key, value) -> if (key !in editableKeys) values[key] = value }
        return SkillFrontmatterParser.compose(values, body)
    }

    companion object {
        const val PROFILE_PATH = "/profile.md"
        const val PREFERENCES_PATH = "/preferences.md"
        const val MAX_BYTES = 48 * 1024
        private const val MAX_PATH_LENGTH = 128

        /**
         * 一次写入后的大小是否允许：没超上限，或者超限的旧文件这次没变大。
         * 迁移来的旧记忆可能本来就超限，得允许一点点删小。仓库层和编辑页用同一条规则。
         */
        fun fitsLimit(newBytes: Int, currentBytes: Int?): Boolean =
            newBytes <= MAX_BYTES || newBytes <= (currentBytes ?: 0)

        /** 规范化模型或用户给出的路径，不合法时返回 null */
        fun normalizePath(raw: String): String? {
            val path = raw.trim().let { if (it.startsWith("/")) it else "/$it" }
            if (path.length > MAX_PATH_LENGTH || !path.endsWith(".md")) return null
            if (path.any { it.isISOControl() || it == '\\' }) return null
            val segments = path.removePrefix("/").split("/")
            if (segments.any { it.isBlank() || it != it.trim() || it.startsWith(".") }) return null
            return path
        }
    }
}

// 编辑页有对应输入框的键，加上跟着路径走的 name
private val editableKeys = setOf("name", "title", "description", "aliases")

// 清单里一个文件占一行，标题和描述里不能有换行
private fun String.toSingleLine(): String = replace(Regex("\\s+"), " ").trim()

// 列表项开头的 [stated] 标记，保留前面的列表符号
private val statedMarkerRegex = Regex("""(?m)^(\s*[-*+]\s+)\[stated\]\s*""", RegexOption.IGNORE_CASE)

// 每次都全文注入的两个文件排在最前，其余目录按记忆格式约定的顺序
private val pinnedPathOrder = listOf(MemoryFile.PROFILE_PATH, MemoryFile.PREFERENCES_PATH)
private val directoryOrder = listOf("/", "/topics", "/areas", "/people")

private fun <T> List<T>.orderOf(item: T) = indexOf(item).let { if (it < 0) size else it }

/** 把路径里用连字符或下划线拼的名字转成界面上的标题 */
fun memoryTitleOf(name: String): String =
    name.split('-', '_', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

/** 按目录分组，用于在界面上展示 */
fun List<MemoryFile>.groupByDirectory(): List<Pair<String, List<MemoryFile>>> =
    sortedWith(compareBy({ pinnedPathOrder.orderOf(it.path) }, { it.path }))
        .groupBy { it.directory }
        .toList()
        .sortedWith(compareBy({ directoryOrder.orderOf(it.first) }, { it.first }))

private fun memoryVersionOf(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)

internal data class MemoryFileMergeOutcome(
    val content: String,
    val changed: Boolean,
    val bytes: Int,
    val exceedsLimit: Boolean,
)

private val bulletLineRegex = Regex("""^\s*[-*+]\s+.*""")
private val multiBlankLinesRegex = Regex("""\n{3,}""")

/**
 * 将同路径的 [source] 结构化合并进 [target]：
 * - 合并 YAML frontmatter（保留目标既有字段，补齐空标题/描述，aliases 去重并集，保留未知自定义键）；
 * - 对正文中的逐字重复列表项去重后追加新增内容；
 * - 任一方无合法 frontmatter 时保留原始文本，不静默丢弃不可解析的头部。
 */
internal fun mergeMemoryFiles(target: MemoryFile, source: MemoryFile): MemoryFileMergeOutcome {
    val targetBytes = target.content.toByteArray().size
    if (target.version == source.version) {
        return MemoryFileMergeOutcome(target.content, changed = false, bytes = targetBytes, exceedsLimit = false)
    }

    val targetFm = target.parsedFrontmatter.toMap()
    val sourceFm = source.parsedFrontmatter.toMap()

    val (finalContent, changed) = if (targetFm.isEmpty() && sourceFm.isEmpty()) {
        val (mergedBody, bodyChanged) = mergeMemoryBodies(target.content, source.content)
        if (bodyChanged) "$mergedBody\n" to true else target.content to false
    } else {
        val targetBody = if (targetFm.isEmpty()) target.content else target.body
        val sourceBody = if (sourceFm.isEmpty()) source.content else source.body
        val (mergedBody, bodyChanged) = mergeMemoryBodies(targetBody, sourceBody)

        val targetExplicitTitle = (targetFm["title"] as? String)?.trim()?.ifEmpty { null }
        val sourceExplicitTitle = (sourceFm["title"] as? String)?.trim()?.ifEmpty { null }
        val mergedTitle = targetExplicitTitle ?: sourceExplicitTitle
        val mergedDescription = target.description.ifEmpty { source.description }
        val mergedAliases = (target.aliases + source.aliases).distinct()
        val extraFromSource = sourceFm.filterKeys { it !in editableKeys && it !in targetFm }

        val fmChanged = (targetFm.isEmpty() && sourceFm.isNotEmpty()) ||
            (targetExplicitTitle == null && sourceExplicitTitle != null) ||
            (target.description.isEmpty() && source.description.isNotEmpty()) ||
            mergedAliases.size > target.aliases.size ||
            extraFromSource.isNotEmpty()

        if (!bodyChanged && !fmChanged) {
            target.content to false
        } else {
            val values = linkedMapOf<String, Any?>("name" to target.name)
            mergedTitle?.toSingleLine()?.takeIf { it.isNotEmpty() }?.let { values["title"] = it }
            mergedDescription.toSingleLine().takeIf { it.isNotEmpty() }?.let { values["description"] = it }
            mergedAliases.takeIf { it.isNotEmpty() }?.let { values["aliases"] = it }
            targetFm.forEach { (k, v) -> if (k !in editableKeys) values[k] = v }
            extraFromSource.forEach { (k, v) -> values[k] = v }
            SkillFrontmatterParser.compose(values, mergedBody) to true
        }
    }

    val bytes = finalContent.toByteArray().size
    val exceedsLimit = changed && !MemoryFile.fitsLimit(bytes, targetBytes)
    return MemoryFileMergeOutcome(finalContent, changed = changed, bytes = bytes, exceedsLimit = exceedsLimit)
}

private fun mergeMemoryBodies(targetRaw: String, sourceRaw: String): Pair<String, Boolean> {
    val targetClean = targetRaw.replace("\r\n", "\n").trim()
    val sourceClean = sourceRaw.replace("\r\n", "\n").trim()
    if (sourceClean.isEmpty()) return targetClean to false
    if (targetClean.isEmpty()) return sourceClean to true

    val targetLines = targetClean.lines()
    val targetLineSet = targetLines.map { it.trim() }.toSet()
    val targetBullets = targetLines.filter { bulletLineRegex.matches(it) }.map { it.trim() }.toSet()

    val sourceLines = sourceClean.lines()
    val sourceHasContentLines = sourceLines.any { val t = it.trim(); t.isNotEmpty() && !t.startsWith("#") }
    val filtered = sourceLines.filterNot { bulletLineRegex.matches(it) && it.trim() in targetBullets }
    val collapsed = filtered.joinToString("\n").replace(multiBlankLinesRegex, "\n\n").trim()

    if (collapsed.isEmpty()) return targetClean to false
    val keptHasContentLines = collapsed.lines().any { val t = it.trim(); t.isNotEmpty() && !t.startsWith("#") }
    if (sourceHasContentLines && !keptHasContentLines) return targetClean to false
    if (!sourceHasContentLines && collapsed.lines().all { it.trim().isEmpty() || it.trim() in targetLineSet }) {
        return targetClean to false
    }

    return "$targetClean\n\n$collapsed" to true
}
