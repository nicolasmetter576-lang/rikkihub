package me.rerere.rikkihub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.CacheEntry
import me.rerere.common.cache.CacheStore
import me.rerere.common.cache.LruCache
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkihub.data.workspace.workspacePath
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    // 解析结果按 (url, mtime, size) 缓存：同一次生成多次重试、分支重发不必重复解析 PDF/Office 文档。
    private val parseCache = LruCache<String, String>(
        capacity = 32,
        store = NoopCacheStore,
    )
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                // 有请求能力快照时按真实挂载判定 /upload；无快照走旧开关值。
                                val exposeUpload = ctx.workspaceCapabilities?.let {
                                    "/upload" in it.exposedTargets
                                } ?: ctx.exposeUpload
                                // Copy inside a confined working directory wins over /upload.
                                val path = document.workspacePath ?: resolveWorkspacePath(document, exposeUpload)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                        filterIsInstance<UIMessagePart.Image>().mapNotNull { it.workspacePath }.forEach { path ->
                            add(0, UIMessagePart.Text("<UploadFile kind=\"image\" path=\"$path\" />"))
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parsePdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // 上传文件保存在 filesDir/upload 下, 默认工作区通过 proot 挂载到 /upload。
    // Harness 极简工作区不挂 /upload, 因此不把这条路径告诉模型。
    private fun resolveWorkspacePath(
        document: UIMessagePart.Document,
        exposeUpload: Boolean,
    ): String? {
        if (!exposeUpload) return null
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        val cacheKey = "${document.url}#${file.lastModified()}:${file.length()}"
        parseCache.get(cacheKey)?.let { return it }
        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }.also { parseCache.put(cacheKey, it) }
    }
}

private object NoopCacheStore : CacheStore<String, String> {
    override fun loadEntry(key: String): CacheEntry<String>? = null
    override fun saveEntry(key: String, entry: CacheEntry<String>) = Unit
    override fun remove(key: String) = Unit
    override fun clear() = Unit
    override fun loadAllEntries(): Map<String, CacheEntry<String>> = emptyMap()
    override fun keys(): Set<String> = emptySet()
}
