package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.ui.hooks.ChatInputState
import org.koin.compose.koinInject
import me.rerere.rikkihub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkihub.ui.components.sketch.SketchDialog
import me.rerere.rikkihub.R
import me.rerere.hugeicons.stroke.PaintBoard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.IconButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

private const val DRAFT_LEASE_HEARTBEAT_MILLIS = 3 * 60 * 1000L // 3min，远小于 24h 租约 TTL

@Composable
internal fun MediaFileInputRow(
    state: ChatInputState,
) {
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()

    // 草稿附件租约心跳：附件停留在草稿期间服务端看不到引用，
    // 只能靠 FilesManager.attachmentLeases 保护不被存储清理误删。
    // 本行在附件非空时保持组合（主输入框与队列编辑弹窗共用），
    // 出组合（发送/清空/取消编辑/页面销毁）后自动停，租约 24h 自然过期。
    // 应用切后台时 Compose 继续运行，心跳照常；进程被杀则依赖 24h
    // 创建保护 + 重启兜底。续租本身是非挂起的短临界区，无锁泄漏风险。
    LaunchedEffect(state, filesManager) {
        while (true) {
            val urls = state.draftAttachmentFileUrls()
            if (urls.isNotEmpty()) {
                runCatching { filesManager.renewUploadLeases(urls) }
            }
            delay(DRAFT_LEASE_HEARTBEAT_MILLIS)
        }
    }

    val managedFiles by filesManager.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val displayNameByRelativePath = remember(managedFiles) {
        managedFiles.associate { it.relativePath to it.displayName }
    }
    val displayNameByFileName = remember(managedFiles) {
        managedFiles.associate { it.relativePath.substringAfterLast('/') to it.displayName }
    }

    fun removePart(part: UIMessagePart, url: String) {
        state.messageContent = state.messageContent.filterNot { it == part }
        if (state.shouldDeleteFileOnRemove(part)) {
            scope.launch(Dispatchers.IO) {
                filesManager.deleteChatFiles(listOf(url.toUri()))
            }
        }
    }

    // 正在哪张图片上画，非空时显示画板
    var drawingOn by remember { mutableStateOf<UIMessagePart.Image?>(null) }
    drawingOn?.let { part ->
        SketchDialog(
            image = part.url.toUri(),
            onDismiss = { drawingOn = null },
            onConfirm = { result ->
                drawingOn = null
                scope.launch {
                    val uri = filesManager.createChatFileBySketch(result) ?: return@launch
                    // 画好的图换掉原来那张，位置不变；保存的这会儿原图已经被移走的话就不加了
                    if (part !in state.messageContent) {
                        filesManager.deleteChatFiles(listOf(uri))
                        return@launch
                    }
                    state.messageContent = state.messageContent.map {
                        if (it == part) UIMessagePart.Image(uri.toString()) else it
                    }
                    if (state.shouldDeleteFileOnRemove(part)) {
                        launch(Dispatchers.IO) { filesManager.deleteChatFiles(listOf(part.url.toUri())) }
                    }
                }
            },
        )
    }

    // 点开预览时从哪张图片开始，非空时显示预览；预览里可以切到画板在当前图片上画
    var previewing by remember { mutableStateOf<UIMessagePart.Image?>(null) }
    previewing?.let { start ->
        val images = state.messageContent.filterIsInstance<UIMessagePart.Image>()
        ImagePreviewDialog(
            images = images.map { it.url },
            initialPage = images.indexOf(start).coerceAtLeast(0),
            onDismissRequest = { previewing = null },
            actions = { page ->
                IconButton(
                    onClick = {
                        previewing = null
                        drawingOn = images.getOrNull(page)
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.PaintBoard,
                        contentDescription = stringResource(R.string.sketch_draw_on_image),
                        tint = Color.White,
                    )
                }
            },
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        state.messageContent.fastForEach { part ->
            when (part) {
                is UIMessagePart.Image -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "image",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = {
                            Surface(
                                modifier = Modifier.size(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                AsyncImage(
                                    model = part.url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        onRemove = { removePart(part, part.url) },
                        onClick = { previewing = part },
                    )
                }

                is UIMessagePart.Video -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "video",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Video01) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Audio -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "audio",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.MusicNote03) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Document -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = part.fileName,
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Files02) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    title: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            leading()
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 40.dp, max = 180.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentLeadingIcon(
    icon: ImageVector,
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun attachmentNameFromUrl(
    url: String,
    fallback: String,
    displayNameByRelativePath: Map<String, String>,
    displayNameByFileName: Map<String, String>,
): String {
    val parsed = runCatching { url.toUri() }.getOrNull()
    val relativePath = parsed?.path?.substringAfter("/files/", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    if (relativePath != null) {
        displayNameByRelativePath[relativePath]?.let { return it }
    }

    val storedFileName = parsed?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    if (storedFileName != null) {
        displayNameByFileName[storedFileName]?.let { return it }
        return storedFileName
    }

    return fallback
}
