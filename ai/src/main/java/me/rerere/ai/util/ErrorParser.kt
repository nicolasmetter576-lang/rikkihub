package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class HttpException(
    message: String,
    /** 上游 HTTP 状态码；未知（错误体里解析出、没有响应对象）时为 null。 */
    val statusCode: Int? = null,
    /** 响应头 `Retry-After` 换算的等待毫秒数；没有或无法解析为 null。 */
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * SSE `onFailure` 收尾用：把非 2xx 响应的状态码与 `Retry-After` 补到异常上，供上层
 * 判断限流重试。异常消息保持原样（错误体解析出的 [HttpException]、其他非网络异常都只
 * 包一层带状态的 [HttpException]）；原来没有异常（错误体为空）时给出 `HTTP <code>`，
 * 而不是让流悄悄正常结束。网络异常（[java.io.IOException]）与 2xx 响应原样返回。
 */
fun httpFailure(error: Throwable?, response: okhttp3.Response?, bodyRaw: String? = null): Throwable? {
    if (response == null || response.isSuccessful) return error
    val code = response.code
    val retryAfter = parseRetryAfterMillis(response.header("Retry-After"))
    return when (error) {
        is HttpException -> HttpException(error.message.orEmpty(), code, retryAfter, error.cause)
            .also { it.stackTrace = error.stackTrace }
        null -> HttpException(
            "HTTP $code" + bodyRaw?.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty(),
            code,
            retryAfter,
        )
        is java.io.IOException -> error
        else -> HttpException(error.message ?: "HTTP $code", code, retryAfter, error)
    }
}

/** `Retry-After`：秒数或 HTTP 日期（RFC 1123）。过去的日期按 0。 */
fun parseRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toLongOrNull()?.let { seconds -> return (seconds * 1000).coerceAtLeast(0) }
    return runCatching {
        java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - nowMillis
    }.getOrNull()?.coerceAtLeast(0)
}

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}
