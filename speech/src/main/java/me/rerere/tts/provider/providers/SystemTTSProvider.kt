package me.rerere.tts.provider.providers

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.common.android.appTempFolder
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SystemTTSProvider"

class SystemTTSProvider : TTSProvider<TTSProviderSetting.SystemTTS> {
    // TextToSpeech 初始化开销大, provider 级别缓存一个实例复用;
    // 引擎内部排队 utterance, 这里再用 mutex 串行化防止并发 chunk 覆盖 listener 状态
    private var engine: TextToSpeech? = null
    private val engineLock = Mutex()

    private suspend fun getOrCreateEngine(context: Context): TextToSpeech {
        engine?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine = created
                    if (continuation.isActive) continuation.resume(created!!)
                } else {
                    if (continuation.isActive) continuation.resumeWithException(
                        Exception("Failed to initialize TextToSpeech engine")
                    )
                }
            }
            continuation.invokeOnCancellation {
                if (engine == null) {
                    created?.shutdown()
                    created = null
                }
            }
        }
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.SystemTTS,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val audioData = engineLock.withLock {
            val ttsInstance = getOrCreateEngine(context)

            suspendCancellableCoroutine<ByteArray> { continuation ->
                // Set language
                val locale = Locale.getDefault()
                val langResult = ttsInstance.setLanguage(locale)

                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "generateSpeech: Language $locale not supported")
                }

                // Set speech parameters
                ttsInstance.setSpeechRate(providerSetting.speechRate)
                ttsInstance.setPitch(providerSetting.pitch)

                // Create temporary file for audio output using temp directory like RikkihubApp
                val tempDir = context.appTempFolder
                val audioFile = File(tempDir, "tts_${System.currentTimeMillis()}.wav")

                val utteranceId = UUID.randomUUID().toString()

                ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(TAG, "onStart: TTS engine started!")
                    }

                    override fun onDone(utteranceId: String?) {
                        try {
                            if (audioFile.exists()) {
                                val audioData = audioFile.readBytes()
                                audioFile.delete()

                                if (continuation.isActive) continuation.resume(audioData)
                            } else {
                                if (continuation.isActive) continuation.resumeWithException(
                                    Exception("Failed to generate audio file")
                                )
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "onError: TTS synthesis failed!")
                        audioFile.delete()
                        if (continuation.isActive) continuation.resumeWithException(
                            Exception("TTS synthesis failed")
                        )
                    }
                })

                val result = ttsInstance.synthesizeToFile(
                    request.text,
                    null,
                    audioFile,
                    utteranceId
                )

                if (result != TextToSpeech.SUCCESS) {
                    audioFile.delete()
                    if (continuation.isActive) continuation.resumeWithException(
                        Exception("Failed to start TTS synthesis")
                    )
                }

                continuation.invokeOnCancellation {
                    // 取消这次合成, 引擎实例保留复用
                    ttsInstance.stop()
                }
            }
        }

        emit(
            AudioChunk(
                data = audioData,
                format = me.rerere.tts.model.AudioFormat.WAV,
                isLast = true,
                metadata = mapOf(
                    "provider" to "system",
                    "speechRate" to providerSetting.speechRate.toString(),
                    "pitch" to providerSetting.pitch.toString()
                )
            )
        )
    }
}
