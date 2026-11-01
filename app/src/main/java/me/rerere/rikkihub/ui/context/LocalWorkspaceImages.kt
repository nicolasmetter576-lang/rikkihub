package me.rerere.rikkihub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkihub.data.workspace.WorkspaceImageContext

/**
 * The workspace behind the content being shown, so a Markdown image written as a workspace path
 * (`/workspace/out.png`) can be loaded from there. Null outside a conversation with a workspace.
 */
val LocalWorkspaceImages = compositionLocalOf<WorkspaceImageContext?> { null }
