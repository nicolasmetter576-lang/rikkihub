package me.rerere.rikkihub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkihub.ui.components.ui.toast.ToasterState

val LocalToaster = staticCompositionLocalOf<ToasterState> { error("Not provided") }