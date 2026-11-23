package me.rerere.rikkihub.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.JsonArray
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds

/** 作品工具：标题说清读了哪几章、找了什么；结果是纯文本，展开看开头几行。 */
class BookToolUI private constructor(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = when (toolName) {
        BuiltinToolIds.BOOK_SEARCH -> HugeIcons.Search01
        BuiltinToolIds.BOOK_UPDATE -> HugeIcons.Edit01
        else -> HugeIcons.BookOpen01
    }

    @Composable
    override fun title(context: ToolUIContext): String = when (toolName) {
        BuiltinToolIds.BOOK_READ -> {
            val from = context.arguments.getStringContent("chapter")
            val to = context.arguments.getStringContent("to")
            when {
                from == null -> stringResource(R.string.book_tool_read)
                to == null || to == from -> stringResource(R.string.book_tool_read_one, from)
                else -> stringResource(R.string.book_tool_read_range, from, to)
            }
        }
        BuiltinToolIds.BOOK_SEARCH -> context.arguments.getStringContent("query")
            ?.let { stringResource(R.string.book_tool_search_query, it) } ?: stringResource(R.string.book_tool_search)
        BuiltinToolIds.BOOK_UPDATE -> (context.arguments.jsonObjectOrNull?.get("operations") as? JsonArray)
            ?.let { stringResource(R.string.book_tool_update_count, it.size) } ?: stringResource(R.string.book_tool_update)
        else -> stringResource(R.string.book_tool_contents)
    }

    private fun textOf(context: ToolUIContext): String? =
        context.tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.ifBlank { null }

    override fun hasSummary(context: ToolUIContext): Boolean = textOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Text(
            text = textOf(context)?.take(SUMMARY_CHARS) ?: return,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = SUMMARY_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }

    companion object {
        private const val SUMMARY_LINES = 6
        private const val SUMMARY_CHARS = 600

        val all = listOf(
            BuiltinToolIds.BOOK_CONTENTS, BuiltinToolIds.BOOK_READ, BuiltinToolIds.BOOK_SEARCH, BuiltinToolIds.BOOK_UPDATE,
        ).map(::BookToolUI)
    }
}
