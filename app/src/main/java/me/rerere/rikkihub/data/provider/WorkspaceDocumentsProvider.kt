package me.rerere.rikkihub.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceFileAlreadyExistsException
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTransferMode
import org.koin.core.context.GlobalContext
import java.io.FileNotFoundException

/**
 * 通过 Storage Access Framework 将 workspace 的 files 目录暴露给系统文件管理器。
 *
 * 目录结构：
 * ```
 * <root>
 * └── {workspace name}        <- 每个 workspace（虚拟目录，对应 files/ 目录）
 *     └── ...                 <- files/ 目录下的实际文件
 * ```
 *
 * documentId 设计：
 * - 顶层根：[ROOT_DOC_ID]
 * - workspace 根目录：`ws/{root}`（root 为 workspace 在磁盘上的目录名，即 UUID）
 * - workspace 内文件：`ws/{root}/{相对 files/ 的路径}`
 *
 * 查询/增删改/同工作区复制移动走 [WorkspaceBackend]（带文件锁与排他创建）；
 * 打开文件直接返回真实 FD；跨工作区复制/移动用 [WorkspaceManager] 解析出的真实路径完成。
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private fun backend(): WorkspaceBackend = GlobalContext.get().get()

    private fun manager(): WorkspaceManager = GlobalContext.get().get()

    private fun dao(): WorkspaceDAO = GlobalContext.get().get()

    private fun allWorkspaces(): List<WorkspaceEntity> = runBlocking { dao().getAll() }

    /** 一次查询生成 root → 显示名映射，避免逐行查询数据库 */
    private fun workspaceNameByRoot(): Map<String, String> =
        allWorkspaces().associate { it.root to it.name }

    /** Only roots of registered workspaces resolve; a forged documentId cannot name another tree. */
    private fun requireKnownRoot(root: String) {
        if (allWorkspaces().none { it.root == root }) throw FileNotFoundException("Unknown workspace")
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE,
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val target = parseDocId(documentId)
        if (target.isRoot) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_DISPLAY_NAME, context?.getString(R.string.app_name))
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
        } else {
            requireKnownRoot(target.root)
            val info = backend().stat(target.root, target.relPath)
            if (info.exists) {
                addRow(
                    cursor, target.root, target.relPath, displayName(target.relPath),
                    info.isDirectory, info.sizeBytes, info.updatedAt, workspaceNameByRoot(),
                )
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = parseDocId(parentDocumentId)
        if (parent.isRoot) {
            // 顶层：列出所有 workspace
            val workspaces = allWorkspaces()
            val names = workspaces.associate { it.root to it.name }
            for (ws in workspaces) {
                addRow(cursor, ws.root, "", ws.name, true, 0L, ws.updatedAt, names)
            }
        } else {
            requireKnownRoot(parent.root)
            val names = workspaceNameByRoot()
            backend().listFiles(parent.root, parent.relPath)
                .filter { !it.name.startsWith(".l2s.") }
                .sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .forEach { entry ->
                    addRow(cursor, parent.root, entry.path, entry.name, entry.isDirectory,
                        entry.sizeBytes, entry.updatedAt, names)
                }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot open this document" }
        requireKnownRoot(target.root)
        val file = manager().resolveFile(target.root, target.relPath)
        if (!file.isFile) throw FileNotFoundException("Not a file")
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        val writable = accessMode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0 ||
            accessMode and ParcelFileDescriptor.MODE_READ_WRITE != 0
        if (!writable) return ParcelFileDescriptor.open(file, accessMode)
        val parentDocId = buildDocId(target.root, target.relPath.substringBeforeLast('/', ""))
        return ParcelFileDescriptor.open(file, accessMode, Handler(Looper.getMainLooper())) {
            notifyChange(parentDocId)
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = parseDocId(parentDocumentId)
        require(!parent.isRoot) { "Cannot create document at root" }
        requireKnownRoot(parent.root)
        val backend = backend()
        val parentInfo = backend.stat(parent.root, parent.relPath)
        require(parentInfo.isDirectory) { "Parent is not a directory" }
        val entry = withUniqueName(parent.root, parent.relPath, displayName) { path ->
            if (mimeType == Document.MIME_TYPE_DIR) backend.createDirectory(parent.root, path)
            else backend.createFile(parent.root, path, "")
        }
        notifyChange(parentDocumentId)
        return buildDocId(parent.root, entry.path)
    }

    override fun deleteDocument(documentId: String) {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot delete this document" }
        requireKnownRoot(target.root)
        require(backend().deleteFile(target.root, target.relPath, recursive = true)) { "Failed to delete: $documentId" }
        notifyChange(buildDocId(target.root, target.relPath.substringBeforeLast('/', "")))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot rename this document" }
        requireKnownRoot(target.root)
        val parentPath = target.relPath.substringBeforeLast('/', "")
        val dest = joinPath(parentPath, safeName(displayName))
        val entry = backend().moveFile(target.root, target.relPath, dest, overwrite = false)
        notifyChange(buildDocId(target.root, parentPath))
        return buildDocId(target.root, entry.path)
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val source = parseDocId(sourceDocumentId)
        val targetParent = parseDocId(targetParentDocumentId)
        require(!source.isRoot && source.relPath.isNotEmpty()) { "Cannot copy this document" }
        require(!targetParent.isRoot) { "Cannot copy to root" }
        requireKnownRoot(source.root)
        if (source.root != targetParent.root) {
            return transferAcrossWorkspaces(source, targetParent, targetParentDocumentId, move = false)
        }
        val backend = backend()
        require(backend.stat(targetParent.root, targetParent.relPath).isDirectory) { "Target is not a directory" }
        val entry = withUniqueName(targetParent.root, targetParent.relPath, displayName(source.relPath)) { path ->
            backend.copyFile(source.root, source.relPath, path)
        }
        notifyChange(targetParentDocumentId)
        return buildDocId(targetParent.root, entry.path)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String,
    ): String {
        val source = parseDocId(sourceDocumentId)
        val targetParent = parseDocId(targetParentDocumentId)
        require(!source.isRoot && source.relPath.isNotEmpty()) { "Cannot move this document" }
        require(!targetParent.isRoot) { "Cannot move to root" }
        requireKnownRoot(source.root)
        if (source.root != targetParent.root) {
            return transferAcrossWorkspaces(source, targetParent, targetParentDocumentId, move = true)
        }
        val backend = backend()
        require(backend.stat(targetParent.root, targetParent.relPath).isDirectory) { "Target is not a directory" }
        require(targetParent.relPath != source.relPath && !targetParent.relPath.startsWith(source.relPath + "/")) {
            "Cannot move a directory into itself"
        }
        val entry = withUniqueName(targetParent.root, targetParent.relPath, displayName(source.relPath)) { path ->
            backend.moveFile(source.root, source.relPath, path, overwrite = false)
        }
        notifyChange(buildDocId(source.root, source.relPath.substringBeforeLast('/', "")))
        notifyChange(targetParentDocumentId)
        return buildDocId(targetParent.root, entry.path)
    }

    /**
     * Copy/move between two workspaces' FILES trees: same locking, link handling and
     * "name (n)" renaming as the in-app Move/Copy to ([me.rerere.workspace.WorkspaceFileTransfer]).
     */
    private fun transferAcrossWorkspaces(
        source: DocId,
        targetParent: DocId,
        targetParentDocumentId: String,
        move: Boolean,
    ): String {
        requireKnownRoot(targetParent.root)
        val mode = if (move) WorkspaceTransferMode.MOVE else WorkspaceTransferMode.COPY
        val item = runBlocking {
            backend().transferFiles(source.root, listOf(source.relPath), targetParent.root, targetParent.relPath, mode)
        }.items.single()
        val target = item.target ?: throw IllegalStateException(item.error ?: "Failed to ${mode.name.lowercase()}: ${source.relPath}")
        if (move) notifyChange(buildDocId(source.root, source.relPath.substringBeforeLast('/', "")))
        notifyChange(targetParentDocumentId)
        return buildDocId(targetParent.root, target)
    }

    override fun getDocumentType(documentId: String): String {
        val target = parseDocId(documentId)
        if (target.isRoot || target.relPath.isEmpty()) return Document.MIME_TYPE_DIR
        requireKnownRoot(target.root)
        val info = backend().stat(target.root, target.relPath)
        return if (info.isDirectory) Document.MIME_TYPE_DIR else mimeOf(displayName(target.relPath))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parseDocId(parentDocumentId)
        val child = parseDocId(documentId)
        if (child.isRoot) return false
        if (parent.isRoot) return true
        if (parent.root != child.root) return false
        if (parent.relPath.isEmpty()) return true
        return child.relPath == parent.relPath || child.relPath.startsWith(parent.relPath + "/")
    }

    // --- helpers ---

    private fun addRow(
        cursor: MatrixCursor,
        root: String,
        relPath: String,
        name: String,
        isDir: Boolean,
        size: Long,
        modified: Long,
        names: Map<String, String>,
    ) {
        val flags = when {
            // workspace 根目录：仅允许在其内部创建文件，不能删除/重命名 workspace 本身
            relPath.isEmpty() -> Document.FLAG_DIR_SUPPORTS_CREATE
            isDir -> Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
            else -> Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, buildDocId(root, relPath))
            add(Document.COLUMN_DISPLAY_NAME, if (relPath.isEmpty()) names[root] ?: root else name)
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(name))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else size)
            add(Document.COLUMN_LAST_MODIFIED, modified.takeIf { it > 0 })
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun displayName(relPath: String) = relPath.substringAfterLast('/')

    private fun safeName(name: String) = name.replace('/', '_').replace('\\', '_').ifBlank { "untitled" }

    private fun joinPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    /** "name", then "stem (1).ext", ... — the backend refuses an existing target atomically. */
    private fun withUniqueName(
        root: String,
        parentPath: String,
        name: String,
        create: (String) -> WorkspaceFileEntry,
    ): WorkspaceFileEntry {
        val safe = safeName(name)
        val dot = safe.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { safe.substring(0, it) } ?: safe
        val ext = dot?.let { safe.substring(it) } ?: ""
        for (n in 0..MAX_NAME_ATTEMPTS) {
            val candidate = if (n == 0) safe else "$stem ($n)$ext"
            val path = joinPath(parentPath, candidate)
            if (backend().stat(root, path).exists) continue
            try {
                return create(path)
            } catch (_: WorkspaceFileAlreadyExistsException) {
                continue
            } catch (error: IllegalArgumentException) {
                if (error.message?.contains("already exists") == true) continue
                throw error
            }
        }
        throw IllegalStateException("No free name for $name")
    }

    private fun parseDocId(documentId: String): DocId {
        if (documentId == ROOT_DOC_ID) return DocId(isRoot = true, root = "", relPath = "")
        require(documentId.startsWith(DOC_PREFIX)) { "Invalid documentId: $documentId" }
        val rest = documentId.removePrefix(DOC_PREFIX)
        val idx = rest.indexOf('/')
        return if (idx < 0) {
            DocId(isRoot = false, root = rest, relPath = "")
        } else {
            DocId(isRoot = false, root = rest.substring(0, idx), relPath = rest.substring(idx + 1))
        }
    }

    private fun buildDocId(root: String, relPath: String): String =
        if (relPath.isEmpty()) "$DOC_PREFIX$root" else "$DOC_PREFIX$root/$relPath"

    private fun notifyChange(parentDocumentId: String) {
        val ctx = context ?: return
        val uri = DocumentsContract.buildChildDocumentsUri(
            ctx.packageName + ".documents",
            parentDocumentId,
        )
        ctx.contentResolver.notifyChange(uri, null)
    }

    private data class DocId(
        val isRoot: Boolean,
        val root: String,
        val relPath: String,
    )

    companion object {
        private const val ROOT_ID = "rikkihub_workspaces"
        private const val ROOT_DOC_ID = "root"
        private const val DOC_PREFIX = "ws/"
        private const val MAX_NAME_ATTEMPTS = 1000

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
