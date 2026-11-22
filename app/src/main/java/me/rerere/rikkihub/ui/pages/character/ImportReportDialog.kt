package me.rerere.rikkihub.ui.pages.character

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.character.BookOutcome
import me.rerere.rikkihub.data.character.ImportedRecord

/** 导入完的报告：带来了什么；卡里有正则脚本时问一句要不要现在允许（不允许它们就不运行）。 */
@Composable
fun ImportReportDialog(
    result: ImportedRecord,
    onAllowScripts: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    val report = result.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.character_report_title, result.record.name)) },
        text = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                if (report.greetings > 0) Text(stringResource(R.string.character_report_greetings, report.greetings))
                when (val book = report.book) {
                    is BookOutcome.Created -> Text(stringResource(R.string.character_report_book_created, book.name, book.entries))
                    is BookOutcome.Reused -> Text(stringResource(R.string.character_report_book_reused, book.name))
                    null -> Unit
                }
                if (report.regexScripts > 0) Text(stringResource(R.string.character_report_regex, report.regexScripts))
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (report.regexScripts > 0) TextButton(onClick = onAllowScripts) { Text(stringResource(R.string.character_report_allow)) }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.character_report_ok)) }
            }
        },
    )
}
