package me.rerere.rikkihub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceImageRefs
import me.rerere.rikkihub.ui.context.LocalWorkspaceImages
import org.koin.compose.koinInject
import java.io.File

/**
 * An image written in Markdown or HTML. When the conversation has a workspace and the address is a
 * workspace path, the file is read from there with the same view the model has (mounts and working
 * directory confinement); anything else, or a path that does not resolve, loads as before.
 */
@Composable
fun WorkspaceMarkdownImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val workspace = LocalWorkspaceImages.current
    val guestPath = remember(url, workspace?.cwd) {
        workspace?.let { WorkspaceImageRefs.guestPath(url, it.cwd) }
    }
    if (workspace == null || guestPath == null) {
        ZoomableAsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    val repository: WorkspaceRepository = koinInject()
    val resolution by produceState<Resolution>(Resolution.Pending, workspace, guestPath) {
        val file = repository.resolveRootfsFile(workspace.workspaceId, guestPath, workspace.filesScope)
        value = if (file != null) Resolution.Found(file) else Resolution.Missing
    }
    val source = when (val current = resolution) {
        Resolution.Pending -> null
        Resolution.Missing -> url
        is Resolution.Found -> current.file.absolutePath
    }
    ZoomableAsyncImage(
        model = source,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        // The same path can be rewritten with a new picture; the file's age and size keep the cache honest.
        memoryCacheKey = (resolution as? Resolution.Found)?.file?.let {
            "${it.absolutePath}:${it.lastModified()}:${it.length()}"
        },
    )
}

private sealed interface Resolution {
    data object Pending : Resolution
    data object Missing : Resolution
    data class Found(val file: File) : Resolution
}
