package me.rerere.rikkihub.ui.pages.setting

import me.rerere.rikkihub.ui.components.ui.rememberSettingSearchListState
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.AppSpacing
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.qubagu.Qubagu
import me.rerere.rikkihub.data.ai.qubagu.QubaguConfig
import me.rerere.rikkihub.data.ai.qubagu.QubaguImportChoice
import me.rerere.rikkihub.data.ai.qubagu.QubaguMode
import me.rerere.rikkihub.data.ai.qubagu.QubaguRecord
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefine
import me.rerere.rikkihub.data.ai.qubagu.QubaguRule
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulePack
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulesException
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulesJson
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.ui.components.ai.ModelSelector
import me.rerere.rikkihub.ui.components.message.QubaguEditRow
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField
import me.rerere.rikkihub.ui.components.ui.DropdownMenu
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val WORD_SEPARATORS = Regex("[,，、\\s]+")

internal val QubaguMode.labelRes: Int
    get() = when (this) {
        QubaguMode.AUTO -> R.string.qubagu_mode_auto
        QubaguMode.REVIEW -> R.string.qubagu_mode_review
    }

/** 正在编辑的规则：[packId] 是它所在的包，[isNew] 时保存会追加到包末尾。 */
private data class RuleDraft(val packId: String, val rule: QubaguRule, val isNew: Boolean)

/** 导入到一半、等用户决定冲突包怎么处理的规则文件。 */
private class PendingImport(val packs: List<QubaguRulePack>, val invalid: List<String>, val conflicts: List<QubaguRulePack>)

private fun QubaguConfig.mapPack(id: String, transform: (QubaguRulePack) -> QubaguRulePack) =
    copy(packs = packs.map { if (it.id == id) transform(it) else it })

private fun exportFileName(): String = "qubagu-rules-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json"

/**
 * 去八股设置：处理方式、不改的内容、碎句合并、规则包（导入导出、逐条编辑）、AI 精修、试一试。
 * 聊天里的总开关在输入框的「+」面板（长按进这一页）。App 不内置规则。
 */
