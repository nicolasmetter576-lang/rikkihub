package me.rerere.rikkihub.data.files

import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub API 限流（429，或 403 且 X-RateLimit-Remaining=0）。由 UI 层映射为
 * 本地化文案展示，不在下载层硬编码用户可见消息。
 */
internal class GitHubRateLimitException(url: String) : Exception("GitHub rate limit: $url")

/**
 * 判断 HTTP 响应是否为 GitHub API 限流：429 本身就是限流；
 * 403 仅在 X-RateLimit-Remaining 归零时判为限流（否则是权限等其他问题）。
 */
internal fun isGitHubRateLimit(responseCode: Int, rateLimitRemaining: String?): Boolean {
    if (responseCode == 429) return true
    if (responseCode == 403 && rateLimitRemaining?.trim() == "0") return true
    return false
}

/**
 * 按字节下载 GitHub 内容（含图片/PDF 等二进制附件，不能先按 UTF-8 解码）。
 * 限流时抛 [GitHubRateLimitException] 供调用方给出准确反馈；其他非 200 返回
 * null，由调用方转换为已有的具体失败原因。
 */
internal fun downloadGitHubBytes(url: String): ByteArray? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    try {
        val code = connection.responseCode
        // 未登录的 GitHub API 每小时仅 60 次，超限时给出明确提示而不是笼统的"读取失败"
        if (isGitHubRateLimit(code, connection.getHeaderField("X-RateLimit-Remaining"))) {
            throw GitHubRateLimitException(url)
        }
        return if (code == HttpURLConnection.HTTP_OK) {
            connection.inputStream.use { it.readBytes() }
        } else {
            null
        }
    } finally {
        connection.disconnect()
    }
}
