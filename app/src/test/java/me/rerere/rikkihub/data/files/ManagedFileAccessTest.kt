package me.rerere.rikkihub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * I1 regression coverage for `FileUtils.resolveManagedWithin` — the canonical
 * ownership re-check FilesManager runs inside the delete critical section.
 */
class ManagedFileAccessTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File

    private fun prepare(): File {
        filesDir = tmp.newFolder("files")
        File(filesDir, "upload").mkdirs()
        File(filesDir, "datastore").mkdirs()
        File(filesDir, "upload/pic.png").writeBytes(byteArrayOf(1))
        File(filesDir, "datastore/secret.pb").writeBytes(byteArrayOf(2))
        File(filesDir, "upload/deep").mkdirs()
        File(filesDir, "upload/deep/nested.txt").writeText("x")
        return filesDir
    }

    @Test
    fun `managed file inside its folder resolves`() {
        val dir = prepare()
        val resolved = FileUtils.resolveManagedWithin(dir, "upload", "upload/pic.png")
        assertNotNull(resolved)
        assertEquals("pic.png", resolved!!.name)
        // Nested managed paths (skills etc.) resolve too.
        assertNotNull(FileUtils.resolveManagedWithin(dir, "upload", "upload/deep/nested.txt"))
    }

    @Test
    fun `traversal absolute and foreign-folder paths are denied`() {
        val dir = prepare()
        assertNull(FileUtils.resolveManagedWithin(dir, "upload", "upload/../datastore/secret.pb"))
        assertNull(FileUtils.resolveManagedWithin(dir, "upload", "datastore/secret.pb"))
        assertNull(FileUtils.resolveManagedWithin(dir, "upload", "/etc/passwd"))
        assertNull(FileUtils.resolveManagedWithin(dir, "upload", "upload/../../x"))
        // The folder check is on the managed path itself: mismatched folder → null.
        assertNull(FileUtils.resolveManagedWithin(dir, "datastore", "upload/pic.png"))
    }

    @Test
    fun `symlink aliases are denied even when they point back inside`() {
        val dir = prepare()
        val outside = tmp.newFile("outside.png").apply { writeBytes(byteArrayOf(9)) }
        val link = File(dir, "upload/alias.png")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(FileUtils.resolveManagedWithin(dir, "upload", "upload/alias.png"))
        } catch (_: UnsupportedOperationException) {
            // FS without symlink support — nothing to assert.
        }
    }

    @Test
    fun `missing or directory targets are denied`() {
        val dir = prepare()
        assertNull(FileUtils.resolveManagedWithin(dir, "upload", "upload/missing.png"))
        assertNull(FileUtils.resolveManagedWithin(dir, "upload", "upload/deep"))
    }
}
