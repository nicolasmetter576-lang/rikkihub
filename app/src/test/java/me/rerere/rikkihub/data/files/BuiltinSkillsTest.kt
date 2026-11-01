package me.rerere.rikkihub.data.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class MemorySkillAssets(var files: Map<String, ByteArray>) : BuiltinSkillAssets {
    val opens = AtomicInteger()
    var fail = false
    override fun children(path: String): List<String> {
        val relative = path.removePrefix("builtin_skills").trimStart('/')
        val prefix = if (relative.isEmpty()) "" else "$relative/"
        return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
    }
    override fun open(path: String) = ByteArrayInputStream(
        if (fail) throw IOException("asset failed") else files.getValue(path.removePrefix("builtin_skills/"))
    ).also { opens.incrementAndGet() }
}

class BuiltinSkillsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun assets(body: String = "one") = MemorySkillAssets(mapOf("creator/SKILL.md" to body.toByteArray()))
    private fun root() = tmp.newFolder("files")
    private fun atomicMove(from: File, to: File) { Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE) }
    private fun transactions(root: File) = root.listFiles().orEmpty().filter { it.name.startsWith(".builtin-skills-update-") }

    @Test fun `first publication stamps only the published tree`() {
        val files = root()
        val published = BuiltinSkillPublisher(files, assets()).ensure("1")
        assertEquals(File(files, "builtin_skills"), published)
        assertEquals("one", published.resolve("creator/SKILL.md").readText())
        assertEquals("1", published.resolve(".package-stamp").readText())
        assertTrue(transactions(files).isEmpty())
    }
    @Test fun `same package version does not extract again`() {
        val source = assets()
        val publisher = BuiltinSkillPublisher(root(), source)
        publisher.ensure("1")
        publisher.ensure("1")
        assertEquals(1, source.opens.get())
    }
    @Test fun `package update replaces files and removes obsolete assets`() {
        val files = root()
        val source = assets()
        source.files = source.files + ("old/data.bin" to byteArrayOf(1, 2))
        val publisher = BuiltinSkillPublisher(files, source)
        publisher.ensure("1")
        source.files = mapOf("creator/SKILL.md" to "two".toByteArray())
        val updated = publisher.ensure("2")
        assertEquals("two", updated.resolve("creator/SKILL.md").readText())
        assertFalse(updated.resolve("old").exists())
        assertEquals("2", updated.resolve(".package-stamp").readText())
    }
    @Test fun `binary streams remain byte exact and shebang gets executable permission`() {
        val bytes = byteArrayOf(0, 0xff.toByte(), 0xfe.toByte(), 0x80.toByte(), 42)
        val source = assets().also { it.files = it.files + mapOf(
            "creator/icon.bin" to bytes, "creator/run.sh" to "#!/bin/sh\necho ok\n".toByteArray(),
        ) }
        val published = BuiltinSkillPublisher(root(), source).ensure("1")
        assertArrayEquals(bytes, published.resolve("creator/icon.bin").readBytes())
        assertTrue(published.resolve("creator/run.sh").canExecute())
        assertFalse(published.resolve("creator/icon.bin").canExecute())
    }
    @Test fun `asset failure keeps old tree and old stamp then retries same version`() {
        val files = root()
        val source = assets()
        val publisher = BuiltinSkillPublisher(files, source)
        val published = publisher.ensure("1")
        source.files = mapOf("creator/SKILL.md" to "two".toByteArray())
        source.fail = true
        assertThrows(IOException::class.java) { publisher.ensure("2") }
        assertEquals("one", published.resolve("creator/SKILL.md").readText())
        assertEquals("1", published.resolve(".package-stamp").readText())
        source.fail = false
        publisher.ensure("2")
        assertEquals("two", published.resolve("creator/SKILL.md").readText())
    }
    @Test fun `failed first extraction leaves no success stamp and is retryable`() {
        val files = root()
        val source = assets().also { it.fail = true }
        val publisher = BuiltinSkillPublisher(files, source)
        assertThrows(IOException::class.java) { publisher.ensure("1") }
        assertFalse(files.resolve("builtin_skills/.package-stamp").exists())
        assertTrue(transactions(files).isEmpty())
        source.fail = false
        assertTrue(publisher.ensure("1").isDirectory)
    }
    @Test fun `publication rename failure rolls back instead of deleting old tree`() {
        val files = root()
        BuiltinSkillPublisher(files, assets()).ensure("1")
        var fail = true
        val publisher = BuiltinSkillPublisher(files, assets("two")) { from, to ->
            if (from.name == "stage" && fail) { fail = false; throw IOException("publish failed") }
            atomicMove(from, to)
        }
        assertThrows(IOException::class.java) { publisher.ensure("2") }
        assertEquals("one", files.resolve("builtin_skills/creator/SKILL.md").readText())
        assertEquals("1", files.resolve("builtin_skills/.package-stamp").readText())
        assertTrue(transactions(files).isEmpty())
        publisher.ensure("2")
        assertEquals("two", files.resolve("builtin_skills/creator/SKILL.md").readText())
    }
    @Test fun `failed backup rename preserves old tree`() {
        val files = root()
        BuiltinSkillPublisher(files, assets()).ensure("1")
        val publisher = BuiltinSkillPublisher(files, assets("two")) { from, to ->
            if (to.name == "backup") throw IOException("backup failed")
            atomicMove(from, to)
        }
        assertThrows(IOException::class.java) { publisher.ensure("2") }
        assertEquals("one", files.resolve("builtin_skills/creator/SKILL.md").readText())
        assertTrue(transactions(files).isEmpty())
    }
    @Test fun `rollback failure retains owned backup and a fresh publisher recovers it`() {
        val files = root()
        BuiltinSkillPublisher(files, assets()).ensure("1")
        val publisher = BuiltinSkillPublisher(files, assets("two")) { from, to ->
            if (from.name == "stage" || from.name == "backup") throw IOException("rename failed")
            atomicMove(from, to)
        }
        assertThrows(IOException::class.java) { publisher.ensure("2") }
        val transaction = transactions(files).single()
        assertEquals("one", transaction.resolve("backup/creator/SKILL.md").readText())
        val recovered = BuiltinSkillPublisher(files, assets("two")).ensure("1")
        assertEquals("one", recovered.resolve("creator/SKILL.md").readText())
        assertTrue(transactions(files).isEmpty())
    }
    @Test fun `empty unstamped mount fallback never replaces retained rollback backup`() {
        val files = root()
        BuiltinSkillPublisher(files, assets()).ensure("1")
        val failing = BuiltinSkillPublisher(files, assets("two")) { from, to ->
            if (from.name == "stage" || from.name == "backup") throw IOException("rename failed")
            atomicMove(from, to)
        }
        assertThrows(IOException::class.java) { failing.ensure("2") }
        check(files.resolve("builtin_skills").mkdir()) // same empty fallback as mountDirectory
        val restored = BuiltinSkillPublisher(files, assets("two")).ensure("1")
        assertEquals("one", restored.resolve("creator/SKILL.md").readText())
        assertEquals("1", restored.resolve(".package-stamp").readText())
        assertTrue(transactions(files).isEmpty())
    }
    @Test fun `nonempty unstamped fallback keeps the only old backup until successful publication`() {
        val files = root()
        BuiltinSkillPublisher(files, assets()).ensure("1")
        val failing = BuiltinSkillPublisher(files, assets("two")) { from, to ->
            if (from.name == "stage" || from.name == "backup") throw IOException("rename failed")
            atomicMove(from, to)
        }
        assertThrows(IOException::class.java) { failing.ensure("2") }
        val retained = transactions(files).single()
        val fallback = files.resolve("builtin_skills").also { check(it.mkdir()) }
        fallback.resolve("unpublished.txt").writeText("unpublished")
        val source = assets("two").also { it.fail = true }
        val publisher = BuiltinSkillPublisher(files, source)
        assertThrows(IOException::class.java) { publisher.ensure("2") }
        assertEquals("one", retained.resolve("backup/creator/SKILL.md").readText())
        assertEquals("unpublished", fallback.resolve("unpublished.txt").readText())
        source.fail = false
        publisher.ensure("2")
        publisher.ensure("2") // subsequent safe recovery cleans the retained old transaction
        assertEquals("two", fallback.resolve("creator/SKILL.md").readText())
        assertTrue(transactions(files).isEmpty())
    }
    @Test fun `concurrent publishers serialize and never adopt another stage`() {
        val files = root()
        val source = assets()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs = (1..12).map { pool.submit<File> {
                start.await()
                BuiltinSkillPublisher(files, source).ensure("1")
            } }
            start.countDown()
            jobs.forEach { assertTrue(it.get(5, TimeUnit.SECONDS).isDirectory) }
            assertEquals(1, source.opens.get())
            assertTrue(transactions(files).isEmpty())
        } finally { pool.shutdownNow() }
    }
    @Test fun `symlink publication root is refused without touching outside files`() {
        val files = root()
        val outside = tmp.newFolder("outside").also { it.resolve("keep").writeText("safe") }
        Files.createSymbolicLink(files.resolve("builtin_skills").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { BuiltinSkillPublisher(files, assets()).ensure("1") }
        assertEquals("safe", outside.resolve("keep").readText())
        assertEquals(listOf("keep"), outside.list()!!.toList())
    }
    @Test fun `symlink lock and stamp are refused`() {
        val files = root()
        val outside = tmp.newFile("outside").also { it.writeText("safe") }
        val lock = files.resolve(".builtin-skills.lock")
        Files.createSymbolicLink(lock.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { BuiltinSkillPublisher(files, assets()).ensure("1") }
        Files.delete(lock.toPath())
        val published = BuiltinSkillPublisher(files, assets()).ensure("1")
        published.resolve(".package-stamp").delete()
        Files.createSymbolicLink(published.resolve(".package-stamp").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { BuiltinSkillPublisher(files, assets()).ensure("2") }
        assertEquals("safe", outside.readText())
    }
    @Test fun `unowned stages and symlink transactions are not deleted`() {
        val files = root()
        val unowned = files.resolve(".builtin-skills-update-unowned").also { it.mkdir(); it.resolve("keep").writeText("safe") }
        val outside = tmp.newFolder("outside").also { it.resolve("keep").writeText("safe") }
        Files.createSymbolicLink(files.resolve(".builtin-skills-update-link").toPath(), outside.toPath())
        BuiltinSkillPublisher(files, assets()).ensure("1")
        assertEquals("safe", unowned.resolve("keep").readText())
        assertEquals("safe", outside.resolve("keep").readText())
    }
    @Test fun `update cleanup never follows links in old builtin tree`() {
        val files = root()
        val publisher = BuiltinSkillPublisher(files, assets())
        val old = publisher.ensure("1")
        val outside = tmp.newFolder("outside").also { it.resolve("keep").writeText("safe") }
        Files.createSymbolicLink(old.resolve("external").toPath(), outside.toPath())
        publisher.ensure("2")
        assertEquals("safe", outside.resolve("keep").readText())
        assertFalse(files.resolve("builtin_skills/external").exists())
    }
    @Test fun `oversized published stamp safely reinstalls and failure retains old tree`() {
        val files = root()
        val source = assets()
        val publisher = BuiltinSkillPublisher(files, source)
        val published = publisher.ensure("1")
        published.resolve(".package-stamp").outputStream().use { output ->
            output.write('1'.code)
            repeat(2048) { output.write(ByteArray(1024) { 'x'.code.toByte() }) }
        }
        source.fail = true
        assertThrows(IOException::class.java) { publisher.ensure("1") }
        assertEquals("one", published.resolve("creator/SKILL.md").readText())
        assertTrue(published.resolve(".package-stamp").length() > 1024)
        source.fail = false
        publisher.ensure("1")
        assertEquals("1", published.resolve(".package-stamp").readText())
        assertEquals(2, source.opens.get())
    }
    @Test fun `oversized owner metadata is not adopted or deleted`() {
        val files = root()
        val unowned = files.resolve(".builtin-skills-update-oversized").also { it.mkdir() }
        unowned.resolve(".owner").outputStream().use { output ->
            output.write("rikkihub-builtin-skills-v1".toByteArray())
            repeat(2048) { output.write(ByteArray(1024)) }
        }
        unowned.resolve("keep").writeText("safe")
        BuiltinSkillPublisher(files, assets()).ensure("1")
        assertEquals("safe", unowned.resolve("keep").readText())
    }
    @Test fun `broken package descriptor is rejected only when the skills pair is exposed`() {
        val files = root()
        val outside = tmp.newFolder("outside-mount").also { it.resolve("keep").writeText("safe") }
        Files.createSymbolicLink(files.resolve("builtin_skills").toPath(), outside.toPath())
        val descriptor = builtinSkillMountDescriptor(files) { throw IllegalArgumentException("unsafe root") }
        assertEquals(files.resolve("builtin_skills"), descriptor)
        val manager = me.rerere.workspace.WorkspaceManager(tmp.newFolder("workspaces"), bindMounts = listOf(
            me.rerere.workspace.WorkspaceBindMount(tmp.newFolder("user-skills"), "/skills"),
            me.rerere.workspace.WorkspaceBindMount(descriptor, "/builtin_skills", required = true),
        ))
        assertTrue(manager.agentBindMounts(emptySet()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { manager.agentBindMounts(setOf("/skills")) }
        assertEquals("safe", outside.resolve("keep").readText())
    }
    @Test fun `startup prewarm is asynchronous and follows pending restore and Koin`() {
        val path = "app/src/main/java/me/rerere/rikkihub/RikkihubApp.kt"
        val source = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()
        val startup = source.substringAfter("override fun onCreate() {").substringBefore("private fun prewarmBuiltinSkills()")
        assertTrue(startup.indexOf("BackupManager.applyPendingRestore") < startup.indexOf("startKoin {"))
        assertTrue(startup.indexOf("startKoin {") < startup.indexOf("prewarmBuiltinSkills()"))
        assertFalse(startup.contains("BuiltinSkills.ensure"))
        val prewarm = source.substringAfter("private fun prewarmBuiltinSkills() {").substringBefore("private fun warmupEmojiData()")
        assertTrue(prewarm.contains("get<AppScope>().launch(Dispatchers.IO)"))
        assertTrue(prewarm.contains("BuiltinSkills.ensure(this@RikkihubApp)"))
        assertTrue(prewarm.contains("throw cancelled"))
        assertFalse(prewarm.contains("runBlocking"))
    }
    @Test fun `publication never changes user skills or settings`() {
        val files = root()
        val user = files.resolve("skills/creator").also { it.mkdirs(); it.resolve("SKILL.md").writeText("user") }
        val settings = files.resolve("settings.json").also { it.writeText("settings") }
        BuiltinSkillPublisher(files, assets()).ensure("1")
        BuiltinSkillPublisher(files, assets("two")).ensure("2")
        assertEquals("user", user.resolve("SKILL.md").readText())
        assertEquals("settings", settings.readText())
    }
}