@Composable
fun SettingQubaguPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.qubagu
    val runtime = koinInject<TavernRuntime>()
    val engine = remember(runtime) { Qubagu(runtime.regexFactory) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var editingRule by remember { mutableStateOf<RuleDraft?>(null) }
    var editingPack by remember { mutableStateOf<QubaguRulePack?>(null) }
    var deletingPack by remember { mutableStateOf<QubaguRulePack?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var invalidRules by remember { mutableStateOf<List<String>>(emptyList()) }
    var exporting by remember { mutableStateOf<List<QubaguRulePack>>(emptyList()) }

    val importDone = stringResource(R.string.qubagu_import_done)
    val exportDone = stringResource(R.string.qubagu_export_done)
    val errorTexts = mapOf(
        QubaguRulesException.Reason.NOT_JSON to stringResource(R.string.qubagu_import_error_not_json),
        QubaguRulesException.Reason.WRONG_TYPE to stringResource(R.string.qubagu_import_error_wrong_type),
        QubaguRulesException.Reason.NEWER_VERSION to stringResource(R.string.qubagu_import_error_newer),
        QubaguRulesException.Reason.INVALID to stringResource(R.string.qubagu_import_error_invalid),
    )
    val ioFailed = stringResource(R.string.qubagu_file_failed)

    fun finishImport(pending: PendingImport, choices: Map<String, QubaguImportChoice>) {
        val merged = QubaguRulesJson.merge(vm.settings.value.qubagu.packs, pending.packs) { choices[it.id] ?: QubaguImportChoice.OVERWRITE }
        QubaguRulesJson.validate(merged)?.let {
            toaster.show(errorTexts.getValue(QubaguRulesException.Reason.INVALID).format(it))
            return
        }
        vm.updateQubagu { latest -> latest.copy(packs = QubaguRulesJson.merge(latest.packs, pending.packs) { choices[it.id] ?: QubaguImportChoice.OVERWRITE }) }
        val imported = pending.packs.filter { choices[it.id] != QubaguImportChoice.SKIP }
        toaster.show(importDone.format(imported.size, imported.sumOf { it.rules.size }))
        invalidRules = pending.invalid.takeIf { imported.isNotEmpty() }.orEmpty()
    }

    fun import(uri: Uri) = scope.launch {
        try {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Cannot open the file")
            }
            val checked = QubaguRulesJson.checkRegex(QubaguRulesJson.decode(text), engine)
            // the stored value, not this composition's: the page may not have recomposed since the picker returned
            val existing = vm.settings.value.qubagu.packs.mapTo(HashSet()) { it.id }
            val pending = PendingImport(checked.packs, checked.invalid, checked.packs.filter { it.id in existing })
            if (pending.conflicts.isEmpty()) finishImport(pending, emptyMap()) else pendingImport = pending
        } catch (e: CancellationException) {
            throw e
        } catch (e: QubaguRulesException) {
            toaster.show(errorTexts.getValue(e.reason).format(e.detail))
        } catch (e: Exception) {
            toaster.show(ioFailed.format(e.message.orEmpty()))
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::import) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val packs = exporting
        if (uri == null || packs.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(QubaguRulesJson.encode(packs).encodeToByteArray()) }
                        ?: error("Cannot write the file")
                }
                toaster.show(exportDone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show(ioFailed.format(e.message.orEmpty()))
            }
        }
    }
    fun export(packs: List<QubaguRulePack>) {
        exporting = packs
        exporter.launch(exportFileName())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qubagu_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            state = rememberSettingSearchListState(),
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("intro") {
                Text(
                    stringResource(R.string.qubagu_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item("mode") {
                CardGroup(modifier = Modifier.padding(horizontal = AppSpacing.xxs), title = { Text(stringResource(R.string.qubagu_section_mode)) }) {
                    item(
                        headlineContent = {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                QubaguMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = config.mode == mode,
                                        onClick = { vm.updateQubagu { it.copy(mode = mode) } },
                                        shape = SegmentedButtonDefaults.itemShape(index, QubaguMode.entries.size),
                                        modifier = Modifier.testTag("qubagu-mode-${mode.name}"),
                                    ) { Text(stringResource(mode.labelRes)) }
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                stringResource(if (config.mode == QubaguMode.AUTO) R.string.qubagu_mode_auto_desc else R.string.qubagu_mode_review_desc),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        },
                    )
                }
            }
            item("protect") {
                CardGroup(modifier = Modifier.padding(horizontal = AppSpacing.xxs), title = { Text(stringResource(R.string.qubagu_section_protect)) }) {
                    switchItem(R.string.qubagu_protect_dialogue, R.string.qubagu_protect_dialogue_desc, config.protectDialogue, "qubagu-protect-dialogue") { on ->
                        vm.updateQubagu { it.copy(protectDialogue = on) }
                    }
                    switchItem(R.string.qubagu_protect_corner, null, config.protectCornerQuotes, "qubagu-protect-corner") { on ->
                        vm.updateQubagu { it.copy(protectCornerQuotes = on) }
                    }
                    switchItem(R.string.qubagu_protect_details, R.string.qubagu_protect_details_desc, config.protectDetails, "qubagu-protect-details") { on ->
                        vm.updateQubagu { it.copy(protectDetails = on) }
                    }
                }
            }
            item("merge") {
                CardGroup(modifier = Modifier.padding(horizontal = AppSpacing.xxs), title = { Text(stringResource(R.string.qubagu_section_syntax)) }) {
                    switchItem(R.string.qubagu_merge, R.string.qubagu_merge_desc, config.merge, "qubagu-merge") { on ->
                        vm.updateQubagu { it.copy(merge = on) }
                    }
                    if (config.merge) {
                        item(headlineContent = {
                            IntField(
                                value = config.effectiveMergeMaxChars,
                                range = QubaguConfig.MERGE_MAX_CHARS_RANGE,
                                label = stringResource(R.string.qubagu_merge_max_chars),
                                tag = "qubagu-merge-max",
                            ) { value -> vm.updateQubagu { it.copy(mergeMaxChars = value) } }
                        })
                    }
                }
            }
            item("rules-header") {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.qubagu_section_packs))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.testTag("qubagu-import"), shape = MaterialTheme.shapes.medium) {
                            Text(stringResource(R.string.qubagu_import))
                        }
                        OutlinedButton(onClick = { export(config.packs) }, enabled = config.packs.isNotEmpty(), modifier = Modifier.testTag("qubagu-export"), shape = MaterialTheme.shapes.medium) {
                            Text(stringResource(R.string.qubagu_export_all))
                        }
                        OutlinedButton(
                            onClick = { editingPack = QubaguRulePack() },
                            enabled = config.packs.size < QubaguConfig.MAX_PACKS,
                            modifier = Modifier.testTag("qubagu-pack-new"),
                            shape = MaterialTheme.shapes.medium,
                        ) { Text(stringResource(R.string.qubagu_pack_new)) }
                    }
                    if (config.packs.isEmpty()) {
                        Text(
                            stringResource(R.string.qubagu_rules_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            items(config.packs, key = { "pack-${it.id}" }) { pack ->
                PackCard(
                    pack = pack,
                    index = config.packs.indexOf(pack),
                    count = config.packs.size,
                    canAddRule = config.packs.sumOf { it.rules.size } < QubaguConfig.MAX_RULES,
                    update = vm::updateQubagu,
                    onEditRule = { rule -> editingRule = RuleDraft(pack.id, rule, isNew = false) },
                    onAddRule = { rule -> editingRule = RuleDraft(pack.id, rule, isNew = true) },
                    onEdit = { editingPack = pack },
                    onExport = { export(listOf(pack)) },
                    onDelete = { deletingPack = pack },
                )
            }
            item("refine") {
                CardGroup(modifier = Modifier.padding(horizontal = AppSpacing.xxs), title = { Text(stringResource(R.string.qubagu_section_refine)) }) {
                    switchItem(R.string.qubagu_refine_auto, R.string.qubagu_refine_auto_desc, config.refine, "qubagu-refine-auto") { on ->
                        vm.updateQubagu { it.copy(refine = on) }
                    }
                    item(
                        headlineContent = {
                            ModelSelector(
                                modelId = config.refineModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    val id = model.takeIf { it.modelId.isNotBlank() }?.id
                                    vm.updateQubagu { it.copy(refineModelId = id) }
                                },
                                modifier = Modifier.testTag("qubagu-refine-model"),
                            )
                        },
                        supportingContent = {
                            val missing = config.refineModelId != null && settings.findModelById(config.refineModelId) == null
                            Text(
                                stringResource(if (missing) R.string.qubagu_refine_model_missing else R.string.qubagu_refine_model_hint),
                                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
            item("refine-prompt") {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    PromptSettingItem(
                        title = stringResource(R.string.qubagu_refine_prompt),
                        promptDescription = stringResource(R.string.qubagu_refine_prompt_desc),
                        promptValue = config.effectiveRefinePrompt,
                        onPromptChange = { value -> vm.updateQubagu { it.copy(refinePrompt = value.take(QubaguConfig.MAX_REFINE_PROMPT_CHARS)) } },
                        onResetPrompt = { vm.updateQubagu { it.copy(refinePrompt = null) } },
                        defaultPrompt = QubaguRefine.DEFAULT_PROMPT,
                    )
                }
            }
            item("test") { TestBox(engine, config) }
        }
    }

    editingRule?.let { draft ->
        RuleEditor(
            draft = draft,
            engine = engine,
            onSave = { saved ->
                vm.updateQubagu { latest ->
                    latest.mapPack(draft.packId) { pack ->
                        pack.copy(rules = if (pack.rules.any { it.id == saved.id }) pack.rules.map { if (it.id == saved.id) saved else it } else pack.rules + saved)
                    }
                }
                editingRule = null
            },
            onDelete = {
                vm.updateQubagu { latest -> latest.mapPack(draft.packId) { pack -> pack.copy(rules = pack.rules.filterNot { it.id == draft.rule.id }) } }
                editingRule = null
            },
            onDismiss = { editingRule = null },
        )
    }
    editingPack?.let { pack ->
        PackEditor(
            pack = pack,
            isNew = config.packs.none { it.id == pack.id },
            onSave = { name, description ->
                vm.updateQubagu { latest ->
                    if (latest.packs.any { it.id == pack.id }) latest.mapPack(pack.id) { it.copy(name = name, description = description) }
                    else latest.copy(packs = latest.packs + pack.copy(name = name, description = description))
                }
                editingPack = null
            },
            onDismiss = { editingPack = null },
        )
    }
    deletingPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { deletingPack = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(stringResource(R.string.qubagu_pack_delete_confirm, pack.name, pack.rules.size)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateQubagu { latest -> latest.copy(packs = latest.packs.filterNot { it.id == pack.id }) }
                    deletingPack = null
                }, modifier = Modifier.testTag("qubagu-pack-delete-confirm")) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingPack = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    pendingImport?.let { pending ->
        ImportConflictDialog(
            conflicts = pending.conflicts,
            onConfirm = { choices ->
                pendingImport = null
                finishImport(pending, choices)
            },
            onDismiss = { pendingImport = null },
        )
    }
    if (invalidRules.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { invalidRules = emptyList() },
            title = { Text(stringResource(R.string.qubagu_import_invalid_title)) },
            text = { Text(invalidRules.joinToString("\n")) },
            confirmButton = { TextButton(onClick = { invalidRules = emptyList() }) { Text(stringResource(R.string.common_confirm)) } },
        )
    }
}

private fun me.rerere.rikkihub.ui.components.ui.CardGroupScope.switchItem(
    title: Int,
    description: Int?,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) = item(
    headlineContent = { Text(stringResource(title)) },
    supportingContent = description?.let { { Text(stringResource(it)) } },
    trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag)) },
    searchKey = title,
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
}

