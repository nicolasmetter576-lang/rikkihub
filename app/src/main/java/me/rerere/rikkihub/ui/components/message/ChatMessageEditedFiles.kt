package me.rerere.rikkihub.ui.components.message

import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.hasUnavailableOutcome
import me.rerere.rikkihub.data.cli.AcpParts
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.amoledOutline
import me.rerere.workspace.WorkspaceFilesScope
import me.rerere.workspace.WorkspaceManager
import org.koin.compose.koinInject

private const val DEFAULT_VISIBLE_COUNT = 3
private val WORKSPACE_FILE_TOOL_NAMES = setOf(
    "workspace_write_file",
    "workspace_edit_file",
    "str_replace_editor",
)
private val DENIED_ACP_KINDS = setOf(
    "delete",
    "read",
    "search",
    "fetch",
    "think",
    "plan",
    "execute",
)
private val ACP_MUTATION_TOOL_NAMES = setOf(
    "write",
    "write_file",
    "write_to_file",
    "create_file",
    "save_file",
    "edit",
    "edit_file",
    "multi_edit",
    "multiedit",
    "notebook_edit",
    "notebookedit",
    "replace_file_content",
    "multi_replace_file_content",
    "str_replace",
    "str_replace_editor",
    "patch",
    "apply_patch",
    "apply_diff",
    "update_file",
    "modify_file",
    "insert_text",
    "move",
    "move_file",
    "rename",
    "rename_file",
    "workspace_write_file",
    "workspace_edit_file",
)
private val ACP_MOVE_TARGET_KEYS = listOf(
    "destination",
    "Destination",
    "target_file",
    "TargetFile",
    "to",
    "new_path",
    "newPath",
    "dest",
    "dest_path",
    "destPath",
    "target",
    "targetPath",
    "target_path",
)
private val ACP_FALLBACK_INPUT_KEYS = listOf(
    "path",
    "file_path",
    "filePath",
    "target_file",
    "TargetFile",
    "destination",
    "Destination",
    "AbsolutePath",
)
private val URI_SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

internal fun extractEditedFilePaths(
    parts: List<UIMessagePart>,
    workspaceCwd: String? = null,
    workspaceCwdScoped: Boolean = false,
): List<String> = parts
    .filterIsInstance<UIMessagePart.Tool>()
    .flatMap { tool ->
        when {
            tool.toolName in WORKSPACE_FILE_TOOL_NAMES -> listOfNotNull(extractWorkspaceToolPath(tool))
            AcpParts.isAcpTool(tool) -> extractAcpEditedFilePaths(tool, workspaceCwd, workspaceCwdScoped)
            else -> emptyList()
        }
    }
    .distinct()

private fun extractWorkspaceToolPath(tool: UIMessagePart.Tool): String? {
    // 被中断/被关闭、从未执行的调用（unavailable）没写过文件
    if (!tool.isExecuted || tool.hasUnavailableOutcome(Json)) return null
    val input = tool.inputAsJson() as? JsonObject ?: return null
    if (tool.toolName == "str_replace_editor") {
        val command = input.stringOrNull("command")
        if (command != "create" && command != "str_replace" && command != "insert") {
            return null
        }
    }
    return input.stringOrNull("path")
}

private fun extractAcpEditedFilePaths(
    tool: UIMessagePart.Tool,
    workspaceCwd: String?,
    workspaceCwdScoped: Boolean,
): List<String> {
    if (AcpParts.isLocalCommand(tool)) return emptyList()
    if (!tool.isExecuted || tool.approvalState is ToolApprovalState.Denied) return emptyList()
    if (AcpParts.status(tool) != "completed") return emptyList()
    if (tool.toolName == AcpParts.ASK_QUESTION) return emptyList()

    val acp = tool.metadata?.get(AcpParts.META_KEY) as? JsonObject ?: return emptyList()
    val kind = acp.stringOrNull("kind")?.lowercase()
    if (kind in DENIED_ACP_KINDS) return emptyList()

    val diffPaths = (acp["content"] as? JsonArray).orEmpty().mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        if (obj.stringOrNull("type") != "diff") return@mapNotNull null
        obj.stringOrNull("path")?.takeIf { it.isNotBlank() }
    }
    val inputObj = tool.inputAsJson() as? JsonObject
    val acpRawInput = acp["rawInput"] as? JsonObject
    val strippedName = tool.toolName.removePrefix(AcpParts.TOOL_PREFIX).lowercase()

    val allowed = when (kind) {
        "edit", "move" -> true
        null, "other" -> {
            val strReplaceOk = strippedName != "str_replace_editor" ||
                inputObj?.stringOrNull("command") != "view"
            diffPaths.isNotEmpty() || (strippedName in ACP_MUTATION_TOOL_NAMES && strReplaceOk)
        }
        else -> false
    }
    if (!allowed) return emptyList()

    val candidates = when {
        diffPaths.isNotEmpty() -> diffPaths
        kind == "move" -> extractAcpMoveCandidates(acp, inputObj, acpRawInput)
        else -> {
            val locationPaths = extractAcpLocationPaths(acp)
            if (locationPaths.isNotEmpty()) {
                locationPaths
            } else {
                listOfNotNull(firstInputPath(inputObj, acpRawInput, ACP_FALLBACK_INPUT_KEYS))
            }
        }
    }

    return candidates.mapNotNull { raw ->
        resolvePreviewableAcpPath(raw, workspaceCwd, workspaceCwdScoped)
    }
}

