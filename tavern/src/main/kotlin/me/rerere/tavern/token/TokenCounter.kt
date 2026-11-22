package me.rerere.tavern.token

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType

/**
 * 统一用 cl100k_base 计数：ST 对未知来源回退到 gpt-3.5-turbo 分词器（ST:public/scripts/tokenizers.js:789），
 * 即 cl100k_base。这是有意的近似，不按模型区分。
 */
object TokenCounter {
    private val encoding: Encoding by lazy {
        Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
    }

    /** 首次计数要加载并解析编码表（约 1 秒），应用启动后在后台线程调用一次。 */
    fun warmUp() {
        encoding.countTokensOrdinary("warm up")
    }

    fun count(text: String): Int = if (text.isEmpty()) 0 else encoding.countTokensOrdinary(text)
}
