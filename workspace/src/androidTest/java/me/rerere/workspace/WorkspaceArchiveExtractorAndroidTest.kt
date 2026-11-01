package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The archive libraries (zip4j AES, commons-compress 7z AES/xz, junrar RAR5 crypto) and the
 * legacy-charset decoding run on ART's libcore and crypto providers, not only on the host JVM.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceArchiveExtractorAndroidTest {
    private lateinit var area: File
    private lateinit var staging: File
    private val extractor = WorkspaceArchiveExtractor(preferredLocale = { Locale.SIMPLIFIED_CHINESE })

    @Before fun setup() {
        val base = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "extract-${UUID.randomUUID()}")
        area = File(base, "files").apply { mkdirs() }
        staging = File(base, "tmp").apply { mkdirs() }
    }

    @After fun cleanup() { area.parentFile!!.deleteRecursively() }

    private fun extract(path: String, password: String? = null) =
        runBlocking { extractor.extract(area, staging, path, password?.toCharArray()) }

    private fun code(block: () -> Unit): String = try {
        block(); fail("expected an archive error"); ""
    } catch (e: WorkspaceArchiveException) {
        e.code
    }

    private fun fixture(name: String) {
        File(area, name).writeBytes(javaClass.classLoader!!.getResourceAsStream("archives/$name")!!.readBytes())
    }

    @Test fun gbkZipNamesAreDecoded() {
        ZipOutputStream(File(area, "cn.zip").outputStream(), Charset.forName("GBK")).use { out ->
            out.putNextEntry(ZipEntry("资料/说明.txt")); out.write("内容".toByteArray()); out.closeEntry()
        }
        val result = extract("cn.zip")
        assertEquals("资料", result.path)
        assertEquals("内容", File(area, "资料/说明.txt").readText())
    }

    @Test fun aesZipNeedsTheRightPassword() {
        val source = File(staging.parentFile, "secret.txt").apply { writeText("top secret") }
        ZipFile(File(area, "aes.zip"), "pa55".toCharArray()).use { zip ->
            zip.addFile(source, ZipParameters().apply {
                isEncryptFiles = true; encryptionMethod = EncryptionMethod.AES; aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            })
        }
        assertEquals(WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED, code { extract("aes.zip") })
        assertEquals(WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, code { extract("aes.zip", "no") })
        assertEquals("top secret", File(area, extract("aes.zip", "pa55").path).readText())
    }

    @Test fun sevenZipAndRar5WithPasswords() {
        fixture("bla.encrypted.7z")
        assertEquals(WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED, code { extract("bla.encrypted.7z") })
        assertTrue(extract("bla.encrypted.7z", "foo").files > 0)
        fixture("rar5-encrypted-junrar.rar")
        assertEquals(WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD, code { extract("rar5-encrypted-junrar.rar", "wrong") })
        assertTrue(extract("rar5-encrypted-junrar.rar", "junrar").files > 0)
        fixture("rar4-password-junrar.rar")
        assertTrue(extract("rar4-password-junrar.rar", "junrar").files > 0)
    }

    @Test fun tarXzKeepsSymlinksAndExecBits() {
        TarArchiveOutputStream(XZCompressorOutputStream(File(area, "app.tar.xz").outputStream()), StandardCharsets.UTF_8.name()).use { tar ->
            val bytes = "#!/bin/sh".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("app/run.sh").apply { size = bytes.size.toLong(); mode = 0x1ED })
            tar.write(bytes); tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry("app/link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "run.sh" })
            tar.closeArchiveEntry()
        }
        val result = extract("app.tar.xz")
        assertEquals("app", result.path)
        assertTrue(File(area, "app/run.sh").canExecute())
        assertTrue(Files.isSymbolicLink(File(area, "app/link").toPath()))
        assertEquals(emptyList<String>(), staging.list()!!.toList())
    }
}
