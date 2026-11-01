package me.rerere.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceArchiveExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val extractor = WorkspaceArchiveExtractor(preferredLocale = { Locale.SIMPLIFIED_CHINESE })

    private lateinit var area: File
    private lateinit var staging: File

    private fun setUp() {
        area = tmp.newFolder("files")
        staging = tmp.newFolder("tmp")
    }

    private fun extract(path: String, password: String? = null, encoding: String? = null, onProgress: (WorkspaceExtractProgress) -> Unit = {}) =
        runBlocking { extractor.extract(area, staging, path, password?.toCharArray(), encoding, onProgress) }

    private fun archiveCode(block: () -> Unit): String =
        assertThrows(WorkspaceArchiveException::class.java) { block() }.code

    private fun fixture(name: String, target: String = name): String {
        val bytes = javaClass.classLoader!!.getResourceAsStream("archives/$name")!!.readBytes()
        File(area, target).writeBytes(bytes)
        return target
    }

    private fun zip(name: String, charset: Charset = StandardCharsets.UTF_8, entries: List<Pair<String, String?>>): String {
        ZipOutputStream(File(area, name).outputStream(), charset).use { out ->
            for ((path, content) in entries) {
                out.putNextEntry(ZipEntry(path))
                content?.let { out.write(it.toByteArray()) }
                out.closeEntry()
            }
        }
        return name
    }

    private fun tree(dir: File): List<String> = dir.walkTopDown()
        .filter { it != dir }
        .map { it.relativeTo(dir).path.replace(File.separatorChar, '/') + if (it.isDirectory) "/" else "" }
        .sorted().toList()

    private fun assertStagingEmpty() = assertEquals(emptyList<String>(), staging.list()!!.toList())

    // ---------- zip and file names ----------

    @Test
    fun `zip with several top-level entries lands in a folder named after the archive`() {
        setUp()
        zip("bundle.zip", entries = listOf("a.txt" to "A", "dir/" to null, "dir/b.txt" to "B"))
        val result = extract("bundle.zip")
        assertEquals("bundle", result.path)
        assertTrue(result.isDirectory)
        assertEquals(WorkspaceArchiveFormat.ZIP, result.format)
        assertEquals(listOf("a.txt", "dir/", "dir/b.txt"), tree(File(area, "bundle")))
        assertEquals("B", File(area, "bundle/dir/b.txt").readText())
        assertEquals(2, result.files)
        assertStagingEmpty()
    }

    @Test
    fun `a single top-level folder is placed directly and renamed on clash`() {
        setUp()
        File(area, "proj").mkdir()
        zip("proj.zip", entries = listOf("proj/a.txt" to "A", "proj/sub/c.txt" to "C"))
        val result = extract("proj.zip")
        assertEquals("proj (1)", result.path)
        assertEquals(listOf("a.txt", "sub/", "sub/c.txt"), tree(File(area, "proj (1)")))
        assertTrue(File(area, "proj").list()!!.isEmpty())
    }

    @Test
    fun `extraction lands next to the archive inside sub folders`() {
        setUp()
        File(area, "in/deep").mkdirs()
        zip("in/deep/x.zip", entries = listOf("only.txt" to "1"))
        val result = extract("in/deep/x.zip")
        assertEquals("in/deep/only.txt", result.path)
        assertFalse(result.isDirectory)
    }

    @Test
    fun `GBK names without the UTF-8 flag are decoded instead of turning into mojibake`() {
        setUp()
        zip("cn.zip", Charset.forName("GBK"), listOf("资料/说明.txt" to "内容", "资料/图片/" to null))
        val result = extract("cn.zip")
        assertEquals("资料", result.path)
        assertEquals("GB18030", result.nameEncoding)
        assertEquals(listOf("图片/", "说明.txt"), tree(File(area, "资料")))
    }

    @Test
    fun `UTF-8 names without the flag are still read as UTF-8`() {
        setUp()
        // Charset other than UTF-8 makes ZipOutputStream skip the flag; the bytes are UTF-8 anyway.
        val name = String("日本語.txt".toByteArray(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)
        zip("u.zip", StandardCharsets.ISO_8859_1, listOf(name to "x"))
        val result = extract("u.zip")
        assertEquals("日本語.txt", result.path)
        assertEquals("UTF-8", result.nameEncoding)
    }

    @Test
    fun `explicit name encoding overrides the guess`() {
        setUp()
        zip("jp.zip", Charset.forName("Shift_JIS"), listOf("テスト.txt" to "x"))
        val result = extract("jp.zip", encoding = "Shift_JIS")
        assertEquals("テスト.txt", result.path)
        assertEquals("Shift_JIS", result.nameEncoding)
        assertEquals(WorkspaceArchiveException.ARCHIVE_ENCODING_UNSUPPORTED, archiveCode { extract("jp.zip", encoding = "no-such-charset") })
    }

    @Test
    fun `Info-ZIP unicode path field wins over the legacy name`() {
        setUp()
        val legacy = "????.txt".toByteArray(StandardCharsets.ISO_8859_1)
        val utf8 = "统一码.txt".toByteArray(StandardCharsets.UTF_8)
        val crc = CRC32().apply { update(legacy) }.value
        val extra = java.io.ByteArrayOutputStream().apply {
            val size = 1 + 4 + utf8.size
            write(byteArrayOf(0x75, 0x70, (size and 0xFF).toByte(), (size shr 8).toByte(), 1))
            for (i in 0 until 4) write(((crc shr (8 * i)) and 0xFF).toInt())
            write(utf8)
        }.toByteArray()
        ZipOutputStream(File(area, "up.zip").outputStream(), StandardCharsets.ISO_8859_1).use { out ->
            out.putNextEntry(ZipEntry(String(legacy, StandardCharsets.ISO_8859_1)).apply { this.extra = extra })
            out.write("x".toByteArray())
            out.closeEntry()
        }
        assertEquals("统一码.txt", extract("up.zip").path)
    }

    @Test
    fun `entries escaping the archive are skipped and absolute paths stay inside`() {
        setUp()
        zip("evil.zip", entries = listOf("../escape.txt" to "x", "/abs.txt" to "y", "ok/../../up.txt" to "z", "fine.txt" to "w"))
        val result = extract("evil.zip")
        assertEquals("evil", result.path)
        assertEquals(listOf("abs.txt", "fine.txt"), tree(File(area, "evil")))
        assertEquals(2, result.skipped)
        assertFalse(File(area, "escape.txt").exists())
        assertFalse(File(tmp.root, "escape.txt").exists())
    }

    // ---------- passwords ----------

    private fun encryptedZip(name: String, method: EncryptionMethod) {
        val source = tmp.newFile("secret-$name.txt").apply { writeText("top secret") }
        ZipFile(File(area, name), "pa55".toCharArray()).use { zip ->
            zip.addFile(source, ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = method
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = "secret.txt"
            })
        }
    }

    @Test
    fun `AES zip asks for a password, rejects a wrong one and opens with the right one`() {
        setUp()
        encryptedZip("aes.zip", EncryptionMethod.AES)
        assertEquals(WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED, archiveCode { extract("aes.zip") })
        assertEquals(WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, archiveCode { extract("aes.zip", "nope") })
        assertEquals(listOf("aes.zip"), area.list()!!.toList())
        assertStagingEmpty()
        assertEquals("top secret", File(area, extract("aes.zip", "pa55").path).readText())
    }

    @Test
    fun `ZipCrypto wrong password is reported as such`() {
        setUp()
        encryptedZip("zc.zip", EncryptionMethod.ZIP_STANDARD)
        assertEquals(WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, archiveCode { extract("zc.zip", "wrong-one") })
        assertEquals("top secret", File(area, extract("zc.zip", "pa55").path).readText())
    }

    @Test
    fun `7z plain and AES encrypted`() {
        setUp()
        val plain = extract(fixture("bla.7z"))
        assertEquals(WorkspaceArchiveFormat.SEVEN_ZIP, plain.format)
        assertTrue(plain.files > 0)
        fixture("bla.encrypted.7z")
        assertEquals(WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED, archiveCode { extract("bla.encrypted.7z") })
        assertEquals(WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, archiveCode { extract("bla.encrypted.7z", "bar") })
        val opened = extract("bla.encrypted.7z", "foo")
        assertEquals(plain.files, opened.files)
        assertStagingEmpty()
    }

    @Test
    fun `rar5 and rar4 archives including passwords and encrypted headers`() {
        setUp()
        val plain = extract(fixture("rar5.rar"))
        assertEquals(WorkspaceArchiveFormat.RAR, plain.format)
        assertTrue(plain.files > 0)
        for (name in listOf("rar4-password-junrar.rar", "rar5-password-junrar.rar", "rar5-encrypted-junrar.rar")) {
            fixture(name)
            assertEquals(name, WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED, archiveCode { extract(name) })
            assertEquals(name, WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, archiveCode { extract(name, "wrong") })
            val result = extract(name, "junrar")
            assertTrue(name, result.files > 0)
            File(area, result.path).walkTopDown().filter { it.isFile }.forEach { assertTrue(it.length() > 0) }
        }
        assertStagingEmpty()
    }

    @Test
    fun `rar unicode names`() {
        setUp()
        val result = extract(fixture("unicode.rar"))
        val names = File(area, result.path).let { if (it.isDirectory) tree(it) else listOf(it.name) }
        assertTrue(names.toString(), names.any { name -> name.any { it.code > 0x7F } })
    }

    // ---------- tar family ----------

    private fun tar(out: OutputStream, block: TarArchiveOutputStream.() -> Unit) {
        TarArchiveOutputStream(out, "ISO-8859-1").use { it.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX); it.block() }
    }

    private fun TarArchiveOutputStream.put(name: String, content: String, mode: Int = 0x1A4) {
        val bytes = content.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong(); this.mode = mode })
        write(bytes)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.link(name: String, target: String, type: Byte) {
        putArchiveEntry(TarArchiveEntry(name, type).apply { linkName = target })
        closeArchiveEntry()
    }

    @Test
    fun `tar gz keeps exec bits and inside links, drops links that leave the archive`() {
        setUp()
        tar(GZIPOutputStream(File(area, "app.tar.gz").outputStream())) {
            put("app/run.sh", "#!/bin/sh", mode = 0x1ED)
            put("app/data.txt", "data")
            link("app/latest", "data.txt", TarArchiveEntry.LF_SYMLINK.toByte())
            link("app/outside", "../../../etc/passwd", TarArchiveEntry.LF_SYMLINK.toByte())
            link("app/abs", "/etc/passwd", TarArchiveEntry.LF_SYMLINK.toByte())
            link("app/copy.txt", "app/data.txt", TarArchiveEntry.LF_LINK.toByte())
        }
        val result = extract("app.tar.gz")
        assertEquals(WorkspaceArchiveFormat.TAR_GZ, result.format)
        assertEquals("app", result.path)
        val app = File(area, "app")
        assertTrue(File(app, "run.sh").canExecute())
        assertTrue(Files.isSymbolicLink(File(app, "latest").toPath()))
        assertEquals("data", File(app, "latest").readText())
        assertEquals("data", File(app, "copy.txt").readText())
        assertFalse(File(app, "outside").exists() || Files.isSymbolicLink(File(app, "outside").toPath()))
        assertFalse(Files.isSymbolicLink(File(app, "abs").toPath()))
        assertEquals(2, result.skipped)
    }

    @Test
    fun `tar xz and bz2 with legacy GBK names`() {
        setUp()
        val gbkName = String("报告/第一章.md".toByteArray(Charset.forName("GBK")), StandardCharsets.ISO_8859_1)
        tar(XZCompressorOutputStream(File(area, "r.tar.xz").outputStream())) { put(gbkName, "# 1") }
        tar(BZip2CompressorOutputStream(File(area, "r.tbz2").outputStream())) { put("x/y.txt", "y") }
        val xz = extract("r.tar.xz")
        assertEquals(WorkspaceArchiveFormat.TAR_XZ, xz.format)
        assertEquals("报告", xz.path)
        assertEquals("# 1", File(area, "报告/第一章.md").readText())
        assertEquals("GB18030", xz.nameEncoding)
        val bz = extract("r.tbz2")
        assertEquals(WorkspaceArchiveFormat.TAR_BZ2, bz.format)
        assertEquals("x", bz.path)
        assertNull(bz.nameEncoding)
    }

    @Test
    fun `plain tar and single compressed files`() {
        setUp()
        tar(File(area, "p.tar").outputStream()) { put("one.txt", "1") }
        assertEquals("one.txt", extract("p.tar").path)
        GZIPOutputStream(File(area, "data.json.gz").outputStream()).use { it.write("{}".toByteArray()) }
        val gz = extract("data.json.gz")
        assertEquals(WorkspaceArchiveFormat.GZ, gz.format)
        assertEquals("data.json", gz.path)
        assertEquals("{}", File(area, "data.json").readText())
    }

    @Test
    fun `unknown content is unsupported regardless of extension`() {
        setUp()
        File(area, "fake.zip").writeText("just text")
        assertEquals(WorkspaceArchiveException.ARCHIVE_UNSUPPORTED, archiveCode { extract("fake.zip") })
        File(area, "z.tar.zst").writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0, 0))
        assertEquals(WorkspaceArchiveException.ARCHIVE_UNSUPPORTED, archiveCode { extract("z.tar.zst") })
        val missing = assertThrows(WorkspaceFileOperationException::class.java) { extract("nope.zip") }
        assertEquals(WorkspaceFileOperationException.FILE_NOT_FOUND, missing.code)
    }

    @Test
    fun `cancel mid-way leaves nothing behind`() {
        setUp()
        zip("many.zip", entries = (1..50).map { "f$it.txt" to "x".repeat(1000) })
        val job = Job()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withContext(job) {
                    extractor.extract(area, staging, "many.zip", null, null) { if (it.entries == 3) job.cancel() }
                }
            }
        }
        assertEquals(listOf("many.zip"), area.list()!!.toList())
        assertStagingEmpty()
    }

    @Test
    fun `progress reports entries`() {
        setUp()
        zip("p.zip", entries = listOf("a" to "1", "b" to "2"))
        val seen = mutableListOf<Int>()
        extract("p.zip") { seen += it.entries }
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `names and hints`() {
        assertEquals("photos", WorkspaceArchiveExtractor.containerName("photos.tar.gz"))
        assertEquals("a", WorkspaceArchiveExtractor.containerName("a.part1.rar"))
        assertEquals("data", WorkspaceArchiveExtractor.containerName("data.ZIP"))
        assertEquals("data.json", WorkspaceArchiveExtractor.singleFileName("data.json.gz"))
        assertTrue(WorkspaceArchiveExtractor.isArchiveName("x.TGZ"))
        assertTrue(WorkspaceArchiveExtractor.isArchiveName("x.7z"))
        assertFalse(WorkspaceArchiveExtractor.isArchiveName("x.txt"))
        assertFalse(WorkspaceArchiveExtractor.isArchiveName(".zip"))
    }
}
