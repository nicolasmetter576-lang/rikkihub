package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.BookVolume
import me.rerere.rikkihub.data.book.transfer.BookExporter
import me.rerere.rikkihub.data.book.transfer.BookImporter
import me.rerere.rikkihub.data.book.transfer.ExportFormat
import me.rerere.rikkihub.data.book.transfer.ExportOptions
import me.rerere.rikkihub.data.book.transfer.exportPlan
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.AppSpacing
import org.koin.compose.koinInject
import java.io.File

/** 导出作品：TXT、Markdown、EPUB；存到手机、存进作品的「导出」文件夹，或直接分享。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookExportSheet(book: Book, onDismiss: () -> Unit) {
    val store = koinInject<BookStore>()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ExportFormat.EPUB) }
    var volumeId by rememberSaveable { mutableStateOf<String?>(null) }
    var chapterIds by remember { mutableStateOf<Set<String>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var finalOnly by rememberSaveable { mutableStateOf(false) }
    var volumeTitles by rememberSaveable { mutableStateOf(true) }
    var indent by rememberSaveable { mutableStateOf(true) }
    var blankLines by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val options = ExportOptions(format, volumeId, chapterIds, finalOnly, volumeTitles, indent, blankLines)
    val plan = remember(book, options) { exportPlan(book, options) }
    val fileName = (BookImporter.safeName(book.manifest.title).ifBlank { "book" }) + "." + format.extension
    val savedMessage = stringResource(R.string.book_export_saved)
    val folder = stringResource(R.string.book_export_folder)
    val savedInBook = stringResource(R.string.book_export_saved_in, "$folder/$fileName")

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                toaster.show(e.message ?: e.javaClass.simpleName, type = ToastType.Error)
            } finally {
                busy = false
            }
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
        if (uri != null) run {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open the file")
                output.use { store.exporter(book.workspaceId).export(book, options, it) }
            }
            toaster.show(savedMessage, type = ToastType.Success)
            onDismiss()
        }
    }

    if (choosing) ChapterChooser(
        book,
        chapterIds ?: book.entries.filterNot { it.missing }.mapTo(HashSet()) { it.id },
        onDismiss = { choosing = false },
        onConfirm = {
            choosing = false
            volumeId = null
            chapterIds = it
        },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AppSpacing.md).navigationBarsPadding().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.book_export_title), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = format == value,
                        onClick = { format = value },
                        shape = SegmentedButtonDefaults.itemShape(index, ExportFormat.entries.size),
                    ) { Text(if (value == ExportFormat.MARKDOWN) "Markdown" else value.name, maxLines = 1) }
                }
            }
            val volumes = book.manifest.items.filterIsInstance<BookVolume>()
            var menu by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { menu = true },
                    label = {
                        Text(
                            when {
                                chapterIds != null -> stringResource(R.string.book_export_chosen)
                                else -> volumes.firstOrNull { it.id == volumeId }?.title ?: stringResource(R.string.book_replace_scope_all)
                            },
                        )
                    },
                )
                fun choose(volume: String?) {
                    menu = false
                    volumeId = volume
                    chapterIds = null
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.book_replace_scope_all)) }, onClick = { choose(null) })
                    volumes.forEach { volume -> DropdownMenuItem(text = { Text(volume.title) }, onClick = { choose(volume.id) }) }
                    DropdownMenuItem(text = { Text(stringResource(R.string.book_export_chosen)) }, onClick = {
                        menu = false
                        choosing = true
                    })
                }
            }
            if (volumes.isNotEmpty()) Toggle(stringResource(R.string.book_export_volume_titles), volumeTitles) { volumeTitles = it }
            Toggle(stringResource(R.string.book_export_final_only), finalOnly) { finalOnly = it }
            if (format != ExportFormat.MARKDOWN) Toggle(stringResource(R.string.book_export_indent), indent) { indent = it }
            if (format == ExportFormat.TXT) Toggle(stringResource(R.string.book_export_blank_lines), blankLines) { blankLines = it }
            Text(
                stringResource(R.string.book_export_count, plan.chapters.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (plan.missing.isNotEmpty()) {
                Text(
                    stringResource(R.string.book_export_missing, plan.missing.joinToString("、") { it.title }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val enabled = !busy && plan.chapters.isNotEmpty()
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                OutlinedButton(
                    onClick = {
                        run {
                            val file = withContext(Dispatchers.IO) {
                                File(context.cacheDir, "book_export/$fileName").also { file ->
                                    file.parentFile?.mkdirs()
                                    file.outputStream().use { store.exporter(book.workspaceId).export(book, options, it) }
                                }
                            }
                            context.share(file, format.mime)
                            onDismiss()
                        }
                    },
                    enabled = enabled,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.share), maxLines = 1) }
                OutlinedButton(
                    onClick = {
                        run {
                            store.exportInto(book, options, "$folder/$fileName")
                            toaster.show(savedInBook, type = ToastType.Success)
                            onDismiss()
                        }
                    },
                    enabled = enabled,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).testTag("book-export-into"),
                ) { Text(stringResource(R.string.book_export_into), maxLines = 1) }
                Button(
                    onClick = { saver.launch(fileName) },
                    enabled = enabled,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).testTag("book-export-save"),
                ) { Text(stringResource(R.string.book_export_save), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ChapterChooser(book: Book, initial: Set<String>, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_export_chosen)) },
        text = {
            LazyColumn {
                items(book.entries.filterNot { it.missing }, key = { it.id }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp).toggleable(entry.id in chosen) { on -> chosen = if (on) chosen + entry.id else chosen - entry.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = entry.id in chosen, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
                        Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun Context.share(file: File, mime: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, null))
}
