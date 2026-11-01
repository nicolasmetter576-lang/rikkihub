package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

private const val TAG = "DashScopeASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
private const val SESSION_FINISH_TIMEOUT_MS = 10_000L

private val dashScopeJson = Json { ignoreUnknownKeys = true }

class DashScopeASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.DashScope
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var finishTimeoutJob: Job? = null
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
                    put("event_id", "evt_${System.currentTimeMillis()}")
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
                if (this@DashScopeASRController.webSocket !== webSocket ||
                    state.value.status != ASRStatus.Connecting
                ) {
                    webSocket.close(1000, "cancelled")
                    return
                }
                webSocket.send(provider.sessionUpdateEvent().toString())
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                micCapture.start()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@DashScopeASRController.webSocket === webSocket) {
                    handleServerEvent(webSocket, text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val handshakeResult = response?.let { " (HTTP ${it.code} ${it.message})" }.orEmpty()
                Log.e(TAG, "DashScope ASR websocket failed$handshakeResult", t)
                if (this@DashScopeASRController.webSocket !== webSocket) return

                finishTimeoutJob?.cancel()
                finishTimeoutJob = null
                this@DashScopeASRController.webSocket = null
                micCapture.stop()
                setError(t.message ?: "ASR websocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "DashScope ASR websocket closed: code=$code, reason=$reason")
                if (this@DashScopeASRController.webSocket !== webSocket) return

                finishTimeoutJob?.cancel()
                finishTimeoutJob = null
                this@DashScopeASRController.webSocket = null
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
        val wasListening = state.value.status == ASRStatus.Listening
        val socket = webSocket
        val wasCapturing = micCapture.isActive
        micCapture.stop()
        if (socket == null && !wasCapturing) {
            _state.update {
                if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Idle)
            }
            return
        }

        _state.update {
            if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Stopping)
        }

        finishTimeoutJob?.cancel()
        finishTimeoutJob = scope.launch {
            // Do not enqueue session.finish until the capture loop can no longer send audio frames.
            micCapture.join()

            if (socket == null) {
                _state.update {
                    if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Idle)
                }
                return@launch
            }
            if (webSocket !== socket) return@launch

            if (!wasListening) {
                socket.cancel()
                webSocket = null
                _state.update {
                    if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Idle)
                }
                return@launch
            }

            val finishSent = socket.send(sessionFinishEvent().toString())
            if (!finishSent) {
                Log.w(TAG, "Failed to finish DashScope ASR session; closing websocket")
                socket.cancel()
                webSocket = null
                _state.update {
                    if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Idle)
                }
                return@launch
            }

            delay(SESSION_FINISH_TIMEOUT_MS)
            if (webSocket === socket) {
                Log.w(TAG, "Timed out waiting for session.finished; closing websocket")
                socket.close(1000, "session finish timeout")
                webSocket = null
                _state.update {
                    if (it.status == ASRStatus.Error) it else it.copy(status = ASRStatus.Idle)
                }
            }
        }
    }

    override fun dispose() {
        micCapture.stop()
        finishTimeoutJob?.cancel()
        finishTimeoutJob = null
        webSocket?.cancel()
        webSocket = null
        scope.cancel()
    }

    private fun handleServerEvent(socket: WebSocket, text: String) {
        val event = runCatching { dashScopeJson.parseToJsonElement(text).jsonObject }.getOrElse {
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

            "conversation.item.input_audio_transcription.text" -> {
                val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull ?: "default"
                val preview =
                    event["text"]?.jsonPrimitive?.contentOrNull.orEmpty() +
                            event["stash"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (preview.isNotEmpty()) {
                    partialTranscripts[itemId] = preview
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
                val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull ?: "default"
                val error = event["error"] as? JsonObject
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "ASR transcription failed"
                Log.e(TAG, "DashScope ASR transcription failed: $message")
                partialTranscripts.remove(itemId)
                publishTranscript()
                _state.update { it.copy(errorMessage = message) }
            }

            "error" -> {
                val error = event["error"] as? JsonObject
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "ASR realtime error"
                Log.e(TAG, "DashScope ASR server error: $message")
                setError(message)
            }

            "session.finished" -> {
                finishTimeoutJob?.cancel()
                finishTimeoutJob = null
                socket.close(1000, "session finished")
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

private fun sessionFinishEvent(): JsonObject {
    return buildJsonObject {
        put("event_id", "evt_session_finish_${System.currentTimeMillis()}")
        put("type", "session.finish")
    }
}

private fun ASRProviderSetting.DashScope.websocketEndpoint(): String {
    val endpoint = websocketUrl
        .trim()
        .trimEnd('/')
        .replace("/api-ws/v1/inference", "/api-ws/v1/realtime")
    val separator = if (endpoint.contains("?")) "&" else "?"
    return if (endpoint.contains("model=")) endpoint
    else "${endpoint}${separator}model=${model}"
}

private fun ASRProviderSetting.DashScope.sessionUpdateEvent(): JsonObject {
    val transcription = buildJsonObject {
        if (language.isNotBlank()) put("language", language)
    }

    val session = buildJsonObject {
        put("input_audio_format", "pcm")
        put("sample_rate", sampleRate)
        put("input_audio_transcription", transcription)
        put("turn_detection", buildJsonObject {
            put("type", "server_vad")
            put("threshold", vadThreshold)
            put("silence_duration_ms", silenceDurationMs)
        })
    }

    return buildJsonObject {
        put("event_id", "evt_session_update")
        put("type", "session.update")
        put("session", session)
    }
}
