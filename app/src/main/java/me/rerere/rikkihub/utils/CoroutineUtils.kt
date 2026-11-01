package me.rerere.rikkihub.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "CoroutineUtils"
private const val MAX_MARKER_LENGTH = 4000

/**
 * Installed by the Application so [toMutableStateFlow] can leave a durable crash
 * marker on disk before halting (halt skips normal crash handlers).
 */
@Volatile
var fatalHaltMarkerFile: File? = null

private fun writeFatalHaltMarker(error: Throwable) {
    val file = fatalHaltMarkerFile ?: return
    runCatching {
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine("fatalHaltAt=${System.currentTimeMillis()}")
                appendLine(error.stackTraceToString().take(MAX_MARKER_LENGTH))
            }
        )
    }
}

fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        runCatching {
            this@toMutableStateFlow.collect { value ->
                stateFlow.value = value
            }
        }.onFailure {
            it.printStackTrace()
            Log.e(TAG, "Error while collecting flow: ${it.message}", it)

            writeFatalHaltMarker(it)
            Runtime.getRuntime().halt(1)
        }
    }
    return stateFlow
}
