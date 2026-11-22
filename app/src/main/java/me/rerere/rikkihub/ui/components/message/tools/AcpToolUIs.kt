package me.rerere.rikkihub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkihub.data.cli.AcpParts
import me.rerere.rikkihub.ui.components.richtext.DiffView
import me.rerere.rikkihub.ui.modifier.shimmer

/**
 * Tool calls of a workspace CLI (`acp_*`, see [AcpParts]): the agent's own title, an icon by ACP
 * kind, file diffs from the call's content, otherwise the output text.
 */
object AcpToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_LINES = 8

    override val toolName: String = AcpParts.TOOL_PREFIX

    private fun acp(context: ToolUIContext): JsonObject? = context.tool.metadata?.get(AcpParts.META_KEY) as? JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    override fun icon(context: ToolUIContext): ImageVector = when (acp(context)?.text("kind")) {
        "execute" -> HugeIcons.ComputerTerminal01
        "edit", "delete", "move" -> HugeIcons.FileEdit
        "read" -> HugeIcons.FileView
        "search" -> HugeIcons.Search01
        "fetch" -> HugeIcons.Globe02
        "plan" -> HugeIcons.Task01
        else -> HugeIcons.Tools
    }

    @Composable
    override fun title(context: ToolUIContext): String = AcpParts.titleOf(context.tool)

    /** A denied card's output is the denial itself, which the card already shows with its reason. */
    private fun outputText(context: ToolUIContext): String =
        if (context.tool.approvalState is ToolApprovalState.Denied) ""
        else context.tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    override fun hasSummary(context: ToolUIContext): Boolean =
        AcpParts.unifiedDiffOf(context.tool) != null || outputText(context).isNotBlank()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val diff = remember(context) { AcpParts.unifiedDiffOf(context.tool) }
        if (diff != null) {
            DiffView(diff = diff, modifier = Modifier.fillMaxWidth(), maxLines = SUMMARY_MAX_LINES, showFileHeader = true)
            return
        }
        val text = outputText(context)
        if (text.isBlank()) return
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .shimmer(isLoading = context.loading),
        ) {
            Text(
                text = text.lineSequence().take(SUMMARY_MAX_LINES).joinToString("\n"),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = SUMMARY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val diff = remember(context) { AcpParts.unifiedDiffOf(context.tool) }
        val text = outputText(context)
        if (diff == null && text.isBlank()) {
            DefaultToolPreview(context = context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title(context), style = MaterialTheme.typography.titleMedium)
            if (diff != null) DiffView(diff = diff, modifier = Modifier.fillMaxWidth())
            if (text.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}
