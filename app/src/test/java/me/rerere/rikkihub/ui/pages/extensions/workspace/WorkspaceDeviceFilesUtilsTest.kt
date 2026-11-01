package me.rerere.rikkihub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceDeviceFilesUtilsTest {

    @Test
    fun `normalize strips slashes dots and blanks`() {
        assertEquals("a/b.txt", normalizeDeviceRelativePath("/a//b.txt/"))
        assertEquals("a/b", normalizeDeviceRelativePath("a/./b"))
        assertEquals("a/b", normalizeDeviceRelativePath("a\\b"))
        assertEquals("", normalizeDeviceRelativePath("  /  "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalize rejects parent traversal`() {
        normalizeDeviceRelativePath("a/../b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalize rejects leading dotdot`() {
        normalizeDeviceRelativePath("../x")
    }

    @Test
    fun `parent path`() {
        assertEquals("a", deviceParentPath("a/b.txt"))
        assertEquals("", deviceParentPath("b.txt"))
        assertEquals("", deviceParentPath(""))
    }

    @Test
    fun `child path joins without leaking`() {
        assertEquals("a/b.txt", deviceChildPath("a", "b.txt"))
        assertEquals("b.txt", deviceChildPath("", "b.txt"))
        assertEquals("a/b/c", deviceChildPath("a", "b/c"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `child path rejects escape`() {
        deviceChildPath("a", "../x")
    }

    @Test
    fun `file name takes last segment`() {
        assertEquals("b.txt", deviceFileName("a/b.txt"))
        assertEquals("b.txt", deviceFileName("b.txt"))
    }

    @Test
    fun `move target validation`() {
        assertEquals("a/c.txt", validDeviceMoveTarget("a/b.txt", "a/c.txt"))
        assertEquals("c.txt", validDeviceMoveTarget("a/b.txt", "c.txt"))
        // 等于源路径 / 空 / 逃逸 → 非法
        assertNull(validDeviceMoveTarget("a/b.txt", "a/b.txt"))
        assertNull(validDeviceMoveTarget("a/b.txt", "  "))
        assertNull(validDeviceMoveTarget("a/b.txt", "../c.txt"))
    }
}
