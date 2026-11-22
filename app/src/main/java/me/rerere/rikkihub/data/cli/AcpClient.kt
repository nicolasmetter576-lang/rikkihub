package me.rerere.rikkihub.data.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkihub.utils.JsonInstant
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A JSON-RPC error returned by the agent, or one we return to it from a request handler. */
class AcpException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message) {
    companion object {
        const val AUTH_REQUIRED = -32000
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/** The agent's stdout ended (process exited, crashed, or sent an oversized frame). */
class AcpClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * JSON-RPC 2.0 over newline-delimited stdio, the ACP transport.
 *
 * - Our requests use numeric ids; responses complete them, and all of them fail with
 *   [AcpClosedException] once stdout ends.
 * - Agent→client requests run [onRequest] in their own coroutine (a permission prompt may wait
 *   for the user); its result or [AcpException] becomes the response.
 * - Notifications run [onNotification] on the reader, in arrival order; keep it non-blocking.
 * - stderr is drained continuously; the last [STDERR_TAIL_CHARS] characters are kept for diagnostics.
 */
class AcpClient(
    private val input: InputStream,
    output: OutputStream,
    private val stderr: InputStream,
    private val scope: CoroutineScope,
    private val onRequest: suspend (method: String, params: JsonObject) -> JsonElement,
    private val onNotification: (method: String, params: JsonObject) -> Unit,
) {
    private val output = output.buffered()
    private val writeMutex = Mutex()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val failure = AtomicReference<AcpClosedException?>(null)
    private val stderrTail = StringBuilder()

    /** Completes when stdout has ended; the value says why. */
    val closed = CompletableDeferred<AcpClosedException>()

    private lateinit var readers: List<Job>

    fun start() {
        readers = listOf(
            scope.launch(Dispatchers.IO) { readStdout() },
            scope.launch(Dispatchers.IO) { readStderr() },
        )
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement =
        call(method, params).await()

    /** Sends a request; the response can still be awaited after the caller was cancelled (prompt turns). */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): Deferred<JsonElement> {
        failure.get()?.let { throw it }
        val id = nextId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        response.invokeOnCompletion { pending.remove(id) }
        // Closing between the check above and registration: fail it here, the reader has already swept.
        failure.get()?.let { response.completeExceptionally(it) }
        try {
            write(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
        } catch (error: Throwable) {
            response.completeExceptionally(error)
            throw error
        }
        return response
    }

    suspend fun notify(method: String, params: JsonObject) {
        failure.get()?.let { throw it }
        write(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        })
    }

    fun stderrTail(): String = synchronized(stderrTail) { stderrTail.toString() }

    /** Waits for both readers to reach EOF (after the process has been told to close). */
    suspend fun awaitReaders() {
        readers.forEach { it.join() }
    }

    private suspend fun write(frame: JsonObject) {
        val bytes = (JsonInstant.encodeToString(JsonObject.serializer(), frame) + "\n").toByteArray()
        writeMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    output.write(bytes)
                    output.flush()
                }
            } catch (error: IOException) {
                throw failure.get() ?: AcpClosedException("Agent stdin closed", error)
            }
        }
    }

    private suspend fun readStdout() {
        var reason: AcpClosedException
        try {
            while (true) {
                val line = runInterruptible { readFrame(input) } ?: break
                if (line.isBlank()) continue
                val frame = runCatching { JsonInstant.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                dispatch(frame)
            }
            reason = AcpClosedException("Agent exited")
        } catch (error: AcpFrameTooLargeException) {
            reason = AcpClosedException("Agent sent a frame larger than ${MAX_FRAME_BYTES / (1024 * 1024)} MiB", error)
        } catch (error: IOException) {
            reason = AcpClosedException("Agent stdout failed", error)
        }
        failure.compareAndSet(null, reason)
        val final = failure.get()!!
        pending.values.forEach { it.completeExceptionally(final) }
        closed.complete(final)
    }

    private suspend fun dispatch(frame: JsonObject) {
        val method = (frame["method"] as? JsonPrimitive)?.contentOrNull
        val id = frame["id"]?.takeUnless { it is JsonNull }
        val params = frame["params"] as? JsonObject ?: JsonObject(emptyMap())
        when {
            method != null && id != null -> scope.launch { answer(id, method, params) }
            method != null -> runCatching { onNotification(method, params) }
            id != null -> {
                val waiter = (id as? JsonPrimitive)?.longOrNull?.let { pending[it] } ?: return
                val error = frame["error"] as? JsonObject
                if (error != null) {
                    waiter.completeExceptionally(
                        AcpException(
                            code = error["code"]?.jsonPrimitive?.intOrNull ?: AcpException.INTERNAL_ERROR,
                            message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            data = error["data"],
                        )
                    )
                } else {
                    waiter.complete(frame["result"] ?: JsonNull)
                }
            }
        }
    }

    private suspend fun answer(id: JsonElement, method: String, params: JsonObject) {
        val reply = try {
            val result = onRequest(method, params)
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
        } catch (error: AcpException) {
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject {
                    put("code", error.code)
                    put("message", error.message.orEmpty())
                })
            }
        }
        runCatching { write(reply) }
    }

    private fun readStderr() {
        val buffer = ByteArray(4096)
        runCatching {
            while (true) {
                val n = stderr.read(buffer)
                if (n < 0) break
                val text = String(buffer, 0, n)
                synchronized(stderrTail) {
                    stderrTail.append(text)
                    if (stderrTail.length > STDERR_TAIL_CHARS) {
                        stderrTail.delete(0, stderrTail.length - STDERR_TAIL_CHARS)
                    }
                }
            }
        }
    }

    private class AcpFrameTooLargeException : IOException()

    companion object {
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024
        const val STDERR_TAIL_CHARS = 64 * 1024

        /** One `\n`-terminated UTF-8 frame without the terminator; null at EOF with nothing pending. */
        private fun readFrame(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) return line.toString(Charsets.UTF_8.name())
                if (line.size() >= MAX_FRAME_BYTES) throw AcpFrameTooLargeException()
                line.write(b)
            }
        }
    }
}
