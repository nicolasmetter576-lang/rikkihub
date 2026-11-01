package me.rerere.rikkihub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceDeviceMount
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import kotlin.math.max

/** `@/device/<alias>/...` 查询解析结果（保留命名空间，不落回 /workspace）。 */
internal sealed interface DeviceQuery {
    /** `device` / `device/` / `/device` —— 列出 mount alias */
    data object Root : DeviceQuery

    /** `device/<alias>` 或 `device/<alias>/<sub>`；subPath=null 表示尚未进入 mount */
    data class Alias(val alias: String, val subPath: String?) : DeviceQuery
}

/**
 * 把 @ 后的归一化查询解析为 device 命名空间请求；非 device 查询返回 null。
 * Only absolute `/device` queries enter this namespace; workspace-relative names are unchanged.
 */
internal fun parseDeviceQuery(rawQuery: String): DeviceQuery? {
    val q = rawQuery.replace('\\', '/').trimStart()
    // Relative `device/...` remains an ordinary /workspace path, matching Web semantics.
    if (!q.startsWith("/device")) return null
    val normalized = q.removePrefix("/")
    if (normalized == "device" || normalized == "device/") return DeviceQuery.Root
    if (!normalized.startsWith("device/")) return null
    val rest = normalized.removePrefix("device/")
    if (rest.isEmpty()) return DeviceQuery.Root
    val alias = rest.substringBefore('/')
    val sub = if ('/' in rest) rest.substringAfter('/') else null
    return DeviceQuery.Alias(alias = alias, subPath = sub)
}

