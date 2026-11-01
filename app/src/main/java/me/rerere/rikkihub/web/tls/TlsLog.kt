package me.rerere.rikkihub.web.tls

/**
 * Logging indirection for the TLS frontend package.
 *
 * Everything under `web/tls/` must stay loadable on a plain JVM unit-test
 * runtime (no `android.util.Log` available). Production wiring points this at
 * `android.util.Log`; tests use [TlsLogger.NOOP].
 */
fun interface TlsLogger {
    fun log(level: Level, tag: String, message: String, error: Throwable?)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    companion object {
        val NOOP = TlsLogger { _, _, _, _ -> }
    }
}