private fun extractAcpMoveCandidates(
    acp: JsonObject,
    inputObj: JsonObject?,
    acpRawInput: JsonObject?,
): List<String> {
    firstInputPath(inputObj, acpRawInput, ACP_MOVE_TARGET_KEYS)?.let { return listOf(it) }
    extractAcpLocationPaths(acp).lastOrNull()?.let { return listOf(it) }
    val remainingKeys = ACP_FALLBACK_INPUT_KEYS.filterNot { it in ACP_MOVE_TARGET_KEYS }
    return listOfNotNull(firstInputPath(inputObj, acpRawInput, remainingKeys))
}

private fun extractAcpLocationPaths(acp: JsonObject): List<String> =
    (acp["locations"] as? JsonArray).orEmpty().mapNotNull { item ->
        (item as? JsonObject)?.stringOrNull("path")?.takeIf { it.isNotBlank() }
    }

private fun firstInputPath(
    primary: JsonObject?,
    secondary: JsonObject?,
    keys: List<String>,
): String? {
    for (key in keys) {
        primary?.stringOrNull(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    for (key in keys) {
        secondary?.stringOrNull(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun resolvePreviewableAcpPath(
    raw: String,
    workspaceCwd: String?,
    workspaceCwdScoped: Boolean,
): String? {
    val scopeDir = try {
        WorkspaceFilesScope.normalize(workspaceCwd)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if ('\u0000' in raw || '\\' in raw) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.endsWith('/') || trimmed.startsWith("//") || trimmed == "." || trimmed.endsWith("/.")) {
        return null
    }
    if (URI_SCHEME_REGEX.containsMatchIn(trimmed)) return null

    val effectiveCwd = scopeDir?.let(WorkspaceFilesScope::guestPath) ?: WorkspaceManager.ROOTFS_WORKSPACE_DIR
    val combined = if (trimmed.startsWith('/')) trimmed else "$effectiveCwd/$trimmed"
    val rawSegments = combined.split('/')
    if (rawSegments.any { it == ".." }) return null

    val segments = rawSegments.filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty()) return null
    val normalized = "/" + segments.joinToString("/")

    val workspacePrefix = "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/"
    if (!normalized.startsWith(workspacePrefix)) return null

    if (workspaceCwdScoped && scopeDir != null) {
        val relative = normalized.removePrefix(workspacePrefix)
        if (!WorkspaceFilesScope.contains(scopeDir, relative)) return null
    }
    return normalized
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditedFilesList(
    parts: List<UIMessagePart>,
    assistant: Assistant?,
    onPreviewFile: ((String) -> Unit)? = null,
    workspaceCwd: String? = null,
    workspaceCwdScoped: Boolean = false,
) {
    val workspaceId = assistant?.workspaceId?.toString() ?: return
    val editedFiles = remember(parts, workspaceCwd, workspaceCwdScoped) {
        extractEditedFilePaths(
            parts = parts,
            workspaceCwd = workspaceCwd,
            workspaceCwdScoped = workspaceCwdScoped,
        )
    }
    if (editedFiles.isEmpty()) return

    val scopeResult = remember(workspaceCwd, workspaceCwdScoped) {
        runCatching { WorkspaceFilesScope.normalize(workspaceCwd).takeIf { workspaceCwdScoped } }
    }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val visibleFiles = if (expanded) editedFiles else editedFiles.take(DEFAULT_VISIBLE_COUNT)
    val hasMore = editedFiles.size > DEFAULT_VISIBLE_COUNT

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visibleFiles.forEach { path ->
            val fileName = remember(path) { path.substringAfterLast('/') }
            Surface(
                onClick = {
                    if (onPreviewFile != null) {
                        onPreviewFile(path)
                    } else if (scopeResult.isSuccess) {
                        selectedPath = path
                    }
                },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                }
            }
        }
        if (hasMore && !expanded) {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "+${editedFiles.size - DEFAULT_VISIBLE_COUNT}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }

    selectedPath?.let { path ->
        if (scopeResult.isSuccess) {
            EditedFileActionsSheet(
                workspaceId = workspaceId,
                path = path,
                filesScope = scopeResult.getOrThrow(),
                onDismiss = { selectedPath = null },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EditedFilePreview(
    workspaceId: String,
    path: String,
    workspaceCwd: String?,
    workspaceCwdScoped: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository: WorkspaceRepository = koinInject()
    val context = LocalContext.current
    val dismiss = rememberUpdatedState(onDismiss)
    val detector = remember(context) {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                dismiss.value()
                return true
            }
        })
    }
    val scopeResult = remember(workspaceCwd, workspaceCwdScoped) {
        runCatching { WorkspaceFilesScope.normalize(workspaceCwd).takeIf { workspaceCwdScoped } }
    }
    var content by remember(workspaceId, path, scopeResult) { mutableStateOf<Result<String>?>(null) }
    var retry by remember { mutableStateOf(0) }
    var showActions by remember { mutableStateOf(false) }
    LaunchedEffect(workspaceId, path, scopeResult, retry) {
        content = null
        content = try {
            Result.success(repository.readRootfsText(
                workspaceId, path, maxBytes = 8L * 1024 * 1024,
                filesScope = scopeResult.getOrThrow(),
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    BackHandler(onBack = onDismiss)
    Surface(
        modifier = modifier.fillMaxSize()
            .amoledOutline(MaterialTheme.shapes.medium)
            .semantics { testTagsAsResourceId = true }
            .testTag("workspace-file-preview"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showActions = true }, enabled = scopeResult.isSuccess) {
                    Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("workspace-file-preview-close")) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.workspace_terminal_close))
                }
            }
            // Observe double taps without consuming the text field's selection or scrolling gestures.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .motionEventSpy { detector.onTouchEvent(it) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val loaded = content
                when {
                    loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    loaded.isFailure -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.chat_input_file_read_failed, loaded.exceptionOrNull()?.message.orEmpty()),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { retry++ }) {
                            Text(stringResource(R.string.workspace_editor_retry))
                        }
                    }
                    else -> {
                        val text = loaded.getOrThrow()
                        val state = remember(text) { TextFieldState(text) }
                        BasicTextField(
                            state = state,
                            readOnly = true,
                            modifier = Modifier.fillMaxSize().testTag("workspace-file-preview-text"),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        if (text.isEmpty()) {
                            Text(
                                stringResource(R.string.workspace_editor_empty),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            }
        }
    }
    if (showActions) {
        EditedFileActionsSheet(
            workspaceId, path, scopeResult.getOrThrow(),
            onDismiss = { showActions = false },
        )
    }
}

@Composable
private fun EditedFileActionsSheet(
    workspaceId: String,
    path: String,
    filesScope: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        workspaceRepository.exportRootfsFile(workspaceId, path, output, filesScope)
                    }
                }
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show(e.message.orEmpty(), type = ToastType.Error)
            }
        }
    }
    val fileName = remember(path) { path.substringAfterLast('/') }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Card(
                onClick = {
                    exportLauncher.launch(fileName)
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = HugeIcons.FileImport,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp),
                    )
                    Text(
                        text = stringResource(R.string.common_export),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Card(
                onClick = {
                    scope.launch {
                        try {
                            val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                            val file = File(dir, fileName)
                            withContext(Dispatchers.IO) {
                                file.outputStream().use { output ->
                                    workspaceRepository.exportRootfsFile(workspaceId, path, output, filesScope)
                                }
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toaster.show(e.message.orEmpty(), type = ToastType.Error)
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = HugeIcons.Share08,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp),
                    )
                    Text(
                        text = stringResource(R.string.common_share),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