class WorkspaceCompletionProvider(
    private val workspaceId: String?,
    private val repository: WorkspaceRepository,
    private val currentCwd: String? = null,
    /** Conversation confined to its working directory: list only that directory, no `/device`. */
    private val scoped: Boolean = false,
) : ChatCompletionProvider {
    override val id: String = "workspace_files"
    private val relativeCwd = currentCwd.toWorkspaceRelativePath()
    private val confined = scoped && relativeCwd.isNotBlank()

    private var cachedAt: Long = 0L
    private var cachedEntries: List<WorkspaceFileEntry> = emptyList()

    // ---- /device 命名空间补全缓存（workspaceId+viewEpoch+mounts 指纹同变即弃） ----
    private var deviceMountsCachedAt: Long = 0L
    private var deviceMountsEpoch: Long = -1L
    private var deviceMountsCached: List<WorkspaceDeviceMount> = emptyList()
    private var deviceListingCachedAt: Long = 0L
    private var deviceListingEpoch: Long = -1L
    private var deviceListingFingerprint: String = ""
    private var deviceListingDir: String = ""
    private var deviceListingEntries: List<WorkspaceFileEntry> = emptyList()

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (workspaceId.isNullOrBlank() || context.hasSelection) return null
        val mention = findWorkspaceMention(context.text, context.cursor) ?: return null
        val deviceQuery = parseDeviceQuery(mention.query)
        if (deviceQuery != null) {
            return if (confined) null else completeDevice(mention, deviceQuery)
        }
        val query = mention.query.normalizeWorkspaceQuery()
        val absoluteQuery = mention.query.isWorkspaceAbsoluteQuery()
        val entries = loadEntries()

        val items = entries
            .asSequence()
            .mapNotNull { entry ->
                val score = entry.matchScore(query, absoluteQuery) ?: return@mapNotNull null
                val path = entry.workspacePath()
                ChatCompletionItem(
                    label = path,
                    insertText = if (entry.isDirectory) "@$path/" else "@$path ",
                    icon = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                    sortScore = score,
                )
            }
            .sortedWith(
                compareByDescending<ChatCompletionItem> { it.sortScore }
                    .thenBy { it.label.length }
                    .thenBy { it.label.lowercase() }
            )
            .take(MAX_COMPLETION_ITEMS)
            .toList()

        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = mention.range,
            items = items,
        )
    }

    /**
     * `/device` 命名空间补全：只列「显式连接且当前可用」的 mount，不扫整机。
     * - 每次查询先经 `repository.deviceMounts`（service.resolveMounts，fail-closed）
     *   校验当前授权/source 状态，缓存只在 viewEpoch+指纹+TTL 内复用；
     * - `device`/`device/` → 各 mount alias 目录；`device/<alias>/sub` → 该
     *   mount 内当前父目录的**直接 children**（前缀匹配，不递归）；
     * - 未配置 mount / 未知 alias → 明确无候选（不回落 /workspace）。
     */
    private suspend fun completeDevice(
        mention: WorkspaceMention,
        query: DeviceQuery,
    ): ChatCompletionList? {
        val mounts = deviceMounts()
        val items = when (query) {
            DeviceQuery.Root -> mounts.map { mount ->
                ChatCompletionItem(
                    label = "/device/${mount.alias}",
                    insertText = "@/device/${mount.alias}/",
                    icon = HugeIcons.Folder01,
                    sortScore = 100,
                )
            }

            is DeviceQuery.Alias -> {
                val sub = query.subPath
                if (sub == null) {
                    // `device/al`：按 alias 前缀在 mount 列表里补全
                    return mounts.asSequence()
                        .filter { it.alias.startsWith(query.alias) }
                        .map { mount ->
                            ChatCompletionItem(
                                label = "/device/${mount.alias}",
                                insertText = "@/device/${mount.alias}/",
                                icon = HugeIcons.Folder01,
                                sortScore = 100,
                            )
                        }
                        .toList()
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                            ChatCompletionList(
                                providerId = id,
                                replacementRange = mention.range,
                                items = it,
                            )
                        }
                }
                val mount = mounts.firstOrNull { it.alias == query.alias }
                    ?: return null // 未知 alias：明确无候选
                val dir = sub.substringBeforeLast('/', missingDelimiterValue = "")
                val prefix = sub.substringAfterLast('/').lowercase()
                val children = deviceListing(mount, dir)
                children.asSequence()
                    .filter { prefix.isEmpty() || it.name.lowercase().startsWith(prefix) }
                    .map { entry ->
                        val rel = listOf(dir, entry.name).filter { it.isNotBlank() }.joinToString("/")
                        val full = "/device/${mount.alias}/$rel"
                        ChatCompletionItem(
                            label = full,
                            insertText = if (entry.isDirectory) "@$full/" else "@$full ",
                            detail = mount.alias,
                            icon = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                            sortScore = if (entry.isDirectory) 50 else 25,
                        )
                    }
                    .sortedWith(
                        compareByDescending<ChatCompletionItem> { it.sortScore }
                            .thenBy { it.label.length }
                            .thenBy { it.label.lowercase() },
                    )
                    .take(DEVICE_MAX_COMPLETION_ITEMS)
                    .toList()
            }
        }
        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = mention.range,
            items = items,
        )
    }

    /** Revalidate permission/source on every query, even when a directory listing cache hits. */
    private suspend fun deviceMounts(): List<WorkspaceDeviceMount> {
        val id = workspaceId ?: return emptyList()
        val epoch = runCatching { repository.runtimeGate.currentViewEpoch(id) }.getOrDefault(-1L)
        val now = System.currentTimeMillis()
        val mounts = try {
            repository.deviceMounts(id).take(DEVICE_MAX_MOUNTS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        deviceMountsCached = mounts
        deviceMountsEpoch = epoch
        deviceMountsCachedAt = now
        return mounts
    }

    /**
     * 单目录 children 列表（有界）：每次先校验 mount 仍在当前可挂载集合里
     * （权限撤回/连接删除后旧缓存不出候选），再看 TTL+epoch+指纹。
     */
    private suspend fun deviceListing(
        mount: WorkspaceDeviceMount,
        dir: String,
    ): List<WorkspaceFileEntry> {
        val id = workspaceId ?: return emptyList()
        val current = deviceMounts()
        if (current.none { it.id == mount.id && it.alias == mount.alias }) return emptyList()
        val epoch = deviceMountsEpoch
        val fingerprint = current.joinToString("\u001f") { "${it.id}:${it.alias}:${it.source.path}" }
        val key = listOf(dir, mount.id).joinToString("\u001f")
        val now = System.currentTimeMillis()
        if (
            deviceListingCachedAt > 0L &&
            now - deviceListingCachedAt < DEVICE_LISTING_TTL_MILLIS &&
            deviceListingEpoch == epoch &&
            deviceListingFingerprint == fingerprint &&
            deviceListingDir == key
        ) {
            return deviceListingEntries
        }
        val path = listOf("/device", mount.alias, dir)
            .filter { it.isNotBlank() }
            .joinToString("/")
        val entries = try {
            repository.listRootfs(id, path).take(DEVICE_MAX_LISTED_ENTRIES)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        deviceListingEntries = entries
        deviceListingDir = key
        deviceListingFingerprint = fingerprint
        deviceListingEpoch = epoch
        deviceListingCachedAt = now
        return entries
    }

    private suspend fun loadEntries(): List<WorkspaceFileEntry> {
        val now = System.currentTimeMillis()
        if (cachedAt > 0L && now - cachedAt < CACHE_TTL_MILLIS) {
            return cachedEntries
        }

        val result = mutableListOf<WorkspaceFileEntry>()
        val queue = ArrayDeque<String>()
        val seenDirs = mutableSetOf<String>()
        val seenEntries = mutableSetOf<String>()
        val matcherCache = mutableMapOf<String, WorkspaceIgnoreMatcher>()
        if (relativeCwd.isNotBlank()) {
            queue.add(relativeCwd)
            seenDirs += relativeCwd
        }
        if (!confined) {
            queue.add("")
            seenDirs += ""
        }
        var visitedDirs = 0

        while (queue.isNotEmpty() && result.size < MAX_INDEXED_ENTRIES && visitedDirs < MAX_INDEXED_DIRS) {
            val path = queue.removeFirst()
            visitedDirs++
            val ignoreMatcher = matcherForDirectory(path, matcherCache)
            val entries = try {
                repository.listFiles(
                    id = workspaceId ?: return emptyList(),
                    area = WorkspaceStorageArea.FILES,
                    path = path,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            entries.forEach { entry ->
                if (result.size >= MAX_INDEXED_ENTRIES) return@forEach
                if (ignoreMatcher.isIgnored(entry.path, entry.isDirectory)) return@forEach
                if (seenEntries.add(entry.path)) {
                    result += entry
                }
                if (entry.isDirectory && queue.size < MAX_INDEXED_DIRS && seenDirs.add(entry.path)) {
                    queue.add(entry.path)
                }
            }
        }

        cachedEntries = result
        cachedAt = now
        return result
    }

    private suspend fun matcherForDirectory(
        directory: String,
        cache: MutableMap<String, WorkspaceIgnoreMatcher>,
    ): WorkspaceIgnoreMatcher {
        val normalized = directory.toWorkspaceRelativePath()
        cache[normalized]?.let { return it }

        var matcher = WorkspaceIgnoreMatcher()
        directoryAncestors(normalized).forEach { path ->
            cache[path]?.let {
                matcher = it
                return@forEach
            }
            val gitignore = readGitignore(path)
            if (gitignore != null) {
                matcher = matcher.withGitignore(path, gitignore)
            }
            cache[path] = matcher
        }
        return matcher
    }

    private suspend fun readGitignore(directory: String): String? {
        val id = workspaceId ?: return null
        val path = if (directory.isBlank()) ".gitignore" else "$directory/.gitignore"
        return try {
            repository.readText(id, path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun directoryAncestors(path: String): List<String> {
        if (path.isBlank()) return listOf("")
        val parts = path.split('/').filter { it.isNotBlank() }
        val result = mutableListOf("")
        parts.fold("") { current, part ->
            val next = if (current.isBlank()) part else "$current/$part"
            result += next
            next
        }
        return result
    }

    private fun WorkspaceFileEntry.workspacePath(): String = "/workspace/$path"

    private fun WorkspaceFileEntry.matchScore(query: String, absoluteQuery: Boolean): Int? {
        val normalizedPath = path.lowercase()
        val normalizedName = name.lowercase()
        val normalizedQuery = query.lowercase()
        val globalScore = if (normalizedQuery.isBlank()) {
            1
        } else {
            max(
                normalizedPath.fuzzyScore(normalizedQuery) ?: -1,
                normalizedName.fuzzyScore(normalizedQuery) ?: -1,
            ).takeIf { it >= 0 } ?: return null
        }

        val cwdScore = if (!absoluteQuery) matchCwdScore(normalizedQuery) else null
        val score = max(globalScore, cwdScore ?: -1)
        return score + if (isDirectory) DIRECTORY_SCORE_BONUS else 0
    }

    private fun WorkspaceFileEntry.matchCwdScore(query: String): Int? {
        val cwdPath = path.relativeToCwdOrNull()?.lowercase() ?: return null
        val score = if (query.isBlank()) {
            1
        } else {
            max(
                cwdPath.fuzzyScore(query) ?: -1,
                name.lowercase().fuzzyScore(query) ?: -1,
            ).takeIf { it >= 0 } ?: return null
        }
        return score + CWD_SCORE_BONUS
    }

    private fun String.relativeToCwdOrNull(): String? {
        if (relativeCwd.isBlank()) return this
        if (this == relativeCwd) return ""
        val prefix = "$relativeCwd/"
        return if (startsWith(prefix)) removePrefix(prefix) else null
    }

    private fun String.fuzzyScore(query: String): Int? {
        if (query.isBlank()) return 1
        if (this == query) return 1000
        if (startsWith(query)) return 900 - length.coerceAtMost(200)
        val containsIndex = indexOf(query)
        if (containsIndex >= 0) return 800 - containsIndex.coerceAtMost(200)

        var queryIndex = 0
        var firstMatch = -1
        var lastMatch = -1
        forEachIndexed { index, char ->
            if (queryIndex < query.length && char == query[queryIndex]) {
                if (firstMatch < 0) firstMatch = index
                lastMatch = index
                queryIndex++
            }
        }
        if (queryIndex != query.length) return null

        val span = (lastMatch - firstMatch + 1).coerceAtLeast(query.length)
        return 500 - span.coerceAtMost(300) - firstMatch.coerceAtLeast(0).coerceAtMost(100)
    }

    private fun String.normalizeWorkspaceQuery(): String {
        val normalized = replace('\\', '/').trimStart()
        return normalized.removeWorkspacePrefix().trimStart('/')
    }

    private fun String?.toWorkspaceRelativePath(): String {
        if (isNullOrBlank()) return ""
        return replace('\\', '/')
            .trim()
            .removeWorkspacePrefix()
            .trim('/')
    }

    private fun String.isWorkspaceAbsoluteQuery(): Boolean {
        val normalized = replace('\\', '/').trimStart()
        return normalized.startsWith("/") || normalized == "workspace" || normalized.startsWith("workspace/")
    }

    private fun String.removeWorkspacePrefix(): String {
        return when {
            this == "/workspace" || this == "workspace" -> ""
            startsWith("/workspace/") -> removePrefix("/workspace/")
            startsWith("workspace/") -> removePrefix("workspace/")
            else -> this
        }
    }

    private data class WorkspaceMention(
        val query: String,
        val range: TextRange,
    )

    private fun findWorkspaceMention(text: String, cursor: Int): WorkspaceMention? {
        if (cursor < 0 || cursor > text.length) return null
        val prefix = text.substring(0, cursor)
        val start = prefix.lastIndexOf('@')
        if (start < 0) return null
        if (start > 0 && !text[start - 1].isMentionBoundary()) return null

        val query = prefix.substring(start + 1)
        if (query.any { it.isWhitespace() }) return null
        return WorkspaceMention(
            query = query,
            range = TextRange(start, cursor),
        )
    }

    private fun Char.isMentionBoundary(): Boolean =
        isWhitespace() || this in "([{<\"'"

    companion object {
        private const val MAX_COMPLETION_ITEMS = 8
        private const val MAX_INDEXED_ENTRIES = 500
        private const val MAX_INDEXED_DIRS = 80
        private const val CACHE_TTL_MILLIS = 5_000L
        private const val DIRECTORY_SCORE_BONUS = 25
        private const val CWD_SCORE_BONUS = 1_500

        // /device 命名空间有界补全：≤8 mount、单目录 ≤512 条、不递归扫全机
        private const val DEVICE_MAX_MOUNTS = 8
        private const val DEVICE_MAX_LISTED_ENTRIES = 512
        private const val DEVICE_MAX_COMPLETION_ITEMS = 8
        private const val DEVICE_MOUNTS_TTL_MILLIS = 2_000L
        private const val DEVICE_LISTING_TTL_MILLIS = 5_000L
    }
}
