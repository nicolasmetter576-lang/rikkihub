package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.MicCaptureLoop
import me.rerere.asr.appendAmplitude
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "OpenAIRealtimeASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L

private val realtimeJson = Json { ignoreUnknownKeys = true }

class OpenAIRealtimeASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.OpenAIRealtime
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())
    private val partialTranscripts = ConcurrentHashMap<String, String>()

    private val micCapture = MicCaptureLoop(
        scope = scope,
        sampleRate = provider.sampleRate,
        onAmplitude = { amplitude ->
            _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
        },
        onFrame = { buffer, read ->
            val socket = webSocket ?: return@MicCaptureLoop
            if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                val event = buildJsonObject {
                    put("type", "input_audio_buffer.append")
                    put("audio", encoded)
                }
                socket.send(event.toString())
            } else {
                Log.w(TAG, "WebSocket queue full, dropping audio frame")
            }
        },
        onError = { e ->
            Log.e(TAG, "Audio recording failed", e)
            setError(e.message ?: "Audio recording failed")
        },
    )

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        completedTranscripts.clear()
        partialTranscripts.clear()
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        val request = Request.Builder()
            .url(provider.websocketEndpoint())
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (this@OpenAIRealtimeASRController.webSocket !== webSocket ||
                    state.value.status != ASRStatus.Connecting
                ) {
                    webSocket.cancel()
                    return
                }
                webSocket.send(provider.sessionUpdateEvent().toString())
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                micCapture.start()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@OpenAIRealtimeASRController.webSocket === webSocket) handleServerEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@OpenAIRealtimeASRController.webSocket !== webSocket) return
                Log.e(TAG, "Realtime ASR websocket failed", t)
                this@OpenAIRealtimeASRController.webSocket = null
                micCapture.stop()
                setError(t.message ?: "ASR websocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@OpenAIRealtimeASRController.webSocket !== webSocket) return
                this@OpenAIRealtimeASRController.webSocket = null
                micCapture.stop()
                _state.update {
                    if (it.status == ASRStatus.Error) it
                    else it.copy(status = ASRStatus.Idle, errorMessage = null)
                }
            }
        })
    }

    override fun pauseCapture() {
        micCapture.stop()
    }

    override fun stop() {
        micCapture.stop()
        val socket = webSocket
        if (socket != null) {
            _state.update {
                if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Stopping)
            }
            scope.launch {
                delay(500)
                socket.close(1000, "stop")
                if (webSocket === socket) {
                    webSocket = null
                    _state.update {
                        if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Idle)
                    }
                }
            }
        } else {
            _state.update {
                if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Idle)
            }
        }
    }

    override fun dispose() {
        micCapture.stop()
        val socket = webSocket
        webSocket = null
        socket?.cancel()
        scope.cancel()
    }

    private fun handleServerEvent(text: String) {
        val event = runCatching { realtimeJson.parseToJsonElement(text).jsonObject }.getOrElse {
            Log.w(TAG, "Invalid realtime event: $text", it)
            return
        }

        when (val type = event["type"]?.jsonPrimitive?.contentOrNull) {
            "input_audio_buffer.speech_started" -> {
                val id = event["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                _state.update { it.copy(voiceTurn = it.voiceTurn.started(id)) }
            }

            "input_audio_buffer.speech_stopped" -> {
                val id = event["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                _state.update { it.copy(voiceTurn = it.voiceTurn.stopped(id)) }
            }

            "conversation.item.input_audio_transcription.delta" -> {
                val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull ?: "default"
                val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    partialTranscripts[itemId] = (partialTranscripts[itemId] ?: "") + delta
                    publishTranscript()
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull ?: "default"
                val transcript = event["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                _state.update { it.copy(voiceTurn = it.voiceTurn.completed(itemId, transcript)) }
                partialTranscripts.remove(itemId)
                if (transcript.isNotEmpty()) {
                    completedTranscripts.add(transcript)
                }
                publishTranscript()
            }

            "conversation.item.input_audio_transcription.failed" -> {
                setError(
                    (event["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "ASR transcription failed"
                )
            }

            "error" -> {
                val error = event["error"] as? JsonObject
                setError(error?.get("message")?.jsonPrimitive?.contentOrNull ?: "ASR realtime error")
            }

            else -> {
                Log.v(TAG, "Ignored realtime event: $type")
            }
        }
    }

    private fun publishTranscript() {
        val transcript = (completedTranscripts + partialTranscripts.values)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch {
            onTranscriptChange?.invoke(transcript)
        }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }
}

private fun ASRProviderSetting.OpenAIRealtime.websocketEndpoint(): String {
    val endpoint = websocketUrl.trim()
    if (endpoint.contains("intent=transcription")) return endpoint
    if (endpoint.contains("model=")) return endpoint
    val separator = if (endpoint.contains("?")) "&" else "?"
    return "${endpoint.trimEnd('/')}${separator}intent=transcription"
}

private fun ASRProviderSetting.OpenAIRealtime.sessionUpdateEvent(): JsonObject {
    val transcription = buildJsonObject {
        put("model", model)
        if (language.isNotBlank()) put("language", language)
        if (prompt.isNotBlank()) put("prompt", prompt)
    }

    return buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("type", "transcription")
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "audio/pcm")
                        put("rate", sampleRate)
                    })
                    put("transcription", transcription)
                    put("noise_reduction", buildJsonObject {
                        put("type", "near_field")
                    })
                    put("turn_detection", buildJsonObject {
                        put("type", "server_vad")
                        put("threshold", vadThreshold)
                        put("prefix_padding_ms", prefixPaddingMs)
                        put("silence_duration_ms", silenceDurationMs)
                    })
                })
            })
        })
    }
}
