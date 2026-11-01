package me.rerere.rikkihub.ui.components.ai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class CropFileLeaseTest {
    @get:Rule val folder = TemporaryFolder()
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 0, 0)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private fun prepared(root: File): CropPendingFiles =
        claimCropFiles(root).copy(encoding = CropOutputEncoding.JPEG).also {
            cropLeaseFile(root, it, "result.jpg").writeBytes(jpeg)
        }

    @Test fun `each request owns a separate directory and marker`() {
        val root = folder.newFolder("crop")
        val a = claimCropFiles(root)
        val b = claimCropFiles(root)
        assertNotEquals(a.directoryPath, b.directoryPath)
        assertNotEquals(a.ownershipToken, b.ownershipToken)
        assertEquals(root.canonicalFile, File(a.directoryPath).canonicalFile.parentFile)
        assertTrue(File(a.directoryPath, ".lease").isFile)
    }

    @Test(timeout = 10_000) fun `output remains until suspended consumer has actually read it`() = runBlocking {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        val pending = prepared(root)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val output = cropLeaseFile(root, pending, "result.jpg")
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            consumeCropOutput(root, pending) { file ->
                started.complete(Unit)
                release.await()
                assertArrayEquals(jpeg, file.readBytes())
            }
        }
        started.await()
        assertTrue(output.exists())
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.await()
        assertFalse(output.exists())
        assertFalse(File(pending.directoryPath).exists())
    }

    @Test fun `consumer failure is preserved and owned temporary files are cleaned`() = runBlocking {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        val pending = prepared(root)
        val failure = runCatching {
            consumeCropOutput(root, pending) { throw IOException("upload failed") }
        }.exceptionOrNull()
        assertEquals("upload failed", failure?.message)
        assertFalse(File(pending.directoryPath).exists())
    }

    @Test(timeout = 10_000) fun `real cancellation cleans only after the consumer unwinds`() = runBlocking {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        val pending = prepared(root)
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var unwound = false
        val job = async {
            consumeCropOutput(root, pending) { file ->
                try { started.complete(Unit); never.await() } finally {
                    assertTrue(file.exists())
                    unwound = true
                }
            }
        }
        started.await()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertTrue(unwound)
        assertFalse(File(pending.directoryPath).exists())
    }

    @Test fun `encoding mismatch never reaches the consumer`() = runBlocking {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        val pending = prepared(root)
        cropLeaseFile(root, pending, "result.jpg").writeBytes(png)
        var consumed = false
        val error = runCatching { consumeCropOutput(root, pending) { consumed = true } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(consumed)
        assertFalse(File(pending.directoryPath).exists())
    }

    @Test fun `unprepared or missing outputs do not pretend to have been consumed`() = runBlocking {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        for (encoding in listOf(null, CropOutputEncoding.JPEG)) {
            val pending = claimCropFiles(root).copy(encoding = encoding)
            assertTrue(runCatching { consumeCropOutput(root, pending) { fail("not consumed") } }.isFailure)
            assertFalse(File(pending.directoryPath).exists())
        }
    }

    @Test fun `prepared snapshot survives serializable round trip without image bytes`() {
        val root = folder.newFolder("crop")
        val pending = prepared(root)
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(pending) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as CropPendingFiles }
        assertEquals(pending, restored)
        assertTrue(bytes.size < 4096)
        assertEquals(cropLeaseFile(root, pending, "result.jpg"), cropLeaseFile(root, restored, "result.jpg"))
    }

    @Test fun `restored wrong marker never authorizes cleanup`() {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        val pending = prepared(root)
        cleanupCropFiles(root, pending.copy(ownershipToken = "wrong"))
        assertTrue(File(pending.directoryPath, "result.jpg").exists())
        assertThrows(IllegalStateException::class.java) { cropLeaseFile(root, pending.copy(ownershipToken = "wrong"), "result.jpg") }
    }

    @Test fun `external and symlink directories are never cleaned`() {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        val pending = prepared(root)
        val outside = folder.newFolder("crop_user")
        val original = File(outside, "result.jpg").apply { writeBytes(jpeg) }
        cleanupCropFiles(root, pending.copy(directoryPath = outside.path))
        val link = File(root, "crop_link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        cleanupCropFiles(root, pending.copy(directoryPath = link.path))
        assertTrue(original.exists())
    }

    @Test fun `only this captured camera temporary is deleted and a lease never touches camera files`() = runBlocking {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        val camera = File(cache, "camera_${Uuid.random()}.jpg").apply { writeBytes(jpeg) }
        val otherCamera = File(cache, "camera_${Uuid.random()}.jpg").apply { writeBytes(jpeg) }
        consumeCropOutput(root, prepared(root)) { assertTrue(camera.exists()) }
        assertTrue(camera.exists())
        assertEquals(camera.canonicalPath, ownedCameraPath(cache, camera))
        deleteOwnedCameraFile(cache, ownedCameraPath(cache, camera))
        assertFalse(camera.exists())
        assertTrue(otherCamera.exists())
    }

    @Test fun `camera cleanup rejects user originals and links even if names look similar`() {
        val cache = folder.newFolder("cache")
        val outside = folder.newFolder("user")
        val original = File(outside, "camera_${Uuid.random()}.jpg").apply { writeBytes(jpeg) }
        val other = File(cache, "photo.jpg").apply { writeBytes(jpeg) }
        val link = File(cache, "camera_${Uuid.random()}.jpg")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        deleteOwnedCameraFile(cache, original.path)
        deleteOwnedCameraFile(cache, other.path)
        deleteOwnedCameraFile(cache, link.path)
        assertTrue(original.exists())
        assertTrue(other.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun `lease cleanup does not recursively delete unknown files`() {
        val cache = folder.newFolder("cache")
        val root = File(cache, "crop")
        val pending = prepared(root)
        val other = File(pending.directoryPath, "unknown.txt").apply { writeText("keep") }
        cleanupCropFiles(root, pending)
        assertTrue(other.exists())
    }

    @Test fun `traversal and symlink input paths cannot be leased`() {
        val root = folder.newFolder("crop")
        val pending = claimCropFiles(root)
        assertThrows(IllegalArgumentException::class.java) { cropLeaseFile(root, pending, "../user.txt") }
        val outside = folder.newFile("user.txt")
        Files.createSymbolicLink(File(pending.directoryPath, "result.jpg").toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { cropLeaseFile(root, pending, "result.jpg") }
        assertThrows(IllegalArgumentException::class.java) { cropLeaseFile(root, pending, "source.jpg") }
    }

    @Test fun `file type comes from content rather than display name`() {
        assertEquals("jpg", cropInputExtension(ByteArrayInputStream(jpeg)))
        assertEquals("png", cropInputExtension(ByteArrayInputStream(png)))
        assertEquals("gif", cropInputExtension(ByteArrayInputStream("GIF89a".toByteArray())))
        assertEquals("img", cropInputExtension(ByteArrayInputStream("random.bin".toByteArray())))
    }
}
