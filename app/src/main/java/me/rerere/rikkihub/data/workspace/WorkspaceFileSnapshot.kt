package me.rerere.rikkihub.data.workspace

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceFileEntry

/**
 * 工作区开关「首条消息附带文件列表」：会话第一条用户消息发送时，把工作目录（选了 cwd 就是 cwd，否则
 * `/workspace`）的列表（默认一层，相当于 `ls`；可设多层，见 [formatWorkspaceFileTree]）冻结进该消息第一个 part 的 metadata，省掉模型开场先 ls 一次。
 * 只在发送时拍一次，之后每次请求原样附带，不随文件变化重拍（否则会打断 prompt cache）。
 */
const val WORKSPACE_FILE_SNAPSHOT_METADATA_KEY = "workspaceFileSnapshot"

/** 列表最多列出的条目数，超出部分只给数量。 */
const val WORKSPACE_FILE_SNAPSHOT_MAX_ENTRIES = 200

val UIMessagePart.workspaceFileSnapshot: String?
    get() = metadata?.get(WORKSPACE_FILE_SNAPSHOT_METADATA_KEY)?.jsonPrimitive?.contentOrNull

/** `ls` 式的一层列表：目录在名字后加 `/`，文件带大小；顺序沿用文件列表（目录在前，按名字）。 */
fun formatWorkspaceFileSnapshot(
    guestPath: String,
    entries: List<WorkspaceFileEntry>,
    maxEntries: Int = WORKSPACE_FILE_SNAPSHOT_MAX_ENTRIES,
): String = buildString {
    append("<workspace_files path=\"").append(guestPath).append("\">\n")
    append("Listing of ").append(guestPath)
        .append(" taken when this conversation started (like `ls`; it is not refreshed):\n")
    if (entries.isEmpty()) append("(empty)\n")
    entries.take(maxEntries).forEach { entry ->
        if (entry.isDirectory) append(entry.name).append("/\n")
        else append(entry.name).append("  ").append(entry.sizeBytes.fileSizeToString()).append('\n')
    }
    if (entries.size > maxEntries) append("… and ").append(entries.size - maxEntries).append(" more\n")
    append("</workspace_files>")
}

/** 可设置的最大层数（App/Web 设置都按这个范围收）。 */
const val WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH = 10

/** 只列名字、不往下展开的目录：内容多且对模型基本是噪音。 */
private val WORKSPACE_FILE_SNAPSHOT_NOT_EXPANDED = setOf(".git", "node_modules")

/**
 * 多层列表（`depth` ≥ 2）：缩进树形，目录在名字后加 `/`。条目先按层广度优先选入（总数仍受
 * [maxEntries] 限制），保证上层完整，深层多了就截断并写「… and N more」。[listChildren] 读不了
 * 返回 null，该目录只列名字。`depth` ≤ 1 时与 [formatWorkspaceFileSnapshot] 逐字相同。
 */
fun formatWorkspaceFileTree(
    guestPath: String,
    depth: Int,
    rootEntries: List<WorkspaceFileEntry>,
    maxEntries: Int = WORKSPACE_FILE_SNAPSHOT_MAX_ENTRIES,
    listChildren: (WorkspaceFileEntry) -> List<WorkspaceFileEntry>?,
): String {
    val levels = depth.coerceIn(1, WORKSPACE_FILE_SNAPSHOT_MAX_DEPTH)
    if (levels == 1) return formatWorkspaceFileSnapshot(guestPath, rootEntries, maxEntries)

    class Node(val entry: WorkspaceFileEntry, val level: Int) {
        var listed: List<WorkspaceFileEntry>? = null
        val included = ArrayList<Node>()
        val notExpanded get() = entry.isDirectory && entry.name in WORKSPACE_FILE_SNAPSHOT_NOT_EXPANDED
    }

    var budget = maxEntries
    val roots = rootEntries.take(budget).map { Node(it, 1) }
    budget -= roots.size
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (!node.entry.isDirectory || node.notExpanded || node.level >= levels) continue
        val children = listChildren(node.entry) ?: continue
        node.listed = children
        children.take(budget).forEach { child ->
            val childNode = Node(child, node.level + 1)
            node.included += childNode
            queue.addLast(childNode)
        }
        budget -= node.included.size
    }

    return buildString {
        append("<workspace_files path=\"").append(guestPath).append("\" depth=\"").append(levels).append("\">\n")
        append("Listing of ").append(guestPath).append(", ").append(levels)
            .append(" levels deep, taken when this conversation started (like `ls -R`; it is not refreshed):\n")
        if (rootEntries.isEmpty()) append("(empty)\n")
        fun emit(node: Node) {
            val indent = "  ".repeat(node.level - 1)
            append(indent).append(node.entry.name)
            when {
                node.notExpanded -> append("/  (not expanded)")
                node.entry.isDirectory -> append('/')
                else -> append("  ").append(node.entry.sizeBytes.fileSizeToString())
            }
            append('\n')
            node.included.forEach(::emit)
            val hidden = (node.listed?.size ?: 0) - node.included.size
            if (hidden > 0) append(indent).append("  … and ").append(hidden).append(" more\n")
        }
        roots.forEach(::emit)
        if (rootEntries.size > roots.size) append("… and ").append(rootEntries.size - roots.size).append(" more\n")
        append("</workspace_files>")
    }
}

/** 把快照记到第一个能带 metadata 的 part 上（文字、图片、文档）；没有这类 part 时原样返回。 */
fun List<UIMessagePart>.withWorkspaceFileSnapshot(snapshot: String): List<UIMessagePart> {
    val index = indexOfFirst { it is UIMessagePart.Text || it is UIMessagePart.Image || it is UIMessagePart.Document }
    if (index < 0) return this
    val part = this[index]
    val metadata = buildJsonObject {
        part.metadata?.forEach { (key, value) -> put(key, value) }
        put(WORKSPACE_FILE_SNAPSHOT_METADATA_KEY, JsonPrimitive(snapshot))
    }
    val tagged = when (part) {
        is UIMessagePart.Text -> part.copy(metadata = metadata)
        is UIMessagePart.Image -> part.copy(metadata = metadata)
        is UIMessagePart.Document -> part.copy(metadata = metadata)
        else -> part
    }
    return toMutableList().apply { this[index] = tagged }
}

/** 请求时把冻结的快照作为一段文字接在该用户消息末尾（只给模型看，界面不显示）。 */
object WorkspaceFileSnapshotTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.map { message ->
            if (message.role != MessageRole.USER) return@map message
            val snapshot = message.parts.firstNotNullOfOrNull { it.workspaceFileSnapshot } ?: return@map message
            // 前置空行：有的 provider 把同一消息的文字段直接首尾相接
            message.copy(parts = message.parts + UIMessagePart.Text("\n\n" + snapshot))
        }
}