/** 一个规则包：开关、菜单（编辑、上下移、导出、删除）；点开看每条规则，逐条开关、点一条编辑、添加规则。 */
@Composable
private fun PackCard(
    pack: QubaguRulePack,
    index: Int,
    count: Int,
    canAddRule: Boolean,
    update: ((QubaguConfig) -> QubaguConfig) -> Unit,
    onEditRule: (QubaguRule) -> Unit,
    onAddRule: (QubaguRule) -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(pack.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    fun move(by: Int) = update { latest ->
        val from = latest.packs.indexOfFirst { it.id == pack.id }
        val to = from + by
        if (from < 0 || to !in latest.packs.indices) latest
        else latest.copy(packs = latest.packs.toMutableList().apply { add(to, removeAt(from)) })
    }
    CardGroup(modifier = Modifier.padding(horizontal = AppSpacing.xxs)) {
        item(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("qubagu-pack-${pack.id}"),
            headlineContent = { Text(pack.name.ifBlank { pack.id }) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (pack.description.isNotBlank()) {
                        Text(pack.description, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(R.string.qubagu_pack_rule_count, pack.rules.size, pack.rules.count { it.enabled }))
                }
            },
            leadingContent = {
                Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = pack.enabled,
                        onCheckedChange = { on -> update { latest -> latest.mapPack(pack.id) { it.copy(enabled = on) } } },
                        modifier = Modifier.testTag("qubagu-pack-switch-${pack.id}"),
                    )
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("qubagu-pack-menu-${pack.id}")) {
                            Icon(HugeIcons.MoreVertical, null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.qubagu_pack_edit)) }, onClick = { menu = false; onEdit() })
                            if (index > 0) DropdownMenuItem(text = { Text(stringResource(R.string.prompt_layout_move_up)) }, onClick = { menu = false; move(-1) })
                            if (index < count - 1) DropdownMenuItem(text = { Text(stringResource(R.string.prompt_layout_move_down)) }, onClick = { menu = false; move(1) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.qubagu_pack_export)) }, onClick = { menu = false; onExport() })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onDelete() },
                                modifier = Modifier.testTag("qubagu-pack-delete"),
                            )
                        }
                    }
                }
            },
        )
        if (expanded) {
            pack.rules.forEach { rule ->
                item(
                    onClick = { onEditRule(rule) },
                    modifier = Modifier.testTag("qubagu-rule"),
                    headlineContent = { Text(rule.name.ifBlank { (rule as? QubaguRule.Replace)?.find ?: rule.id }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(ruleSummary(rule), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Switch(
                            checked = rule.enabled,
                            enabled = pack.enabled,
                            onCheckedChange = { on ->
                                update { latest -> latest.mapPack(pack.id) { p -> p.copy(rules = p.rules.map { if (it.id == rule.id) it.withEnabled(on) else it }) } }
                            },
                        )
                    },
                )
            }
            item(headlineContent = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAddRule(QubaguRule.Replace(regex = true)) }, enabled = canAddRule, modifier = Modifier.testTag("qubagu-rule-add"), shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.qubagu_rule_add_replace))
                    }
                    OutlinedButton(onClick = { onAddRule(QubaguRule.Quota()) }, enabled = canAddRule, modifier = Modifier.testTag("qubagu-rule-add-quota"), shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.qubagu_rule_add_quota))
                    }
                }
            })
        }
    }
}

