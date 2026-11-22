package me.rerere.rikkihub.ui.pages.assistant.detail

import androidx.compose.material3.MaterialTheme
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.character.PresetFormatException
import me.rerere.rikkihub.data.character.PresetPorting
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.ui.components.ui.AutoAIIcon
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster

/** 新建助手时导入 SillyTavern 的 Chat Completion 预设（JSON）：预设成为这个助手的提示词与采样参数。角色卡去“设置 → 角色”导入。 */
@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isLoading = true
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { readPreset(context, uri) }
                toaster.show(
                    context.getString(
                        R.string.assistant_importer_preset_done,
                        imported.report.promptCount, imported.report.regexScriptsDisabled, imported.report.connectionKeysSkipped.size,
                    ),
                )
                onUpdate(imported.assistant)
            } catch (e: PresetFormatException) {
                toaster.show(e.message ?: context.getString(R.string.assistant_importer_import_failed), type = ToastType.Error)
            } catch (e: Exception) {
                toaster.show(e.message ?: context.getString(R.string.assistant_importer_import_failed), type = ToastType.Error)
            } finally {
                isLoading = false
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }, enabled = !isLoading, shape = MaterialTheme.shapes.medium) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(
                if (isLoading) stringResource(R.string.assistant_importer_importing)
                else stringResource(R.string.assistant_importer_import_preset),
            )
        }
    }
}

private fun readPreset(context: Context, uri: Uri): me.rerere.rikkihub.data.character.PresetImport {
    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
    val json = runCatching { Json.parseToJsonElement(text.removePrefix("﻿")).jsonObject }
        .getOrElse { throw PresetFormatException(context.getString(R.string.assistant_importer_read_json_failed)) }
    var name = ""
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0).orEmpty()
    }
    return PresetPorting.import(json, name)
}
