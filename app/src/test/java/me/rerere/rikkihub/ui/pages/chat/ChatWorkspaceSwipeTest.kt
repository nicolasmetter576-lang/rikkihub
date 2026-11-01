package me.rerere.rikkihub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWorkspaceSwipeTest {
    @Test
    fun filesGesturesNeedBoundWorkspaceAndClosedConversationDrawer() {
        assertTrue(
            ChatWorkspaceSwipe.filesGesturesEnabled(
                workspaceBound = true,
                conversationDrawerOpen = false,
            )
        )
        assertFalse(
            ChatWorkspaceSwipe.filesGesturesEnabled(
                workspaceBound = false,
                conversationDrawerOpen = false,
            )
        )
        assertFalse(
            ChatWorkspaceSwipe.filesGesturesEnabled(
                workspaceBound = true,
                conversationDrawerOpen = true,
            )
        )
    }

    @Test
    fun conversationGesturesDisabledWhileFilesOpen() {
        assertTrue(ChatWorkspaceSwipe.conversationGesturesEnabled(filesDrawerOpen = false))
        assertFalse(ChatWorkspaceSwipe.conversationGesturesEnabled(filesDrawerOpen = true))
    }

    @Test
    fun backPrefersFilesNavigationThenFilesCloseThenConversationClose() {
        assertEquals(
            ChatWorkspaceSwipe.BackAction.NavigateUpInFiles,
            ChatWorkspaceSwipe.backAction(
                filesDrawerOpen = true,
                canNavigateUpInFiles = true,
                conversationDrawerOpen = true,
            ),
        )
        assertEquals(
            ChatWorkspaceSwipe.BackAction.CloseFiles,
            ChatWorkspaceSwipe.backAction(
                filesDrawerOpen = true,
                canNavigateUpInFiles = false,
                conversationDrawerOpen = true,
            ),
        )
        assertEquals(
            ChatWorkspaceSwipe.BackAction.CloseConversation,
            ChatWorkspaceSwipe.backAction(
                filesDrawerOpen = false,
                canNavigateUpInFiles = false,
                conversationDrawerOpen = true,
            ),
        )
        assertEquals(
            ChatWorkspaceSwipe.BackAction.None,
            ChatWorkspaceSwipe.backAction(
                filesDrawerOpen = false,
                canNavigateUpInFiles = false,
                conversationDrawerOpen = false,
            ),
        )
    }

    @Test
    fun openGestureOnlyStartsOnRightEdgeBetweenTopBarAndInput() {
        assertTrue(
            ChatWorkspaceSwipe.isOpenGestureOrigin(
                x = 990f,
                y = 400f,
                width = 1000f,
                height = 800f,
                edgePx = 24f,
                topInsetPx = 56f,
                bottomInsetPx = 96f,
            )
        )
        assertFalse(
            ChatWorkspaceSwipe.isOpenGestureOrigin(
                x = 500f,
                y = 400f,
                width = 1000f,
                height = 800f,
                edgePx = 24f,
                topInsetPx = 56f,
                bottomInsetPx = 96f,
            )
        )
        assertFalse(
            ChatWorkspaceSwipe.isOpenGestureOrigin(
                x = 990f,
                y = 20f,
                width = 1000f,
                height = 800f,
                edgePx = 24f,
                topInsetPx = 56f,
                bottomInsetPx = 96f,
            )
        )
        assertFalse(
            ChatWorkspaceSwipe.isOpenGestureOrigin(
                x = 990f,
                y = 750f,
                width = 1000f,
                height = 800f,
                edgePx = 24f,
                topInsetPx = 56f,
                bottomInsetPx = 96f,
            )
        )
    }

    @Test
    fun settleUsesVelocityThenPosition() {
        val width = 1000f
        assertTrue(
            ChatWorkspaceSwipe.shouldOpenAfterDrag(
                offset = 800f,
                panelWidth = width,
                velocity = -1000f,
            )
        )
        assertFalse(
            ChatWorkspaceSwipe.shouldOpenAfterDrag(
                offset = 100f,
                panelWidth = width,
                velocity = 1000f,
            )
        )
        assertTrue(
            ChatWorkspaceSwipe.shouldOpenAfterDrag(
                offset = 700f,
                panelWidth = width,
                velocity = 0f,
            )
        )
        assertFalse(
            ChatWorkspaceSwipe.shouldOpenAfterDrag(
                offset = 800f,
                panelWidth = width,
                velocity = 0f,
            )
        )
        assertFalse(
            ChatWorkspaceSwipe.shouldOpenAfterDrag(
                offset = 0f,
                panelWidth = 0f,
                velocity = -1000f,
            )
        )
    }
}
