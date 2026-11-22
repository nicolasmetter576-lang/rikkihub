package me.rerere.rikkihub.data.workspace

import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.util.Log
import androidx.core.net.toUri
import me.rerere.workspace.WorkspaceUrlHandoff
import java.io.File

/**
 * App side of [WorkspaceUrlHandoff]: opens in the browser the links that `xdg-open` inside a workspace
 * drops into [dir] (bound at [WorkspaceUrlHandoff.GUEST_DIR]). Requests left from an earlier run are
 * discarded, not opened.
 */
class WorkspaceUrlOpener(
    private val context: Context,
    val dir: File,
) {
    @Suppress("DEPRECATION") // FileObserver(File, Int) needs API 29
    private val observer = object : FileObserver(dir.absolutePath, MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == null || path.startsWith('.') || !path.endsWith(WorkspaceUrlHandoff.REQUEST_SUFFIX)) return
            handle(File(dir, path))
        }
    }

    fun start() {
        dir.listFiles()?.forEach { it.delete() }
        observer.startWatching()
    }

    private fun handle(request: File) {
        // A rename and a direct write can both report the same file; whoever reads it first deletes it.
        val text = runCatching {
            request.takeIf { it.length() <= WorkspaceUrlHandoff.MAX_REQUEST_BYTES }?.readText()
        }.getOrNull()
        request.delete()
        val url = text?.let(WorkspaceUrlHandoff::parseRequest) ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "Failed to open workspace link", it) }
    }

    private companion object {
        const val TAG = "WorkspaceUrlOpener"
    }
}
