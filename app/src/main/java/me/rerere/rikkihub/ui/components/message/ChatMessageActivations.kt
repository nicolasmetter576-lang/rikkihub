package me.rerere.rikkihub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tavern.ActivationSummary
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.tavern.worldinfo.WorldInfoPosition
import me.rerere.rikkihub.ui.components.ui.CompactListItem

/** 这条回复用到的世界书条目：书名、条目、落在哪个位置；超出记录上限的只给条数。 */
@Composable
fun WorldInfoActivationSheet(summary: ActivationSummary, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.worldinfo_activations_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.worldinfo_activations_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(summary.entries) { entry ->
                    CompactListItem(
                        headlineContent = {
                            Text(entry.title.ifBlank { stringResource(R.string.worldinfo_activations_unnamed, entry.uid) })
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.worldinfo_activations_detail,
                                    entry.bookName.ifBlank { stringResource(R.string.prompt_page_unnamed_lorebook) },
                                    stringResource(positionLabel(entry.position)),
                                    entry.order,
                                )
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (summary.more > 0) {
                    item {
                        Text(
                            text = stringResource(R.string.worldinfo_activations_more, summary.more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun positionLabel(position: Int): Int = when (position) {
    WorldInfoPosition.BEFORE -> R.string.worldinfo_pos_before
    WorldInfoPosition.AFTER -> R.string.worldinfo_pos_after
    WorldInfoPosition.AN_TOP -> R.string.worldinfo_pos_an_top
    WorldInfoPosition.AN_BOTTOM -> R.string.worldinfo_pos_an_bottom
    WorldInfoPosition.AT_DEPTH -> R.string.worldinfo_pos_depth
    WorldInfoPosition.EM_TOP -> R.string.worldinfo_pos_em_top
    WorldInfoPosition.EM_BOTTOM -> R.string.worldinfo_pos_em_bottom
    WorldInfoPosition.OUTLET -> R.string.worldinfo_pos_outlet
    else -> R.string.worldinfo_pos_after
}
