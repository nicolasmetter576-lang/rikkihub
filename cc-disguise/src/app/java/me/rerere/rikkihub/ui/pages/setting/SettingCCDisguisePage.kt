package me.rerere.rikkihub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Card
import me.rerere.rikkihub.ui.components.ui.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ccdisguise.ClaudeCodeDisguise
import me.rerere.ai.ccdisguise.ClaudeCodeDisguiseConfig
import me.rerere.ai.ccdisguise.ClaudeCodeIdentity
import me.rerere.ai.ccdisguise.UserIdMode
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.RotateClockwise
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.Switch
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel

private val ClaudeCodeDisguiseConfigSaver = Saver<ClaudeCodeDisguiseConfig, String>(
    save = { config ->
        JsonInstant.encodeToString(ClaudeCodeDisguiseConfig.serializer(), config)
    },
    restore = { encoded ->
        runCatching {
            JsonInstant.decodeFromString(ClaudeCodeDisguiseConfig.serializer(), encoded)
        }.getOrNull()
    },
)

@Composable
fun SettingCCDisguisePage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val identity = remember { ClaudeCodeIdentity(context) }

    var initialConfig by remember(settings.ccDisguiseConfig) { mutableStateOf(settings.ccDisguiseConfig) }

    // autoUserId 未初始化时从 legacy 文件读取或生成。文件 IO 走 IO dispatcher，
    // 生成后同时回写 settings.ccDisguiseConfig 和 legacy 文件（identity.syncFile 也建目录）。
    LaunchedEffect(initialConfig.autoUserId, settings.ccDisguiseConfig) {
        if (initialConfig.autoUserId.isNotBlank()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) { identity.userId() }
        initialConfig = initialConfig.copy(autoUserId = resolved)
        vm.updateSettings { latest ->
            if (latest.ccDisguiseConfig.autoUserId.isNotBlank()) latest
            else latest.copy(ccDisguiseConfig = latest.ccDisguiseConfig.copy(autoUserId = resolved))
        }
    }

    // Draft state for editing. Config is JSON-saved because the Bundle registry cannot
    // persist arbitrary @Serializable objects with rememberSaveable's default saver.
    var draft by rememberSaveable(
        initialConfig,
        stateSaver = ClaudeCodeDisguiseConfigSaver,
    ) {
        mutableStateOf(initialConfig)
    }
    val hasChanges = draft != initialConfig

    // Dialog states
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showRegenDialog by remember { mutableStateOf(false) }
    var showResetAllDialog by remember { mutableStateOf(false) }

    // Back handler for unsaved changes
    val tryGoBack = {
        if (hasChanges) {
            showDiscardDialog = true
        } else {
            navController.popBackStack()
        }
    }
    BackHandler(enabled = hasChanges) {
        showDiscardDialog = true
    }

    // Validation
    fun validate(): String? {
        if (draft.systemPromptEnabled && draft.systemPrompt.isBlank()) {
            return context.getString(R.string.setting_page_cc_disguise_validation_system_empty)
        }
        if (draft.userIdMode == UserIdMode.MANUAL && draft.manualUserId.isBlank()) {
            return context.getString(R.string.setting_page_cc_disguise_userid_manual_empty)
        }
        fun checkHeader(value: String, fieldName: String): String? {
            if (value.isBlank()) return context.getString(R.string.setting_page_cc_disguise_validation_header_empty) + ": $fieldName"
            if (value.contains('\n') || value.contains('\r')) return context.getString(R.string.setting_page_cc_disguise_validation_header_newline) + ": $fieldName"
            return null
        }
        checkHeader(draft.userAgent, "User-Agent")?.let { return it }
        checkHeader(draft.xApp, "x-app")?.let { return it }
        // Beta tags: at least one non-empty tag
        val betaTags = draft.anthropicBeta.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (betaTags.isEmpty()) {
            return context.getString(R.string.setting_page_cc_disguise_validation_beta_empty)
        }
        if (draft.anthropicBeta.contains('\n') || draft.anthropicBeta.contains('\r')) {
            return context.getString(R.string.setting_page_cc_disguise_validation_header_newline) + ": anthropic-beta"
        }
        return null
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CC Disguise", text))
        toaster.show(context.getString(R.string.setting_page_cc_disguise_copied), type = ToastType.Normal)
    }

    fun save() {
        val error = validate()
        if (error != null) {
            toaster.show(error, type = ToastType.Error)
            return
        }
        val trimmed = draft.copy(
            systemPrompt = draft.systemPrompt, // preserve newlines
            manualUserId = draft.manualUserId.trim(),
            userAgent = draft.userAgent.trim(),
            xApp = draft.xApp.trim(),
            anthropicBeta = draft.anthropicBeta.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
        )
        vm.updateSettings { latest -> latest.copy(ccDisguiseConfig = trimmed) }
        // Sync legacy file (syncFile 内部建目录并更新内存缓存)
        scope.launch(Dispatchers.IO) { identity.syncFile(trimmed.autoUserId) }
        toaster.show(context.getString(R.string.setting_page_cc_disguise_saved), type = ToastType.Success)
    }

    // Discard dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.setting_page_cc_disguise_discard_title)) },
            text = { Text(stringResource(R.string.setting_page_cc_disguise_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    navController.popBackStack()
                }) {
                    Text(stringResource(R.string.setting_page_cc_disguise_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.setting_page_cc_disguise_discard_cancel))
                }
            }
        )
    }

    // Regenerate ID dialog
    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text(stringResource(R.string.setting_page_cc_disguise_userid_regenerate)) },
            text = { Text(stringResource(R.string.setting_page_cc_disguise_userid_regenerate_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(autoUserId = ClaudeCodeIdentity.generate())
                    showRegenDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Reset all dialog
    if (showResetAllDialog) {
        AlertDialog(
            onDismissRequest = { showResetAllDialog = false },
            title = { Text(stringResource(R.string.setting_page_cc_disguise_reset_all)) },
            text = { Text(stringResource(R.string.setting_page_cc_disguise_reset_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    draft = ClaudeCodeDisguiseConfig.DEFAULT.copy(autoUserId = draft.autoUserId)
                    showResetAllDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_cc_disguise)) },
                navigationIcon = {
                    BackButton(
                        modifier = Modifier,
                        onClick = { tryGoBack() }
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    // Save button
                    TextButton(
                        onClick = { save() },
                        enabled = hasChanges,
                    ) {
                        Text(stringResource(R.string.setting_page_cc_disguise_save))
                    }
                }
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Global hint
            item {
                Card(modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.setting_page_cc_disguise_global_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // ── System Identity ──
            item {
                SectionHeader(stringResource(R.string.setting_page_cc_disguise_system_section))
            }
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_cc_disguise_system_enabled)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_cc_disguise_system_enabled_desc)) },
                        trailingContent = {
                            Switch(
                                checked = draft.systemPromptEnabled,
                                onCheckedChange = { draft = draft.copy(systemPromptEnabled = it) }
                            )
                        }
                    )
                }
            }
            if (draft.systemPromptEnabled) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        OutlinedTextField(
                            value = draft.systemPrompt,
                            onValueChange = { draft = draft.copy(systemPrompt = it) },
                            label = { Text(stringResource(R.string.setting_page_cc_disguise_system_prompt)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 5,
                            isError = draft.systemPromptEnabled && draft.systemPrompt.isBlank(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                draft = draft.copy(systemPrompt = ClaudeCodeDisguise.IDENTITY)
                            }) {
                                Text(stringResource(R.string.setting_page_cc_disguise_reset_field))
                            }
                        }
                    }
                }
            }

            // ── User ID ──
            item {
                SectionHeader(stringResource(R.string.setting_page_cc_disguise_userid_section))
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = draft.userIdMode == UserIdMode.AUTO,
                            onClick = { draft = draft.copy(userIdMode = UserIdMode.AUTO) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) {
                            Text(stringResource(R.string.setting_page_cc_disguise_userid_mode_auto))
                        }
                        SegmentedButton(
                            selected = draft.userIdMode == UserIdMode.MANUAL,
                            onClick = { draft = draft.copy(userIdMode = UserIdMode.MANUAL) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) {
                            Text(stringResource(R.string.setting_page_cc_disguise_userid_mode_manual))
                        }
                    }
                }
            }
            if (draft.userIdMode == UserIdMode.AUTO) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = stringResource(R.string.setting_page_cc_disguise_userid_auto_current),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = draft.autoUserId.ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { copyToClipboard(draft.autoUserId) }) {
                                Icon(HugeIcons.Copy01, stringResource(R.string.setting_page_cc_disguise_copied))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showRegenDialog = true }) {
                                Icon(HugeIcons.RotateClockwise, null)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.setting_page_cc_disguise_userid_regenerate))
                            }
                        }
                    }
                }
            }
            if (draft.userIdMode == UserIdMode.MANUAL) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        OutlinedTextField(
                            value = draft.manualUserId,
                            onValueChange = { draft = draft.copy(manualUserId = it) },
                            label = { Text(stringResource(R.string.setting_page_cc_disguise_userid_manual_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = draft.userIdMode == UserIdMode.MANUAL && draft.manualUserId.isBlank(),
                            trailingIcon = {
                                if (draft.manualUserId.isNotBlank()) {
                                    IconButton(onClick = { copyToClipboard(draft.manualUserId) }) {
                                        Icon(HugeIcons.Copy01, null)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // ── CLI Request Headers ──
            item {
                SectionHeader(stringResource(R.string.setting_page_cc_disguise_headers_section))
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // User-Agent
                    HeaderField(
                        label = stringResource(R.string.setting_page_cc_disguise_header_user_agent),
                        value = draft.userAgent,
                        onValueChange = { draft = draft.copy(userAgent = it) },
                        defaultValue = ClaudeCodeDisguise.USER_AGENT,
                        onReset = { draft = draft.copy(userAgent = ClaudeCodeDisguise.USER_AGENT) },
                    )
                    // x-app
                    HeaderField(
                        label = stringResource(R.string.setting_page_cc_disguise_header_x_app),
                        value = draft.xApp,
                        onValueChange = { draft = draft.copy(xApp = it) },
                        defaultValue = ClaudeCodeDisguise.X_APP,
                        onReset = { draft = draft.copy(xApp = ClaudeCodeDisguise.X_APP) },
                    )
                    // anthropic-beta
                    HeaderField(
                        label = stringResource(R.string.setting_page_cc_disguise_header_beta),
                        value = draft.anthropicBeta,
                        onValueChange = { draft = draft.copy(anthropicBeta = it) },
                        defaultValue = ClaudeCodeDisguise.BETA_TAG,
                        onReset = { draft = draft.copy(anthropicBeta = ClaudeCodeDisguise.BETA_TAG) },
                        placeholder = stringResource(R.string.setting_page_cc_disguise_header_beta_hint),
                    )
                }
            }

            // ── Reset all ──
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(onClick = { showResetAllDialog = true }) {
                        Text(stringResource(R.string.setting_page_cc_disguise_reset_all))
                    }
                }
            }

            // Bottom spacer
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun HeaderField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    defaultValue: String,
    onReset: () -> Unit,
    placeholder: String? = null,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = value.isBlank() || value.contains('\n') || value.contains('\r'),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        if (value != defaultValue) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.setting_page_cc_disguise_reset_field))
                }
            }
        }
    }
}

/**
 * Custom BackButton that accepts an onClick override for unsaved-changes interception.
 */
@Composable
private fun BackButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier,
        shapes = androidx.compose.material3.IconButtonDefaults.shapes(),
        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
    ) {
        Icon(
            imageVector = HugeIcons.ArrowLeft01,
            contentDescription = stringResource(R.string.back)
        )
    }
}
