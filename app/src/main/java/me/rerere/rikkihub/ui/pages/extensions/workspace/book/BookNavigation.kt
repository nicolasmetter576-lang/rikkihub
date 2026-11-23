package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/** 作品工作区的作品；不是作品工作区、读不了或不在 FILES 区时为 null。结构变了会更新。 */
@Composable
internal fun rememberWorkspaceBook(workspaceId: String, area: WorkspaceStorageArea): Book? {
    val store = koinInject<BookStore>()
    val repository = koinInject<WorkspaceRepository>()
    val book by produceState<Book?>(null, workspaceId, area) {
        if (area != WorkspaceStorageArea.FILES) return@produceState
        store.changes.filter { it == workspaceId }.map { }.onStart { emit(Unit) }.collect {
            value = try {
                if (repository.getById(workspaceId)?.bookMode == true) store.open(workspaceId) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    return book
}

/** 正在编辑的文件是哪个作品的哪一章。 */
internal data class BookChapterRef(val workspaceId: String, val chapterId: String)

/** 编辑器里的前后章。 */
internal class BookChapterNav(
    val number: Int,
    val count: Int,
    val previous: String?,
    val next: String?,
    /** 当前文件已经可以离开后，打开另一章。 */
    val open: (String) -> Unit,
)

internal fun bookChapterNav(book: Book?, path: String, open: (String) -> Unit): BookChapterNav? {
    val entry = book?.entryByPath(path) ?: return null
    val readable = book.entries.filterNot { it.missing }
    val index = readable.indexOfFirst { it.id == entry.id }
    if (index < 0) return null
    return BookChapterNav(entry.number, book.entries.size, readable.getOrNull(index - 1)?.path, readable.getOrNull(index + 1)?.path, open)
}

@Composable
internal fun BookChapterNavButtons(nav: BookChapterNav, onGo: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.previous?.let(onGo) }, enabled = nav.previous != null, modifier = Modifier.size(32.dp)) {
            Icon(HugeIcons.ArrowLeft01, stringResource(R.string.book_chapter_previous), Modifier.size(16.dp))
        }
        Text("${nav.number}/${nav.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { nav.next?.let(onGo) }, enabled = nav.next != null, modifier = Modifier.size(32.dp)) {
            Icon(HugeIcons.ArrowRight01, stringResource(R.string.book_chapter_next), Modifier.size(16.dp))
        }
    }
}
