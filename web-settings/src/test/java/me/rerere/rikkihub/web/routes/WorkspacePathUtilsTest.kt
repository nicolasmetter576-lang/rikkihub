package me.rerere.rikkihub.web.routes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePathUtilsTest {
    @Test
    fun `text files are editable in the web client`() {
        assertTrue(isWorkspaceTextFile("notes.md"))
        assertTrue(isWorkspaceTextFile("App.kt"))
        assertTrue(isWorkspaceTextFile("script.sh"))
        assertFalse(isWorkspaceTextFile("photo.png"))
        assertFalse(isWorkspaceTextFile("archive.zip"))
    }

    @Test
    fun `image files are previewed in the web client`() {
        assertTrue(isWorkspaceImageFile("cover.webp"))
        assertTrue(isWorkspaceImageFile("icon.SVG"))
        assertFalse(isWorkspaceImageFile("notes.md"))
        assertFalse(isWorkspaceImageFile("video.mp4"))
    }
}
