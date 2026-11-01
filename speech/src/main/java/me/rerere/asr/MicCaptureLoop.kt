package me.rerere.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AudioRecord 采集循环的共享实现：创建 recorder、按帧读取 PCM16、
 * 计算振幅并把数据帧回调给各 ASR controller 使用。
 *
 * - [start] 在 [Dispatchers.IO] 上跑阻塞 read 循环；
 * - [stop] 取消协程并打断阻塞中的 read()，recorder 由协程 finally 统一释放；
 * - 采集失败只在协程仍 active 时回调 [onError]，取消路径不报错误状态。
 */
internal class MicCaptureLoop(
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val minReadSize: Int = sampleRate / 10 * 2,
    private val onAmplitude: (Float) -> Unit,
    private val onFrame: (buffer: ByteArray, read: Int) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    val isActive: Boolean
        get() = job?.isActive == true

    @SuppressLint("MissingPermission")
    fun start() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(minReadSize).coerceAtLeast(4096)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            audioRecord = recorder
            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        onAmplitude(calculateRmsAmplitude(buffer, read))
                        onFrame(buffer, read)
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                if (isActive) onError(e)
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                if (audioRecord === recorder) audioRecord = null
            }
        }
    }

    /** 打断阻塞中的 read() 并取消采集协程；recorder 由协程 finally 释放。 */
    fun stop() {
        job?.cancel()
        runCatching { audioRecord?.stop() }
    }

    /** 等待采集协程结束（stop() 之后的收尾同步用）。 */
    suspend fun join() {
        job?.join()
    }
}
