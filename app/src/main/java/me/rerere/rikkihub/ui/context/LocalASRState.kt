package me.rerere.rikkihub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkihub.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

