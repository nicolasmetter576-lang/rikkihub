package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.EditorColorPair
import me.rerere.rikkihub.data.datastore.EditorFontIds
import me.rerere.rikkihub.data.datastore.EditorTypography
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.appEditorTypography
import me.rerere.rikkihub.data.fonts.DownloadableFont
import me.rerere.rikkihub.data.fonts.DownloadableFonts
import me.rerere.rikkihub.data.fonts.FontDownloadAlreadyRunningException
import me.rerere.rikkihub.data.fonts.FontDownloadService
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.ui.components.ui.Select
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.pages.setting.ColorPickerRow
import me.rerere.rikkihub.ui.theme.LocalDarkMode
import me.rerere.rikkihub.ui.theme.rememberEditorFontFamily
import org.koin.compose.koinInject

/** Base text style of the workspace editor and preview, from the App typography. */
@Composable
internal fun rememberWorkspaceEditorTextStyle(base: TextStyle): TextStyle {
    val display = LocalSettings.current.displaySetting
    val typography = display.appEditorTypography
    val family = rememberEditorFontFamily(typography.font, display)
    val color = editorTextColor(base.color, typography)
    return remember(base, typography, family, color) {
        base.copy(
            fontFamily = family,
            fontSize = typography.fontSize.sp,
            letterSpacing = typography.letterSpacing.em,
            lineHeight = (typography.fontSize * typography.lineHeight).sp,
            color = color,
        )
    }
}

/**
 * The chosen text color of [typography] for the current theme, else [base] (else the current content
 * color), blended toward the surface by its text brightness. The content color is black outside a
 * Surface/Scaffold, so callers sit in one (or pass a color) for dark themes.
 */
@Composable
internal fun editorTextColor(base: Color, typography: EditorTypography): Color {
    val text = typography.textColor.current() ?: base.takeOrElse { LocalContentColor.current }
    val brightness = typography.textBrightness
    return if (brightness >= 1f) text else lerp(MaterialTheme.colorScheme.surface, text, brightness)
}

/** The color of this pair for the current theme (light or dark); null follows the theme. */
@Composable
internal fun EditorColorPair.current(): Color? = of(LocalDarkMode.current)?.let { Color(it.toInt()) }

/** Color of quoted text in workspace prose, or unspecified while that highlight is off. */
@Composable
internal fun workspaceQuotedTextColor(): Color {
    val typography = LocalSettings.current.displaySetting.appEditorTypography
    if (!typography.quoteHighlight) return Color.Unspecified
    return typography.quoteColor.current() ?: MaterialTheme.colorScheme.tertiary
}

/**
 * One color per theme for [pair]: light / dark tabs (the current theme selected first), each following
 * the theme or picked with HSL sliders, starting from [fallback] of that theme.
 */
@Composable
private fun EditorColorPairPicker(pair: EditorColorPair, fallback: (dark: Boolean) -> Color, onChange: (EditorColorPair) -> Unit) {
    val currentDark = LocalDarkMode.current
    var dark by remember { mutableStateOf(currentDark) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val tabs = listOf(false to R.string.editor_typography_color_light, true to R.string.editor_typography_color_dark)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = dark == value,
                    onClick = { dark = value },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                ) { Text(stringResource(label)) }
            }
        }
        val argb = pair.of(dark)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.editor_typography_color_follow), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = argb == null, onCheckedChange = { follow ->
                onChange(pair.with(dark, if (follow) null else fallback(dark).toArgb().toLong() and 0xFFFFFFFFL))
            })
        }
        if (argb != null) ColorPickerRow(Color(argb.toInt())) { onChange(pair.with(dark, it.toArgb().toLong() and 0xFFFFFFFFL)) }
    }
}

/** Font choices offered by the editor; custom appears only once a font has been imported. */
fun editorFontChoices(display: DisplaySetting): List<String> =
    EditorFontIds.ALL.filter { it != EditorFontIds.CUSTOM || display.chatCustomFontPath.isNotBlank() }

