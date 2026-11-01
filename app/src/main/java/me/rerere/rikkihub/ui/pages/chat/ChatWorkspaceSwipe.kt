package me.rerere.rikkihub.ui.pages.chat

/**
 * Gesture policy for the chat <-> bound-workspace files overlay.
 *
 * The files page is a full-screen panel that sits off the right edge:
 * - swipe from the right edge toward the left opens it
 * - swipe from left toward the right (or system back) returns to chat
 *
 * The conversation drawer lives on the left edge. The two never accept
 * gestures at the same time, otherwise their horizontal drags would fight.
 */
object ChatWorkspaceSwipe {
    const val SETTLE_FRACTION = 0.25f
    const val VELOCITY_THRESHOLD_PX = 900f

    fun filesGesturesEnabled(
        workspaceBound: Boolean,
        conversationDrawerOpen: Boolean,
    ): Boolean = workspaceBound && !conversationDrawerOpen

    fun conversationGesturesEnabled(filesDrawerOpen: Boolean): Boolean = !filesDrawerOpen

    fun isOpenGestureOrigin(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        edgePx: Float,
        topInsetPx: Float,
        bottomInsetPx: Float,
    ): Boolean {
        if (width <= 0f || height <= 0f) return false
        val onRightEdge = x >= width - edgePx
        val belowTopBar = y >= topInsetPx
        val aboveInput = y <= height - bottomInsetPx
        return onRightEdge && belowTopBar && aboveInput
    }

    fun shouldOpenAfterDrag(
        offset: Float,
        panelWidth: Float,
        velocity: Float,
        settleFraction: Float = SETTLE_FRACTION,
        velocityThreshold: Float = VELOCITY_THRESHOLD_PX,
    ): Boolean {
        if (panelWidth <= 0f) return false
        return when {
            velocity < -velocityThreshold -> true
            velocity > velocityThreshold -> false
            else -> offset < panelWidth * (1f - settleFraction)
        }
    }

    enum class BackAction {
        None,
        NavigateUpInFiles,
        CloseFiles,
        CloseConversation,
    }

    fun backAction(
        filesDrawerOpen: Boolean,
        canNavigateUpInFiles: Boolean,
        conversationDrawerOpen: Boolean,
    ): BackAction = when {
        filesDrawerOpen && canNavigateUpInFiles -> BackAction.NavigateUpInFiles
        filesDrawerOpen -> BackAction.CloseFiles
        conversationDrawerOpen -> BackAction.CloseConversation
        else -> BackAction.None
    }
}
