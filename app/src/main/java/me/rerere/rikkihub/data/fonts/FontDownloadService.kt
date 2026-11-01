package me.rerere.rikkihub.data.fonts

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "FontDownloadService"

/** Sub-directory of `filesDir/fonts`; flat backups of `fonts/` skip it, so 25 MB fonts stay out of backups. */
const val DOWNLOADED_FONTS_DIR = "downloaded"

/** One font's download as both the App and the Web see it; a finished download disappears (the font is installed). */
@Serializable
data class FontDownloadState(
    val id: String,
    /** `downloading` or `failed`. */
    val status: String,
    val bytesRead: Long = 0,
    val totalBytes: Long,
    val error: String? = null,
) {
    val downloading: Boolean get() = status == STATUS_DOWNLOADING

    companion object {
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_FAILED = "failed"
    }
}

class FontDownloadAlreadyRunningException : IllegalStateException("Font download already running")

/** An open HTTP body; [contentLength] is null when the server did not send one. */
class FontDownloadStream(val body: InputStream, val contentLength: Long?, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() {
        try { body.close() } finally { onClose() }
    }
}

/** Opens [url] (blocking) or throws an [IOException] that says why (HTTP status, network). */
fun interface FontFetcher {
    fun open(url: String): FontDownloadStream
}

fun okHttpFontFetcher(client: OkHttpClient): FontFetcher {
    val fontClient = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    return FontFetcher { url ->
        val response = fontClient.newCall(Request.Builder().url(url).get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        val body = response.body
        FontDownloadStream(body.byteStream(), body.contentLength().takeIf { it >= 0 }) { response.close() }
    }
}

/**
 * Service-level font downloads, the same shape as the rootfs installs: a download keeps
 * running when the page is left, the App and the Web watch the same [states], and either side can
 * cancel. Each font is written to `<id>.part` while its SHA-256 is computed and only renamed to
 * `<id>.ttf` when size and digest match the catalog; otherwise the next mirror is tried.
 */
class FontDownloadService(
    fontsRoot: File,
    private val scope: CoroutineScope,
    private val fetcher: FontFetcher,
    val catalog: List<DownloadableFont> = DownloadableFonts.ALL,
    private val logWarning: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {
    private val dir = File(fontsRoot, DOWNLOADED_FONTS_DIR)

    private val _states = MutableStateFlow<Map<String, FontDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, FontDownloadState>> = _states.asStateFlow()

    private val _installed = MutableStateFlow(scanInstalled())
    /** Ids whose file is present with the catalog size; changes on download and delete. */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val jobs = HashMap<String, Job>()

    fun font(id: String): DownloadableFont? = catalog.firstOrNull { it.id == id }

    /** The installed file of [id], or null when it is not (completely) downloaded. */
    fun fileOf(id: String): File? {
        val font = font(id) ?: return null
        return fileFor(font).takeIf { it.isFile && it.length() == font.sizeBytes }
    }

    /** Starts a download; throws [FontDownloadAlreadyRunningException] when one is in flight. */
    fun start(id: String): FontDownloadState {
        val font = font(id) ?: throw NoSuchElementException("Unknown font: $id")
        val initial = FontDownloadState(id, FontDownloadState.STATUS_DOWNLOADING, totalBytes = font.sizeBytes)
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) throw FontDownloadAlreadyRunningException()
            _states.update { it + (id to initial) }
            jobs[id] = scope.launch(Dispatchers.IO) {
                try {
                    download(font)
                    _installed.value = scanInstalled()
                    _states.update { it - id }
                } catch (cancelled: CancellationException) {
                    _states.update { it - id }
                    throw cancelled
                } catch (error: Throwable) {
                    logWarning("font download failed for $id", error)
                    _states.update { it + (id to initial.copy(status = FontDownloadState.STATUS_FAILED, error = error.message ?: error::class.java.simpleName)) }
                } finally {
                    synchronized(jobs) {
                        if (jobs[id] == coroutineContext[Job]) jobs.remove(id)
                    }
                }
            }
        }
        return initial
    }

    fun cancel(id: String): Boolean = synchronized(jobs) {
        val job = jobs[id]?.takeIf { it.isActive } ?: return false
        job.cancel(CancellationException("Font download cancelled"))
        true
    }

    /** Clears a failed state once the user has seen it. */
    fun dismiss(id: String) {
        _states.update { states -> if (states[id]?.downloading == true) states else states - id }
    }

    /** Deletes an installed font; refused while it is downloading. */
    fun delete(id: String): Boolean {
        val font = font(id) ?: return false
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) throw FontDownloadAlreadyRunningException()
            val deleted = fileFor(font).delete()
            File(dir, "$id.part").delete()
            _installed.value = scanInstalled()
            return deleted
        }
    }

    private fun fileFor(font: DownloadableFont) = File(dir, "${font.id}.ttf")

    private fun scanInstalled(): Set<String> =
        catalog.filter { font -> fileFor(font).let { it.isFile && it.length() == font.sizeBytes } }
            .mapTo(LinkedHashSet()) { it.id }

    private suspend fun download(font: DownloadableFont) {
        dir.mkdirs()
        val part = File(dir, "${font.id}.part")
        var lastError: Throwable? = null
        try {
            for (url in font.urls) {
                currentCoroutineContext().ensureActive()
                try {
                    downloadFrom(font, url, part)
                    val target = fileFor(font)
                    if (!part.renameTo(target)) {
                        target.delete()
                        if (!part.renameTo(target)) throw IOException("Cannot move the downloaded font into place")
                    }
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logWarning("font ${font.id}: $url failed: ${error.message}", null)
                    lastError = error
                    _states.update { states ->
                        val current = states[font.id] ?: return@update states
                        states + (font.id to current.copy(bytesRead = 0))
                    }
                }
            }
        } finally {
            part.delete()
        }
        throw IOException(lastError?.message ?: "No download source", lastError)
    }

    private suspend fun downloadFrom(font: DownloadableFont, url: String, part: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        var reported = 0L
        fetcher.open(url).use { stream ->
            stream.contentLength?.let { length ->
                if (length != font.sizeBytes) throw IOException("Unexpected size $length (expected ${font.sizeBytes})")
            }
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = stream.body.read(buffer)
                    if (n < 0) break
                    read += n
                    if (read > font.sizeBytes) throw IOException("Download is larger than ${font.sizeBytes} bytes")
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                    if (read - reported >= PROGRESS_STEP || read == font.sizeBytes) {
                        reported = read
                        val progress = read
                        _states.update { states ->
                            val current = states[font.id] ?: return@update states
                            states + (font.id to current.copy(bytesRead = progress))
                        }
                    }
                }
                output.fd.sync()
            }
        }
        if (read != font.sizeBytes) throw IOException("Incomplete download: $read of ${font.sizeBytes} bytes")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != font.sha256) throw IOException("Checksum mismatch")
    }

    private companion object {
        const val PROGRESS_STEP = 256L * 1024
    }
}