@Composable
internal fun editorFontLabel(id: String, display: DisplaySetting): String = when (id) {
    EditorFontIds.SYSTEM -> stringResource(R.string.editor_font_system)
    EditorFontIds.SERIF -> stringResource(R.string.editor_font_serif)
    EditorFontIds.JETBRAINS_MONO -> "JetBrains Mono"
    EditorFontIds.CUSTOM -> stringResource(R.string.editor_font_custom, display.chatCustomFontName.ifBlank { "…" })
    else -> DownloadableFonts.byId(id)?.displayName ?: id
}

private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)

/** Download state and actions of one downloadable font; shared by the editor sheet and the chat font setting. */
@Composable
fun DownloadableFontStatus(font: DownloadableFont, modifier: Modifier = Modifier) {
    val downloads = koinInject<FontDownloadService>()
    val states by downloads.states.collectAsState()
    val installed by downloads.installed.collectAsState()
    val state = states[font.id]
    val start = {
        try { downloads.start(font.id) } catch (_: FontDownloadAlreadyRunningException) { }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = when {
                state?.downloading == true -> stringResource(R.string.editor_font_downloading,
                    (state.bytesRead * 100 / state.totalBytes.coerceAtLeast(1)).toInt())
                state != null -> stringResource(R.string.editor_font_failed, state.error.orEmpty())
                font.id in installed -> stringResource(R.string.editor_font_downloaded, formatMegabytes(font.sizeBytes))
                else -> stringResource(R.string.editor_font_not_downloaded, formatMegabytes(font.sizeBytes))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                color = if (state != null && !state.downloading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state?.downloading == true -> TextButton(onClick = { downloads.cancel(font.id) }) { Text(stringResource(R.string.editor_font_cancel)) }
                state != null -> TextButton(onClick = { start() }) { Text(stringResource(R.string.editor_font_retry)) }
                font.id in installed -> TextButton(onClick = {
                    try { downloads.delete(font.id) } catch (_: FontDownloadAlreadyRunningException) { }
                }) { Text(stringResource(R.string.editor_font_delete)) }
                else -> TextButton(onClick = { start() }) { Text(stringResource(R.string.editor_font_download)) }
            }
        }
        if (state?.downloading == true) {
            LinearProgressIndicator(
                progress = { state.bytesRead.toFloat() / state.totalBytes.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(stringResource(R.string.editor_font_license, font.license), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TypographyStepper(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    fun snap(v: Float) = ((v - range.start) / step).roundToInt() * step + range.start
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(shown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(snap(value - step).coerceIn(range)) }, enabled = value > range.start) {
                Icon(HugeIcons.MinusSign, contentDescription = null)
            }
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { onChange(snap(it).coerceIn(range)) }; dragging = null },
                // Continuous track (30–40 tick marks would crowd it); the value snaps to [step] on release.
                valueRange = range,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(snap(value + step).coerceIn(range)) }, enabled = value < range.endInclusive) {
                Icon(HugeIcons.PlusSign, contentDescription = null)
            }
        }
    }
}

/**
 * Font, size, letter spacing and line spacing controls with a live preview. [sizeUnit] is the
 * label of [EditorTypography.fontSize] (sp in the App).
 */
