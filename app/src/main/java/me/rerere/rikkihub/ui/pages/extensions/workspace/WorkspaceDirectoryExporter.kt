package me.rerere.rikkihub.ui.pages.extensions.workspace

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream
import java.io.Serializable
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceStorageArea

/**
 * 普通文件批量导出到用户选择的 SAF 目录.
 *
 * 与 ZIP 导出共存: 只处理普通文件, 目录不参与 (UI 在发起前禁用并解释;
 * 引擎侧收到目录条目也会记为显式失败, 不静默跳过). 不覆盖用户现存文件:
 * 目标目录同名文件存在时自动选非冲突名字 (保扩展名); createDocument 返回的
 * documentId 若在打开输出流前被发现是目录中原有文档或本轮已写目标, 该文件
 * 记失败, 且绝不把它当自己半成品删除. children() 查询失败时 fail-closed
 * 中止整批, 不做 "查不到就当没有冲突" 的乐观写入.
 *
 * 文件级失败/取消只尽力清理本次已确认新建且未写完的半成品; 已成功写出的
 * 文件全部保留. 所有流用 use 关闭, 取消向上传播、不会粉饰成成功.
 *
 * 已知限制: DocumentsProvider 的 createDocument/查询之间没有事务, 目标目录
 * 里并发写入者在两次检查间隙创建同名文件是 provider 侧无法由本端关闭的
 * 竞态; 本实现用 "先查再验 id" 把可检测到的复用拒之门外, 但不能对任意
 * provider 承诺绝对无覆盖. 失败摘要与进度由调用方 (VM) 汇总展示.
 */

/** 待导出的单个普通文件描述 (纯数据, 可被 snapshot / Serializable 快照). */
data class WorkspaceDirectoryExportFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean = false,
) : Serializable

/** OpenDocumentTree 回调前存入 rememberSaveable 的快照. */
internal data class PendingDirectoryExport(
    val area: WorkspaceStorageArea,
    /** ArrayList at the call site so the object stays saveable. */
    val files: List<WorkspaceDirectoryExportFile>,
) : Serializable

/** 本轮 createDocument 拿到的目标文档句柄; documentId 用于复用检测. */
interface WorkspaceCreatedDocument {
    val documentId: String
}

/**
 * 目标目录抽象 (可注入, 单测用 fake; 生产由 [SafExportDirectory] 适配).
 * 非 suspend: SAF provider 调用本身是阻塞 IO, 调用方自行切到 IO 线程.
 */
interface WorkspaceExportDirectory {
    /** Parent/other protected IDs must never be opened or treated as our partial document. */
    val protectedDocumentIds: Set<String> get() = emptySet()

    /** 目录下已有文档的 documentId -> displayName; 查询失败必须抛异常 (fail-closed). */
    fun children(): Map<String, String>

    /** 请求以 [displayName] 新建文档; provider 可改名, 返回实际句柄或 null. */
    fun create(displayName: String, mimeType: String): WorkspaceCreatedDocument?

    fun openOutput(document: WorkspaceCreatedDocument): OutputStream?

    /** 仅用于删除本轮确认新建、且从未成功写完的半成品. */
    fun delete(document: WorkspaceCreatedDocument)
}

enum class WorkspaceExportFailure {
    /** 目录条目被送到普通文件出口 (引擎防御; UI 已先行拦截). */
    DIRECTORY_REJECTED,

    /** displayName 清洗后为空 (如 "."/".."/纯非法字符名). */
    UNSAFE_NAME,

    /** 同一目录下同名文件过多, 编号用尽了还找不到空位. */
    NAME_COLLISION_EXHAUSTED,

    /** createDocument 返回的 documentId 是目录原有文档或本轮已写目标. */
    TARGET_ID_REUSED,

    /** 本轮新建文档成功但 openOutputStream 失败/写流抛错. */
    WRITE_FAILED,
}

data class WorkspaceExportFileFailure(
    val path: String,
    val name: String,
    val reason: WorkspaceExportFailure,
    val detail: String? = null,
)

data class WorkspaceDirectoryExportResult(
    val exportedCount: Int,
    val totalCount: Int,
    val failures: List<WorkspaceExportFileFailure>,
)

internal fun String?.normalizedExportName(): String = orEmpty().lowercase(Locale.ROOT)

/** Names that must never reach a provider: blank or "."/".." traversal tokens. */
internal fun isUnsafeExportName(displayName: String): Boolean =
    displayName.isBlank() || displayName == "." || displayName == ".." ||
        displayName.any { it == '/' || it == '\\' || it == '\u0000' }

