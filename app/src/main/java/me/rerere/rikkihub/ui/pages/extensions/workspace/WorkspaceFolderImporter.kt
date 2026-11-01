package me.rerere.rikkihub.ui.pages.extensions.workspace

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import me.rerere.workspace.WorkspaceImportNode
import java.io.InputStream

/**
 * 把 ACTION_OPEN_DOCUMENT_TREE 返回的 tree Uri 适配成 workspace 模块的
 * [WorkspaceImportNode]: 纯 DocumentsContract 递归查询, 不引入 DocumentFile 依赖.
 *
 * 安全边界:
 * - 查询返回 null cursor / 根文档查不到 / documentId 列缺失等 provider 异常,
 *   一律显式抛错, 绝不静默当成功
 * - displayName 原样上抛 (可以是 null), 不在这里拼路径;
 *   清洗与冲突命名都在 workspace 模块的 WorkspaceTreeImporter / WorkspaceNames
 * - mime 列缺失/为 null 属 provider 残缺, 显式报错 (避免目录被当文件导入);
 *   自称目录却列不出子节点的会在 listChildren 抛错
 */
class WorkspaceFolderImporter(
    private val contentResolver: ContentResolver,
) {
    /** 查询 tree Uri 的根文档; provider 不可读或不是目录时报错 */
    fun openTree(treeUri: Uri): WorkspaceImportNode {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            ?: error("Not a document tree uri: $treeUri")
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return queryDocument(treeUri, rootUri)
            ?: error("Document provider did not return the selected folder")
    }

    private inner class TreeNode(
        private val treeUri: Uri,
        private val documentUri: Uri,
        override val stableId: String,
        override val displayName: String?,
        override val isDirectory: Boolean,
    ) : WorkspaceImportNode {
        override fun listChildren(): List<WorkspaceImportNode> {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                stableId,
            )
            val cursor = contentResolver.query(childrenUri, PROJECTION, null, null, null)
                ?: error("Document provider returned no child list for: $stableId")
            return cursor.use {
                buildList {
                    while (it.moveToNext()) {
                        it.toNode(treeUri)?.let(::add)
                    }
                }
            }
        }

        override fun openInputStream(): InputStream? =
            contentResolver.openInputStream(documentUri)
    }

    private fun queryDocument(treeUri: Uri, documentUri: Uri): WorkspaceImportNode? {
        val cursor = contentResolver.query(documentUri, PROJECTION, null, null, null)
            ?: error("Document provider returned no metadata for: $documentUri")
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            it.toNode(treeUri)
        }
    }

    private fun Cursor.toNode(treeUri: Uri): TreeNode? {
        val idIndex = getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        if (idIndex < 0) return null
        val documentId = getString(idIndex)?.takeIf { it.isNotBlank() } ?: return null
        val nameIndex = getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        // mime 缺失时若把目录误判为文件, 会以 "no content" 这种误导错误收尾;
        // provider 残缺一律显式报错
        require(mimeIndex >= 0 && getString(mimeIndex) != null) {
            "Document provider returned no mime type for: $documentId"
        }
        return TreeNode(
            treeUri = treeUri,
            documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            stableId = documentId,
            displayName = if (nameIndex >= 0) getString(nameIndex) else null,
            isDirectory = getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR,
        )
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}

/**
 * 尽力删除一个新建的 SAF 文档 (批量导出失败时清理半成品 ZIP).
 * 只应作用于本次 CreateDocument 刚拿到、且已开始写入的 uri; 删除失败静默忽略.
 */
fun deleteDocumentQuietly(contentResolver: ContentResolver, uri: Uri) {
    runCatching {
        DocumentsContract.deleteDocument(contentResolver, uri)
    }
}
