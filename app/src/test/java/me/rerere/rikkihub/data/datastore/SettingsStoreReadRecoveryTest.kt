package me.rerere.rikkihub.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 回归测试：设置读取失败恢复行为。
 *
 * 生产路径 `SettingsStore.settingsFlowRaw` 现在使用 [withSettingsReadRetry]：
 * 仅对临时 IOException 做有界重试，耗尽后向上传播，绝不 emit(emptyPreferences())
 * 冒充默认配置；CorruptionException 由 DataStore corruptionHandler 处理，重建前
 * 经 [backupCorruptedSettingsFile] 保留损坏文件备份。
 *
 * delay/report 注入 no-op，测试完全离线运行（不调用 android.util.Log）。
 */
class SettingsStoreReadRecoveryTest {

    private fun Flow<Preferences>.testRetry(
        delays: MutableList<Long>,
        maxRetries: Int = SETTINGS_READ_MAX_RETRIES,
    ): Flow<Preferences> = withSettingsReadRetry(
        maxRetries = maxRetries,
        delayMillis = { delays += it },
        report = { _, _ -> },
    )

    @Test
    fun `transient IO failures are retried with exponential backoff and then recovered`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val source = flow<Preferences> {
            attempts++
            if (attempts <= 2) throw IOException("transient read failure $attempts")
            emit(emptyPreferences())
        }

        val values = mutableListOf<Preferences>()
        source.testRetry(delays).collect { values += it }

        // 失败 2 次 -> 重试 2 次 -> 第 3 次读取成功
        assertEquals(3, attempts)
        assertEquals(1, values.size)
        // 退避为 base shl attempt：100ms、200ms
        assertEquals(
            listOf(
                SETTINGS_READ_RETRY_BASE_DELAY_MS,
                SETTINGS_READ_RETRY_BASE_DELAY_MS shl 1,
            ),
            delays,
        )
    }

    @Test
    fun `persistent IO failure propagates after bounded retries instead of emitting defaults`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val failure = IOException("disk gone")
        val source = flow<Preferences> {
            attempts++
            throw failure
        }

        val caught = try {
            source.testRetry(delays).collect()
            fail("expected IOException to propagate")
        } catch (e: IOException) {
            e
        }

        // 首次 + SETTINGS_READ_MAX_RETRIES 次重试后仍失败，原异常向上抛出
        assertSame(failure, caught)
        assertEquals(SETTINGS_READ_MAX_RETRIES + 1, attempts)
        assertEquals(SETTINGS_READ_MAX_RETRIES, delays.size)
        assertEquals(
            listOf(100L, 200L, 400L),
            delays,
        )
    }

    @Test
    fun `non IO exception is not retried and propagates immediately`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val failure = IllegalStateException("decoding blew up")
        val source = flow<Preferences> {
            attempts++
            throw failure
        }

        val caught = try {
            source.testRetry(delays).collect()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            e
        }

        assertSame(failure, caught)
        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `CorruptionException is not retried so corruption handler can take over`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        // CorruptionException 本身是 IOException 子类，必须被排除在重试之外
        val failure = CorruptionException("bad proto", null)
        val source = flow<Preferences> {
            attempts++
            throw failure
        }

        val caught = try {
            source.testRetry(delays).collect()
            fail("expected CorruptionException")
        } catch (e: CorruptionException) {
            e
        }

        assertSame(failure, caught)
        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `cancellation thrown by upstream is not retried`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val source = flow<Preferences> {
            attempts++
            throw CancellationException("collector gone")
        }

        try {
            source.testRetry(delays).collect()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }

        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `cancellation during backoff delay aborts retry promptly`() = runBlocking {
        var attempts = 0
        // 使用默认 delayMillis（真实 delay），取消应在退避等待中立即生效
        val source = flow<Preferences> {
            attempts++
            throw IOException("still failing")
        }

        val job = launch(Job()) {
            source.withSettingsReadRetry(report = { _, _ -> }).collect()
        }
        // 等收集协程完成第一次失败并进入退避等待（attempts 在抛错前自增）
        while (attempts == 0) yield()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        // 取消发生在第一次退避内，最多只有一次读取尝试
        assertEquals(1, attempts)
    }

    @Test
    fun `corrupted file backup preserves bytes before datastore rebuild`() {
        val dir = Files.createTempDirectory("settings-corrupt").toFile()
        try {
            val corrupted = File(dir, "settings.preferences_pb")
            val bytes = byteArrayOf(0x00, 0x11, 0x7F, -1, 42)
            corrupted.writeBytes(bytes)

            val backup = backupCorruptedSettingsFile(corrupted) { 1234L }

            assertNotNull(backup)
            assertEquals("settings.preferences_pb.corrupt-1234", backup!!.name)
            assertEquals(dir, backup.parentFile)
            assertTrue(backup.exists())
            assertTrue(backup.readBytes().contentEquals(bytes))
            // 原文件必须保持不动，由 DataStore 后续负责替换
            assertTrue(corrupted.exists())
            assertTrue(corrupted.readBytes().contentEquals(bytes))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `backup failure returns null instead of throwing so rebuild still proceeds`() {
        val dir = Files.createTempDirectory("settings-corrupt-fail").toFile()
        try {
            // 源文件不存在：copyTo 抛 NoSuchFileException，helper 必须吞掉并返回 null
            val missing = File(dir, "settings.preferences_pb")
            assertNull(backupCorruptedSettingsFile(missing) { 1L })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `backup returns null when parent directory is unknown`() {
        // 无目录分隔符的相对路径 parentFile == null，不允许写到未知位置
        assertNull(backupCorruptedSettingsFile(File("name-only")) { 1L })
    }
}