@Composable
fun EditorTypographyControls(
    typography: EditorTypography,
    display: DisplaySetting,
    default: EditorTypography,
    onChange: (EditorTypography) -> Unit,
    sizeUnit: String = "sp",
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.editor_typography_font), style = MaterialTheme.typography.bodyMedium)
        Select(
            options = editorFontChoices(display).let { if (typography.font in it) it else it + typography.font },
            selectedOption = typography.font,
            onOptionSelected = { id ->
                onChange(typography.copy(font = id))
            },
            modifier = Modifier.fillMaxWidth(),
            optionToString = { editorFontLabel(it, display) },
            optionLeading = { id -> Text("Aa", fontFamily = rememberEditorFontFamily(id, display)) },
        )
        DownloadableFonts.byId(typography.font)?.let { font -> DownloadableFontStatus(font) }
        TypographyStepper(
            label = stringResource(R.string.editor_typography_font_size),
            value = typography.fontSize,
            range = EditorTypography.MIN_FONT_SIZE..EditorTypography.MAX_FONT_SIZE,
            step = 1f,
            format = { "${it.roundToInt()} $sizeUnit" },
            onChange = { onChange(typography.copy(fontSize = it)) },
        )
        TypographyStepper(
            label = stringResource(R.string.editor_typography_letter_spacing),
            value = typography.letterSpacing,
            range = EditorTypography.MIN_LETTER_SPACING..EditorTypography.MAX_LETTER_SPACING,
            step = 0.01f,
            format = { "%.2f em".format(it) },
            onChange = { onChange(typography.copy(letterSpacing = it)) },
        )
        TypographyStepper(
            label = stringResource(R.string.editor_typography_line_height),
            value = typography.lineHeight,
            range = EditorTypography.MIN_LINE_HEIGHT..EditorTypography.MAX_LINE_HEIGHT,
            step = 0.05f,
            format = { "%.2f×".format(it) },
            onChange = { onChange(typography.copy(lineHeight = it)) },
        )
        TypographyStepper(
            label = stringResource(R.string.editor_typography_text_brightness),
            value = typography.textBrightness,
            range = EditorTypography.MIN_TEXT_BRIGHTNESS..1f,
            step = 0.05f,
            format = { "${(it * 100).roundToInt()}%" },
            onChange = { onChange(typography.copy(textBrightness = it)) },
        )
        Text(stringResource(R.string.editor_typography_text_color), style = MaterialTheme.typography.bodyMedium)
        EditorColorPairPicker(
            pair = typography.textColor,
            fallback = { dark -> if (dark) Color(0xFFE3E2E9) else Color(0xFF1A1B20) },
            onChange = { onChange(typography.copy(textColor = it)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_typography_quote_color), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.editor_typography_quote_color_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = typography.quoteHighlight, onCheckedChange = { onChange(typography.copy(quoteHighlight = it)) },
                modifier = Modifier.padding(start = 12.dp))
        }
        val tertiary = MaterialTheme.colorScheme.tertiary
        if (typography.quoteHighlight) EditorColorPairPicker(
            pair = typography.quoteColor,
            fallback = { tertiary },
            onChange = { onChange(typography.copy(quoteColor = it)) },
        )
        val family = rememberEditorFontFamily(typography.font, display)
        val quoted = typography.quoteColor.current() ?: tertiary
        val sample = stringResource(R.string.editor_typography_preview) + "\n" + stringResource(R.string.editor_typography_preview_quote)
        Text(
            remember(sample, typography.quoteHighlight, quoted) {
                AnnotatedString.Builder(sample).apply {
                    if (typography.quoteHighlight) workspaceQuotedSpans(sample).forEach { addStyle(SpanStyle(color = quoted), it.start, it.end) }
                }.toAnnotatedString()
            },
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = family,
                fontSize = typography.fontSize.sp,
                letterSpacing = typography.letterSpacing.em,
                lineHeight = (typography.fontSize * typography.lineHeight).sp,
                color = editorTextColor(Color.Unspecified, typography),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
        TextButton(onClick = { onChange(default) }, enabled = typography != default, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.editor_typography_reset))
        }
    }
}