/**
 * 在 [taken] (小写归一化后的已占用名) 里为 [displayName] 找一个非冲突名字,
 * 保留扩展名: "a.txt" -> "a (1).txt", 无扩展名 -> "a (1)".
 * 找不到 (超过 MAX_ATTEMPTS) 返回 null; 返回 null 也算显式失败, 不猜.
 */
internal fun resolveNonConflictingExportName(
    displayName: String,
    taken: Set<String>,
    maxAttempts: Int = 100,
): String? {
    if (isUnsafeExportName(displayName)) return null
    val dot = displayName.lastIndexOf('.')
    val hasUsableDot = dot > 0 && dot < displayName.length - 1
    val stem = if (hasUsableDot) displayName.substring(0, dot) else displayName
    val ext = if (hasUsableDot) displayName.substring(dot) else ""
    if (stem.isBlank()) return null
    if (displayName.normalizedExportName() !in taken) return displayName
    for (index in 1..maxAttempts) {
        val candidate = "$stem ($index)$ext"
        if (candidate.normalizedExportName() !in taken) return candidate
    }
    return null
}

/**
 * 批量把 [files] 写到 [target] 目录. 串行执行, 每文件之间检查取消.
 * [open] 是内容源 (生产侧转调 WorkspaceRepository.exportFile), 实现必须
 * 关闭/不持有 [out] —— 引擎用 use 保证关闭, 实现只管往里写.
 */
