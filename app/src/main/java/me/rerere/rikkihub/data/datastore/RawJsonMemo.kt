package me.rerere.rikkihub.data.datastore

/**
 * 按原始 JSON 字符串缓存上一次的解码结果。
 *
 * `Preferences.decodeSettings()` 在每次设置发射和每次 `mutate` 时都会把所有键整份重新解码；世界书这类
 * 体积大（可达 1 MB 以上）而又很少变化的键，没变时直接复用上一次解码出的对象，改任何无关设置就不再
 * 重复解码它。复用的是同一个实例，下游的相等比较（设置账本、`persistSettingsIn` 里的 `old != new`）也
 * 因此走引用相等的快路径。
 */
internal class RawJsonMemo<T : Any>(private val decode: (String) -> T) {
    private class Entry<T>(val raw: String, val value: T)

    @Volatile
    private var last: Entry<T>? = null

    fun get(raw: String): T {
        val cached = last
        if (cached != null && (cached.raw === raw || cached.raw == raw)) return cached.value
        val value = decode(raw)
        last = Entry(raw, value)
        return value
    }
}
