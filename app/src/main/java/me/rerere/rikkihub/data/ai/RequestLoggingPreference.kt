package me.rerere.rikkihub.data.ai

import android.content.Context
import androidx.core.content.edit
import me.rerere.common.android.Logging

/** Persists the log page's "record requests" switch (default on) across launches. */
object RequestLoggingPreference {
    private const val PREFS = "request_logging"
    private const val KEY_ENABLED = "enabled"

    /** Applies the stored choice to [Logging]; called once at app start. */
    fun restore(context: Context) {
        val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        Logging.setRequestLoggingEnabled(enabled)
    }

    fun set(context: Context, enabled: Boolean) {
        Logging.setRequestLoggingEnabled(enabled)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
    }
}