/** Writes only the App editor typography, on top of the latest settings (field-level). */
@Composable
internal fun rememberAppEditorTypographyWriter(): (EditorTypography) -> Unit {
    val settingsStore = koinInject<SettingsStore>()
    val appScope = koinInject<AppScope>()
    return remember(settingsStore, appScope) {
        { next ->
            appScope.launch {
                settingsStore.update { settings ->
                    settings.copy(displaySetting = settings.displaySetting.copy(workspaceEditorTypography = next))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceEditorTypographySheet(
    onDismiss: () -> Unit,
    topRows: (@Composable () -> Unit)? = null,
    extraRows: @Composable () -> Unit = {},
) {
    val display = LocalSettings.current.displaySetting
    val write = rememberAppEditorTypographyWriter()
    var typography by remember { mutableStateOf(display.appEditorTypography) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            topRows?.let { rows ->
                rows()
                HorizontalDivider()
            }
            Text(stringResource(R.string.editor_typography_title), style = MaterialTheme.typography.titleMedium)
            EditorTypographyControls(
                typography = typography,
                display = display,
                default = EditorTypography.APP_DEFAULT,
                onChange = { next ->
                    typography = next
                    write(next)
                },
            )
            extraRows()
            HorizontalDivider()
            WorkspaceEditorAutoPairRow()
            HorizontalDivider()
            WorkspaceEditorToolbarSettingRows()
        }
    }
}

/** The App editor's 快捷栏位置 and 快捷栏尺寸, also on 设置-偏好设置-工作区; written on top of the latest settings. */
@Composable
private fun WorkspaceEditorToolbarSettingRows() {
    val settingsStore = koinInject<SettingsStore>()
    val appScope = koinInject<AppScope>()
    val display = LocalSettings.current.displaySetting
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setting_workspace_editor_toolbar_position), style = MaterialTheme.typography.bodyMedium)
        WorkspaceEditorToolbarPositionSelector(display.workspaceEditorToolbarAtBottom) { atBottom ->
            appScope.launch {
                settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(workspaceEditorToolbarAtBottom = atBottom)) }
            }
        }
        Text(stringResource(R.string.setting_workspace_editor_toolbar_size), style = MaterialTheme.typography.bodyMedium)
        WorkspaceEditorToolbarSizeSelector(WorkspaceEditorToolbarSize.of(display.workspaceEditorToolbarSize)) { size ->
            appScope.launch {
                settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(workspaceEditorToolbarSize = size.id)) }
            }
        }
    }
}

/** The App editor's 自动补全成对符号 switch, also on 设置-偏好设置-工作区; written on top of the latest settings. */
@Composable
private fun WorkspaceEditorAutoPairRow() = DisplaySwitchRow(
    title = stringResource(R.string.setting_workspace_editor_auto_pair),
    description = stringResource(R.string.setting_workspace_editor_auto_pair_desc),
    checked = LocalSettings.current.displaySetting.workspaceEditorAutoPair,
) { display, value -> display.copy(workspaceEditorAutoPair = value) }

/** Reading mode's 翻页方式 selector, also on 设置-偏好设置-工作区; written on top of the latest settings. */
@Composable
internal fun WorkspaceReaderModeRow() {
    val settingsStore = koinInject<SettingsStore>()
    val appScope = koinInject<AppScope>()
    val verticalScroll = LocalSettings.current.displaySetting.workspaceReaderVerticalScroll
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setting_workspace_reader_mode), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.setting_workspace_reader_mode_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WorkspaceReaderModeSelector(verticalScroll) { next ->
            appScope.launch {
                settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(workspaceReaderVerticalScroll = next)) }
            }
        }
    }
}

/** Horizontal pages / Vertical scroll for reading mode, shared between Aa sheet and Workspace preferences. */
@Composable
internal fun WorkspaceReaderModeSelector(verticalScroll: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(
        false to R.string.setting_workspace_reader_mode_horizontal,
        true to R.string.setting_workspace_reader_mode_vertical,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = verticalScroll == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

/** Reading mode's 音量键翻页 switch, also on 设置-偏好设置-工作区; written on top of the latest settings. */
@Composable
internal fun WorkspaceReaderVolumeKeysRow() = DisplaySwitchRow(
    title = stringResource(R.string.setting_workspace_reader_volume_keys),
    description = stringResource(R.string.setting_workspace_reader_volume_keys_desc),
    checked = LocalSettings.current.displaySetting.workspaceReaderVolumeKeys,
) { display, value -> display.copy(workspaceReaderVolumeKeys = value) }

@Composable
private fun DisplaySwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    update: (DisplaySetting, Boolean) -> DisplaySetting,
) {
    val settingsStore = koinInject<SettingsStore>()
    val appScope = koinInject<AppScope>()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = { value ->
            appScope.launch {
                settingsStore.update { it.copy(displaySetting = update(it.displaySetting, value)) }
            }
        }, modifier = Modifier.padding(start = 12.dp))
    }
}
