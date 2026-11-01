package me.rerere.rikkihub.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingRestoreDeviceActivationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `database restore invalidates old activations before installing and only once after completion`() = runBlocking {
        val database = File(temporary.newFolder("db"), "app.db").apply { writeText("old-db") }
        val activation = File(temporary.root, "activation.enc").apply { writeText("old-install-activation") }
        var calls = 0
        val restore = PendingRestore(temporary.newFolder("restore"), database, temporary.newFolder("files")) {
            assertEquals("old-db", database.readText())
            assertTrue(activation.delete())
            calls++
        }
        val staging = restore.createStagingDirectory()
        File(staging, "payload/database/app.db").apply { parentFile!!.mkdirs(); writeText("restored-db") }
        restore.publish(staging)
        assertTrue(restore.apply {})
        assertEquals("restored-db", database.readText())
        assertFalse(activation.exists())
        assertFalse(restore.apply {})
        assertEquals(1, calls)
    }

    @Test fun `settings-only restore and ordinary startup do not invalidate activations`() = runBlocking {
        val database = File(temporary.newFolder("db"), "app.db").apply { writeText("existing-db") }
        var calls = 0
        val restore = PendingRestore(temporary.newFolder("restore"), database, temporary.newFolder("files")) { calls++ }
        assertFalse(restore.apply {})
        val staging = restore.createStagingDirectory()
        File(staging, "settings.json").writeText("{}")
        restore.publish(staging)
        var settings = ""
        assertTrue(restore.apply { settings = it })
        assertEquals("{}", settings)
        assertEquals("existing-db", database.readText())
        assertEquals(0, calls)
    }

    @Test fun `failed activation invalidation aborts restore without changing original database`() = runBlocking {
        val database = File(temporary.newFolder("db"), "app.db").apply { writeText("old-db") }
        val restore = PendingRestore(temporary.newFolder("restore"), database, temporary.newFolder("files")) {
            error("activation invalidation failed")
        }
        val staging = restore.createStagingDirectory()
        File(staging, "payload/database/app.db").apply { parentFile!!.mkdirs(); writeText("new-db") }
        restore.publish(staging)
        try {
            restore.apply {}
            error("must refuse installation")
        } catch (_: RestoreFailedException) {
            assertEquals("old-db", database.readText())
        }
    }
}
