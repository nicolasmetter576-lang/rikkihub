package me.rerere.rikkihub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkihub.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
