package me.rerere.rikkihub.ui.components.ai.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceQueryParseTest {

    @Test
    fun `device root queries`() {
        assertNull(parseDeviceQuery("device"))
        assertNull(parseDeviceQuery("device/"))
        assertEquals(DeviceQuery.Root, parseDeviceQuery("/device"))
        assertEquals(DeviceQuery.Root, parseDeviceQuery("/device/"))
    }

    @Test
    fun `alias without trailing slash lists mounts`() {
        assertEquals(DeviceQuery.Alias("docs", null), parseDeviceQuery("/device/docs"))
        assertEquals(DeviceQuery.Alias("d", null), parseDeviceQuery("/device/d"))
    }

    @Test
    fun `alias with path lists children`() {
        assertEquals(DeviceQuery.Alias("docs", ""), parseDeviceQuery("/device/docs/"))
        assertEquals(DeviceQuery.Alias("docs", "re"), parseDeviceQuery("/device/docs/re"))
        assertEquals(DeviceQuery.Alias("docs", "a/b"), parseDeviceQuery("/device/docs/a/b"))
    }

    @Test
    fun `non device queries are not intercepted`() {
        assertNull(parseDeviceQuery(""))
        assertNull(parseDeviceQuery("workspace/foo"))
        assertNull(parseDeviceQuery("devices/x"))
        assertNull(parseDeviceQuery("foo"))
    }
}
