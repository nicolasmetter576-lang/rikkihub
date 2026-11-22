package me.rerere.rikkihub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.ui.components.ui.CommitOnDoneTextField

/**
 * 酒馆预设带来的几个采样参数。默认全关（不发）；打开后填数值，失焦或点完成时提交。
 * 各协议只发自己认识的（见 `TextGenerationParams`）。
 */
@Composable
fun SamplingExtras(assistant: Assistant, onUpdate: (Assistant) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.assistant_sampling_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.assistant_sampling_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IntRow(R.string.assistant_sampling_top_k, assistant.topK, default = 40, min = 1, max = 100_000) { onUpdate(assistant.copy(topK = it)) }
        FloatRow(R.string.assistant_sampling_frequency_penalty, assistant.frequencyPenalty, -2f..2f) { onUpdate(assistant.copy(frequencyPenalty = it)) }
        FloatRow(R.string.assistant_sampling_presence_penalty, assistant.presencePenalty, -2f..2f) { onUpdate(assistant.copy(presencePenalty = it)) }
        FloatRow(R.string.assistant_sampling_repetition_penalty, assistant.repetitionPenalty, 0.01f..3f, default = 1.1f) { onUpdate(assistant.copy(repetitionPenalty = it)) }
        FloatRow(R.string.assistant_sampling_min_p, assistant.minP, 0f..1f, default = 0.05f) { onUpdate(assistant.copy(minP = it)) }
        FloatRow(R.string.assistant_sampling_top_a, assistant.topA, 0f..1f, default = 0.2f) { onUpdate(assistant.copy(topA = it)) }
        LongRow(R.string.assistant_sampling_seed, assistant.seed) { onUpdate(assistant.copy(seed = it)) }
    }
}

@Composable
private fun <T> OptionalRow(
    label: Int,
    value: T?,
    default: T,
    text: (T) -> String,
    parse: (String) -> T?,
    keyboard: KeyboardType,
    onChange: (T?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), modifier = Modifier.weight(1f))
            Switch(checked = value != null, onCheckedChange = { on -> onChange(if (on) default else null) })
        }
        if (value != null) {
            CommitOnDoneTextField(
                value = text(value),
                onCommit = { s -> parse(s)?.let(onChange) },
                singleLine = true,
                isError = { parse(it) == null },
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IntRow(label: Int, value: Int?, default: Int, min: Int, max: Int, onChange: (Int?) -> Unit) =
    OptionalRow(label, value, default, { it.toString() }, { s -> s.trim().toIntOrNull()?.takeIf { it in min..max } }, KeyboardType.Number, onChange)

@Composable
private fun LongRow(label: Int, value: Long?, onChange: (Long?) -> Unit) =
    OptionalRow(label, value, 0L, { it.toString() }, { s -> s.trim().toLongOrNull()?.takeIf { it >= 0 } }, KeyboardType.Number, onChange)

@Composable
private fun FloatRow(label: Int, value: Float?, range: ClosedFloatingPointRange<Float>, default: Float = 0.5f.coerceIn(range), onChange: (Float?) -> Unit) =
    OptionalRow(label, value, default, { it.toString() }, { s -> s.trim().toFloatOrNull()?.takeIf { it in range } }, KeyboardType.Decimal, onChange)
