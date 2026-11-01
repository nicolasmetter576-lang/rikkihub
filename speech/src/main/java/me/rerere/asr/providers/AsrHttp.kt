package me.rerere.asr.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

// HTTP 请求失败时的最大重试次数 (含首次). 主要用于偶发网络抖动/5xx 的自动恢复.
private const val MAX_RETRY = 3

/** 带 HTTP 状态码的 ASR 请求异常, 用于区分可重试错误. */
internal class AsrHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

/**
 * HTTP ASR 分段上传的通用重试: 最多 [MAX_RETRY] 次, 线性退避 (300ms, 600ms)。
 *
 * 4xx (除 408/429) 属于请求或鉴权错误, 重试无意义, 直接抛出;
 * 其余 IOException (网络错误 / 408 / 429 / 5xx) 会重试。
 */
internal suspend fun executeAsrRequestWithRetry(
    tag: String,
    httpClient: OkHttpClient,
    request: Request,
    parse: (Response) -> String,
): String {
    var lastError: IOException? = null
    for (attempt in 1..MAX_RETRY) {
        try {
            return withContext(Dispatchers.IO) {
                httpClient.newCall(request).await().use { resp ->
                    if (!resp.isSuccessful) {
                        throw AsrHttpException(
                            resp.code,
                            "HTTP ${resp.code}: ${resp.body.string()}"
                        )
                    }
                    parse(resp)
                }
            }
        } catch (e: AsrHttpException) {
            if (e.statusCode in 400..499 && e.statusCode != 408 && e.statusCode != 429) throw e
            lastError = e
            logRetry(tag, attempt, e)
        } catch (e: IOException) {
            lastError = e
            logRetry(tag, attempt, e)
        }
    }
    throw lastError ?: IOException("ASR request failed")
}

private suspend fun logRetry(tag: String, attempt: Int, e: IOException) {
    Log.w(tag, "flushSegment attempt $attempt/$MAX_RETRY failed: ${e.message}")
    if (attempt < MAX_RETRY) {
        delay(300L * attempt) // 退避: 300ms, 600ms
    }
}