@Composable
private fun ruleSummary(rule: QubaguRule): String = when (rule) {
    is QubaguRule.Replace -> {
        val replacements = rule.replacements.joinToString(" / ").ifEmpty { stringResource(R.string.qubagu_rule_deletes) }
        if (rule.sample.isBlank()) "→  $replacements" else "${rule.sample}  →  $replacements"
    }
    is QubaguRule.Quota -> stringResource(R.string.qubagu_rule_quota_summary, rule.max, rule.words.joinToString("、"))
}

/** 失焦或按完成时保存的整数；空着或超出范围的不保存。 */
@Composable
private fun IntField(value: Int, range: IntRange, label: String, tag: String, onCommit: (Int) -> Unit) {
    CommitOnDoneTextField(
        value = value.toString(),
        onCommit = { text -> text.trim().toIntOrNull()?.takeIf { it in range }?.let(onCommit) },
        label = { Text("$label (${range.first}–${range.last})") },
        singleLine = true,
        isError = { it.trim().toIntOrNull()?.let { number -> number in range } != true },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** 粘贴一段文字，按现在的设置看会改哪些地方（总是按「直接改好」显示结果）。 */
@Composable
private fun TestBox(engine: Qubagu, config: QubaguConfig) {
    var input by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<String, QubaguRecord>?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.qubagu_section_test))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; result = null },
            label = { Text(stringResource(R.string.qubagu_test_input)) },
            minLines = 3,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth().testTag("qubagu-test-input"),
        )
        OutlinedButton(
            onClick = {
                val text = input
                scope.launch { result = text to engine.clean(text, config.copy(mode = QubaguMode.AUTO)) }
            },
            enabled = input.isNotBlank(),
            modifier = Modifier.testTag("qubagu-test-run"),
            shape = MaterialTheme.shapes.medium,
        ) { Text(stringResource(R.string.qubagu_test_run)) }
        result?.let { (text, record) ->
            Text(
                if (record.edits.isEmpty()) stringResource(R.string.qubagu_test_none) else stringResource(R.string.qubagu_test_found, record.edits.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("qubagu-test-count"),
            )
            if (record.edits.isNotEmpty()) {
                CardGroup {
                    item(headlineContent = { Text(record.render(text), modifier = Modifier.testTag("qubagu-test-output")) })
                    record.edits.forEach { edit -> item(headlineContent = { QubaguEditRow(edit, config) }) }
                }
            }
        }
    }
}