suspend fun exportWorkspaceFilesToDirectory(
    files: List<WorkspaceDirectoryExportFile>,
    target: WorkspaceExportDirectory,
    mimeType: (WorkspaceDirectoryExportFile) -> String = { file ->
        file.name.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .let { ext -> DEFAULT_MIME_BY_EXT[ext] }
            ?: "application/octet-stream"
    },
    onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    open: suspend (WorkspaceDirectoryExportFile, OutputStream) -> Unit,
): WorkspaceDirectoryExportResult {
    currentCoroutineContext().ensureActive()
    if (files.isEmpty()) return WorkspaceDirectoryExportResult(0, 0, emptyList())
    // children() 失败直接抛: fail-closed, 不做乐观覆盖
    val preExisting = target.children()
    val takenNames = preExisting.values.mapTo(HashSet()) { it.normalizedExportName() }
    val protectedIds = (preExisting.keys + target.protectedDocumentIds).toMutableSet()

    val failures = ArrayList<WorkspaceExportFileFailure>()
    var exported = 0

    files.forEachIndexed { index, file ->
        currentCoroutineContext().ensureActive()

        fun fail(reason: WorkspaceExportFailure, detail: String? = null) {
            failures += WorkspaceExportFileFailure(file.path, file.name, reason, detail)
        }

        if (file.isDirectory) {
            fail(WorkspaceExportFailure.DIRECTORY_REJECTED)
            onProgress(index + 1, files.size)
            return@forEachIndexed
        }

        // Refresh between files: providers can rename the last document, or another
        // writer can add files while this batch runs. Query failure aborts safely.
        val currentChildren = target.children()
        protectedIds += currentChildren.keys
        takenNames += currentChildren.values.map { it.normalizedExportName() }
        val targetName = resolveNonConflictingExportName(file.name, takenNames)
        if (targetName == null) {
            fail(
                if (isUnsafeExportName(file.name)) WorkspaceExportFailure.UNSAFE_NAME
                else WorkspaceExportFailure.NAME_COLLISION_EXHAUSTED
            )
            onProgress(index + 1, files.size)
            return@forEachIndexed
        }

        // 半成品只认 "create 返回 + 不是复用 id" 的句柄
        var ours: WorkspaceCreatedDocument? = null
        var committed = false
        try {
            val created = try {
                target.create(targetName, mimeType(file))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(WorkspaceExportFailure.WRITE_FAILED, error.message)
                return@forEachIndexed
            }
            if (created == null) {
                fail(WorkspaceExportFailure.WRITE_FAILED, "createDocument returned null")
            } else {
                val id = created.documentId
                when {
                    id.isBlank() || id in protectedIds ->
                        // 复用/空白 id: 不打开、不写入、也不删 (可能是用户现存文件)
                        fail(
                            WorkspaceExportFailure.TARGET_ID_REUSED,
                            "provider returned an existing documentId",
                        )

                    else -> {
                        ours = created
                        // Reserve even failed IDs/names: cleanup can fail, and a provider
                        // must not return that same document for a later export.
                        protectedIds += id
                        takenNames += targetName.normalizedExportName()
                        currentCoroutineContext().ensureActive()
                        val stream = try {
                            target.openOutput(created)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            fail(WorkspaceExportFailure.WRITE_FAILED, error.message)
                            return@forEachIndexed
                        }
                        if (stream == null) {
                            fail(WorkspaceExportFailure.WRITE_FAILED, "openOutputStream failed")
                        } else {
                            try {
                                stream.use { out ->
                                    currentCoroutineContext().ensureActive()
                                    open(file, out)
                                }
                                committed = true
                                exported++
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                fail(
                                    WorkspaceExportFailure.WRITE_FAILED,
                                    error.message ?: "write failed",
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            // 取消/失败路径也要能清: NonCancellable; 只删本轮确认新建且未提交成功的
            val partial = ours
            if (partial != null && !committed) {
                withContext(NonCancellable) {
                    runCatching { target.delete(partial) }
                }
            }
            onProgress(index + 1, files.size)
        }
    }

    return WorkspaceDirectoryExportResult(
        exportedCount = exported,
        totalCount = files.size,
        failures = failures,
    )
}

private val DEFAULT_MIME_BY_EXT = mapOf(
    "txt" to "text/plain",
    "md" to "text/markdown",
    "markdown" to "text/markdown",
    "json" to "application/json",
    "xml" to "application/xml",
    "csv" to "text/csv",
    "log" to "text/plain",
    "html" to "text/html",
    "htm" to "text/html",
    "js" to "text/javascript",
    "ts" to "text/typescript",
    "kt" to "text/x-kotlin",
    "py" to "text/x-python",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "pdf" to "application/pdf",
    "zip" to "application/zip",
)

/** A returned URI alone is not proof that it is a new child in the chosen directory. */
internal fun validateCreatedExportDocument(
    parentId: String,
    returnedId: String,
    sameAuthority: Boolean,
    childIds: Set<String>,
) {
    check(sameAuthority && returnedId.isNotBlank() && returnedId != parentId && returnedId in childIds) {
        "Document provider returned an unverified export target"
    }
}

/** SAF 文档句柄: createDocument 返回的 Uri + 其 documentId. */
private class SafCreatedDocument(
    val uri: Uri,
    override val documentId: String,
) : WorkspaceCreatedDocument

/**
 * 把 ACTION_OPEN_DOCUMENT_TREE 的 tree Uri 适配为 [WorkspaceExportDirectory].
 * provider 查询失败一律抛异常 (engine 视为 fail-closed 中止), 不返回空集
 * 假装 "没有冲突".
 */
internal class SafExportDirectory(
    private val contentResolver: ContentResolver,
    treeUri: Uri,
) : WorkspaceExportDirectory {
    private val parentDocUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
            ?: error("Not a document tree uri: $treeUri"),
    )
    private val parentId = DocumentsContract.getDocumentId(parentDocUri)
    override val protectedDocumentIds: Set<String> = setOf(parentId)
    private val childrenUri: Uri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        parentId,
    )

    override fun children(): Map<String, String> {
        val cursor = contentResolver.query(childrenUri, PROJECTION, null, null, null)
            ?: error("Document provider returned no child list for export target")
        return cursor.use {
            // Check columns even for an empty cursor; missing metadata is not an empty folder.
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            buildMap {
                while (it.moveToNext()) {
                    val id = it.getString(idIndex)?.takeIf(String::isNotBlank)
                        ?: error("Document provider omitted a child documentId")
                    val name = it.getString(nameIndex)
                        ?: error("Document provider omitted a child displayName")
                    put(id, name)
                }
            }
        }
    }

    override fun create(displayName: String, mimeType: String): WorkspaceCreatedDocument? {
        val uri = DocumentsContract.createDocument(contentResolver, parentDocUri, mimeType, displayName)
            ?: return null
        val id = DocumentsContract.getDocumentId(uri)
        // A provider returning an existing/foreign document must not cause a write or
        // cleanup there. If this post-create query fails, leave the unclaimed URI alone.
        validateCreatedExportDocument(
            parentId, id, uri.authority == parentDocUri.authority, children().keys,
        )
        return SafCreatedDocument(uri, id)
    }

    override fun openOutput(document: WorkspaceCreatedDocument): OutputStream? {
        val uri = (document as? SafCreatedDocument)?.uri ?: return null
        return contentResolver.openOutputStream(uri)
    }

    override fun delete(document: WorkspaceCreatedDocument) {
        val uri = (document as? SafCreatedDocument)?.uri ?: return
        deleteDocumentQuietly(contentResolver, uri)
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
    }
}
