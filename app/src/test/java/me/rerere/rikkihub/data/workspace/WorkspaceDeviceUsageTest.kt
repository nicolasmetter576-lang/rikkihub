package me.rerere.rikkihub.data.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDeviceUsageTest {
    @Test fun dispatchedOrPendingWorkBlocksOnlyItsOwnWorkspaceAndUnregisterReleasesIt() {
        val usage = WorkspaceDeviceUsage()
        var pending = true
        val unregister = usage.registerProbe("chat-service") { it == "w1" && pending }
        assertTrue(usage.isBusy("w1"))
        assertFalse(usage.isBusy("w2"))
        pending = false
        assertFalse(usage.isBusy("w1"))
        pending = true
        unregister()
        assertFalse(usage.isBusy("w1"))
    }

    @Test fun probeFailureIsFailClosedNotSilentlyIdle() {
        val usage = WorkspaceDeviceUsage()
        usage.registerProbe("bad-probe") { error("cannot determine usage") }
        assertTrue(usage.isBusy("w1"))
    }
}