@Composable
private fun PackEditor(pack: QubaguRulePack, isNew: Boolean, onSave: (name: String, description: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(pack.id) { mutableStateOf(pack.name) }
    var description by remember(pack.id) { mutableStateOf(pack.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.qubagu_pack_new else R.string.qubagu_pack_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(QubaguRule.MAX_NAME_CHARS) },
                    label = { Text(stringResource(R.string.qubagu_pack_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("qubagu-pack-name"),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(QubaguRulePack.MAX_DESCRIPTION_CHARS) },
                    label = { Text(stringResource(R.string.qubagu_pack_description)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), description.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("qubagu-pack-save")) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 导入的文件里有和已有规则包同 id 的包：逐个选覆盖、另存副本或跳过（默认覆盖）。 */
@Composable
private fun ImportConflictDialog(conflicts: List<QubaguRulePack>, onConfirm: (Map<String, QubaguImportChoice>) -> Unit, onDismiss: () -> Unit) {
    val choices = remember { mutableStateMapOf<String, QubaguImportChoice>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qubagu_import_conflict_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                conflicts.forEach { pack ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(pack.name.ifBlank { pack.id }, style = MaterialTheme.typography.titleSmall)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            QubaguImportChoice.entries.forEachIndexed { index, choice ->
                                SegmentedButton(
                                    selected = (choices[pack.id] ?: QubaguImportChoice.OVERWRITE) == choice,
                                    onClick = { choices[pack.id] = choice },
                                    shape = SegmentedButtonDefaults.itemShape(index, QubaguImportChoice.entries.size),
                                    modifier = Modifier.testTag("qubagu-import-${choice.name}-${pack.id}"),
                                ) {
                                    Text(
                                        stringResource(
                                            when (choice) {
                                                QubaguImportChoice.OVERWRITE -> R.string.qubagu_import_overwrite
                                                QubaguImportChoice.COPY -> R.string.qubagu_import_copy
                                                QubaguImportChoice.SKIP -> R.string.qubagu_import_skip
                                            }
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(choices.toMap()) }, modifier = Modifier.testTag("qubagu-import-confirm")) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 编辑的是草稿，点保存才写回设置。 */
@Composable
private fun RuleEditor(draft: RuleDraft, engine: Qubagu, onSave: (QubaguRule) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val rule = draft.rule
    var name by remember(rule.id) { mutableStateOf(rule.name) }
    var refinePrompt by remember(rule.id) { mutableStateOf(rule.refinePrompt) }
    var edited by remember(rule.id) { mutableStateOf(rule) }
    var listText by remember(rule.id) {
        mutableStateOf(
            when (rule) {
                is QubaguRule.Replace -> rule.replacements.joinToString(" / ")
                is QubaguRule.Quota -> rule.words.joinToString("、")
            }
        )
    }
    var error by remember(rule.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val findRequired = stringResource(R.string.qubagu_rule_find_required)
    val wordsRequired = stringResource(R.string.qubagu_rule_words_required)
    val regexInvalid = stringResource(R.string.qubagu_rule_regex_invalid)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(if (draft.isNew) R.string.qubagu_custom_add else R.string.qubagu_custom_edit), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(QubaguRule.MAX_NAME_CHARS) },
                label = { Text(stringResource(R.string.qubagu_pack_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("qubagu-rule-name"),
            )
            when (val current = edited) {
                is QubaguRule.Replace -> {
                    OutlinedTextField(
                        value = current.find,
                        onValueChange = { edited = current.copy(find = it.take(QubaguRule.MAX_FIND_CHARS)); error = null },
                        label = { Text(stringResource(R.string.qubagu_rule_find)) },
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("qubagu-rule-find"),
                    )
                    OutlinedTextField(
                        value = listText,
                        onValueChange = { listText = it },
                        label = { Text(stringResource(R.string.qubagu_rule_replacements)) },
                        supportingText = { Text(stringResource(R.string.qubagu_rule_replacements_hint)) },
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("qubagu-rule-replacements"),
                    )
                    OutlinedTextField(
                        value = current.sample,
                        onValueChange = { edited = current.copy(sample = it.take(QubaguRule.MAX_SAMPLE_CHARS)) },
                        label = { Text(stringResource(R.string.qubagu_rule_sample)) },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(stringResource(R.string.qubagu_rule_regex), stringResource(R.string.qubagu_rule_regex_desc), current.regex, "qubagu-rule-regex") {
                        edited = current.copy(regex = it); error = null
                    }
                    SwitchRow(stringResource(R.string.qubagu_rule_dialogue), null, current.dialogue, "qubagu-rule-dialogue") { edited = current.copy(dialogue = it) }
                    SwitchRow(stringResource(R.string.qubagu_rule_keep_first), stringResource(R.string.qubagu_rule_keep_first_desc), current.keepFirst, "qubagu-rule-keep-first") {
                        edited = current.copy(keepFirst = it)
                    }
                    SwitchRow(stringResource(R.string.qubagu_rule_at_end), stringResource(R.string.qubagu_rule_at_end_desc), current.atEnd, "qubagu-rule-at-end") {
                        edited = current.copy(atEnd = it)
                    }
                }
                is QubaguRule.Quota -> {
                    OutlinedTextField(
                        value = listText,
                        onValueChange = { listText = it; error = null },
                        label = { Text(stringResource(R.string.qubagu_quota_words)) },
                        isError = error != null,
                        supportingText = { Text(error ?: stringResource(R.string.qubagu_quota_words_hint)) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("qubagu-rule-words"),
                    )
                    IntField(current.max, QubaguRule.Quota.MAX_RANGE, stringResource(R.string.qubagu_quota_max), "qubagu-rule-quota-max") { edited = current.copy(max = it) }
                }
            }
            OutlinedTextField(
                value = refinePrompt,
                onValueChange = { refinePrompt = it.take(QubaguRule.MAX_REFINE_PROMPT_CHARS) },
                label = { Text(stringResource(R.string.qubagu_rule_refine_prompt)) },
                supportingText = { Text(stringResource(R.string.qubagu_rule_refine_prompt_desc)) },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("qubagu-rule-refine-prompt"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!draft.isNew) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("qubagu-rule-delete")) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(Modifier.weight(1f)) {}
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(
                    onClick = {
                        val cleaned = when (val current = edited) {
                            is QubaguRule.Replace -> current.copy(
                                name = name.trim(),
                                find = if (current.regex) current.find.trim() else current.find,
                                replacements = listText.split("/").map { it.trim().take(QubaguRule.MAX_REPLACEMENT_CHARS) }
                                    .filter { it.isNotEmpty() }.distinct().take(QubaguRule.MAX_REPLACEMENTS),
                                refinePrompt = refinePrompt.trim(),
                            )
                            is QubaguRule.Quota -> current.copy(
                                name = name.trim(),
                                words = listText.split(WORD_SEPARATORS).map { it.take(QubaguRule.MAX_REPLACEMENT_CHARS) }
                                    .filter { it.isNotBlank() }.distinct().take(QubaguRule.MAX_WORDS),
                                refinePrompt = refinePrompt.trim(),
                            )
                        }
                        when {
                            cleaned is QubaguRule.Replace && cleaned.find.isBlank() -> error = findRequired
                            cleaned is QubaguRule.Quota && cleaned.words.isEmpty() -> error = wordsRequired
                            else -> scope.launch {
                                val problem = engine.check(cleaned)
                                if (problem != null) error = regexInvalid.format(problem) else onSave(cleaned)
                            }
                        }
                    },
                    modifier = Modifier.testTag("qubagu-rule-save"),
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).clickable { onChange(!checked) }) {
            Text(title)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
