package me.rerere.ai.ccdisguise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting

@Composable
fun ClaudeCodeDisguiseSwitch(
    checked: Boolean,
    effectiveProvider: ProviderSetting?,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    claudeOnlyHint: String,
    modifier: Modifier = Modifier,
) {
    val isClaude = effectiveProvider is ProviderSetting.Claude
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (isClaude) description else claudeOnlyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                enabled = isClaude || checked,
                onCheckedChange = { enabled ->
                    if (enabled && !isClaude) return@Switch
                    onCheckedChange(enabled)
                }
            )
        }
    }
}
