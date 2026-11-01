package me.rerere.rikkihub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkihub.data.datastore.Settings

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
