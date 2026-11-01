package me.rerere.rikkihub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceDeviceDirectoryUtilsTest {

    @Test
    fun `valid aliases pass`() {
        listOf("a", "docs", "my-dir_1", "z9").forEach {
            assertNull(it, validateDeviceMountAlias(it))
        }
    }

    @Test
    fun `empty alias is rejected`() {
        assertEquals(WorkspaceDeviceAliasViolation.EMPTY, validateDeviceMountAlias(""))
        assertEquals(WorkspaceDeviceAliasViolation.EMPTY, validateDeviceMountAlias("   "))
    }

    @Test
    fun `alias must start with lowercase letter`() {
        assertEquals(WorkspaceDeviceAliasViolation.FORMAT, validateDeviceMountAlias("9lives"))
        assertEquals(WorkspaceDeviceAliasViolation.FORMAT, validateDeviceMountAlias("_x"))
        assertEquals(WorkspaceDeviceAliasViolation.FORMAT, validateDeviceMountAlias("Docs"))
        assertEquals(WorkspaceDeviceAliasViolation.FORMAT, validateDeviceMountAlias("dir name"))
        assertEquals(WorkspaceDeviceAliasViolation.FORMAT, validateDeviceMountAlias("dir/name"))
    }

    @Test
    fun `alias longer than 32 chars is rejected`() {
        assertNull(validateDeviceMountAlias("a" + "b".repeat(31)))
        assertEquals(
            WorkspaceDeviceAliasViolation.FORMAT,
            validateDeviceMountAlias("a" + "b".repeat(32)),
        )
    }

    @Test
    fun `display path strips separators and dot segments`() {
        assertEquals("DCIM/Camera", deviceMountDisplayPath("DCIM/Camera"))
        assertEquals("DCIM/Camera", deviceMountDisplayPath("DCIM\\Camera"))
        assertEquals("DCIM", deviceMountDisplayPath("/DCIM/"))
        assertEquals("a/b", deviceMountDisplayPath("a/./b"))
    }

    @Test
    fun `display path never returns absolute or traversal`() {
        assertEquals("/", deviceMountDisplayPath(""))
        assertEquals("/", deviceMountDisplayPath("../.."))
        // 防御：即便上游异常传入绝对路径，展示层也剥掉根前缀
        assertEquals("storage/emulated/0", deviceMountDisplayPath("/storage/emulated/0"))
    }
}
