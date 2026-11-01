package me.rerere.rikkihub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceImageRefsTest {
    private val cwd = "/workspace"

    private fun path(reference: String, cwd: String = this.cwd) = WorkspaceImageRefs.guestPath(reference, cwd)

    @Test
    fun `absolute guest paths are used as written`() {
        assertEquals("/workspace/out.png", path("/workspace/out.png"))
        assertEquals("/tmp/plot.png", path("/tmp/plot.png"))
        assertEquals("/upload/photo.jpg", path("/upload/photo.jpg"))
        assertEquals("/workspace/a/b.png", path("/workspace//a/./b.png"))
    }

    @Test
    fun `an absolute path does not need an image extension`() {
        assertEquals("/workspace/figure", path("/workspace/figure"))
    }

    @Test
    fun `percent escapes are decoded as utf8`() {
        assertEquals("/workspace/my pic.png", path("/workspace/my%20pic.png"))
        assertEquals("/workspace/图 表.png", path("/workspace/%E5%9B%BE%20%E8%A1%A8.png"))
        assertEquals("/workspace/a+b.png", path("/workspace/a+b.png"))
        assertEquals("/workspace/100%.png", path("/workspace/100%.png"))
        assertEquals("/workspace/%zz.png", path("/workspace/%zz.png"))
        assertEquals("/workspace/😀.png", path("/workspace/😀.png"))
    }

    @Test
    fun `query and fragment are dropped`() {
        assertEquals("/workspace/out.png", path("/workspace/out.png?v=2"))
        assertEquals("/workspace/out.png", path("/workspace/out.png#top"))
        assertEquals("/workspace/a b.png", path("/workspace/a%20b.png?x#y"))
    }

    @Test
    fun `an encoded question mark stays part of the name`() {
        assertEquals("/workspace/what?.png", path("/workspace/what%3F.png"))
    }

    @Test
    fun `angle brackets around the destination are removed`() {
        assertEquals("/workspace/my pic.png", path("</workspace/my pic.png>"))
    }

    @Test
    fun `relative references are resolved against the working directory`() {
        assertEquals("/workspace/out.png", path("out.png"))
        assertEquals("/workspace/img/out.png", path("./img/out.png"))
        assertEquals("/workspace/proj/out.png", path("out.png", cwd = "/workspace/proj"))
        assertEquals("/workspace/proj/img/a b.PNG", path("img/a%20b.PNG", cwd = "/workspace/proj"))
    }

    @Test
    fun `a relative reference must look like an image`() {
        assertNull(path("notes"))
        assertNull(path("readme.md"))
        assertNull(path("archive.zip"))
        assertNull(path("png"))
    }

    @Test
    fun `other addresses are not workspace references`() {
        assertNull(path("https://example.com/a.png"))
        assertNull(path("http://example.com/a.png"))
        assertNull(path("file:///workspace/a.png"))
        assertNull(path("content://media/1"))
        assertNull(path("data:image/png;base64,AAAA"))
        assertNull(path("//example.com/a.png"))
        assertNull(path("#anchor"))
        assertNull(path(""))
        assertNull(path("   "))
        assertNull(path("<>"))
    }

    @Test
    fun `traversal and control characters are refused`() {
        assertNull(path("/workspace/../etc/passwd"))
        assertNull(path("../out.png"))
        assertNull(path("img/../../out.png"))
        assertNull(path("/workspace/%2e%2e/out.png"))
        assertNull(path("/workspace/%2E%2E/%2e%2e/out.png"))
        assertNull(path("/workspace/a%00.png"))
        assertNull(path("/workspace\\..\\a.png"))
    }

    @Test
    fun `the root alone is not a file`() {
        assertNull(path("/"))
        assertNull(path("/."))
    }

    @Test
    fun `context follows the conversation's working directory and confinement`() {
        assertNull(workspaceImageContext(null, "/workspace/a", true))

        val whole = workspaceImageContext("ws", null, true)!!
        assertEquals(WorkspaceImageContext("ws", null, "/workspace"), whole)

        val confined = workspaceImageContext("ws", "/workspace/a/b", true)!!
        assertEquals(WorkspaceImageContext("ws", "a/b", "/workspace/a/b"), confined)

        val notConfined = workspaceImageContext("ws", "/workspace/a/b", false)!!
        assertEquals(WorkspaceImageContext("ws", null, "/workspace/a/b"), notConfined)

        val relativeForm = workspaceImageContext("ws", "a/b", true)!!
        assertEquals("a/b", relativeForm.filesScope)
    }

    @Test
    fun `an unusable working directory gives no context`() {
        assertNull(workspaceImageContext("ws", "/etc", true))
        assertNull(workspaceImageContext("ws", "/workspace/../x", true))
        assertNull(workspaceImageContext("ws", "/etc", false))
    }
}
