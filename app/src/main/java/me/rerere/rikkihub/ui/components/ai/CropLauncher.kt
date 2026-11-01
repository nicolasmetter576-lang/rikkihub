package me.rerere.rikkihub.ui.components.ai

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.io.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.Logging
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.crop.ImageCropDialog
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster

/** The optional File is ONLY a captured camera temporary owned by this app, never a user original. */
internal typealias CropRequestLauncher = (sourceUri: Uri, cameraTemporary: File?) -> Unit

/** Small saved state of the open crop dialog: where to read the image and which camera temporary it owns. */
private data class CropRequest(val source: String, val cameraPath: String?) : Serializable

/**
 * Returns the function that opens the crop dialog for an image. The cropped result is written to an
 * owned per-request lease file, handed to [onCroppedImageReady] (the dialog stays open and locked until
 * the consumer has actually read it) and then deleted. [aspectRatio] (width / height) fixes the crop frame.
 */
@Composable
internal fun useCropLauncher(
    onCroppedImageReady: suspend (Uri) -> Unit,
    aspectRatio: Float? = null,
): CropRequestLauncher {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val consumeLatest by rememberUpdatedState(onCroppedImageReady)
    // This dedicated cache is not the appTempFolder erased asynchronously on app startup.
    val root = remember(context.cacheDir) { File(context.cacheDir, "crop_requests") }
    // Survives a configuration change; the dialog reloads the image from the source and starts over.
    var request by rememberSaveable { mutableStateOf<CropRequest?>(null) }
    var saving by remember { mutableStateOf(false) }
    val currentRequest by rememberUpdatedState(request)
    val failedText = stringResource(R.string.chat_crop_failed)
    val busyText = stringResource(R.string.chat_crop_busy)
    val unknownErrorText = stringResource(R.string.search_detail_unknown_error)

    // A rotation keeps the request (and its camera temporary); really leaving the page drops the temporary.
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                deleteOwnedCameraFile(context.cacheDir, currentRequest?.cameraPath)
            }
        }
    }

    fun showFailure(error: Throwable?) {
        val message = error?.message ?: unknownErrorText
        Logging.log("CropLauncher", "crop failed: $message")
        toaster.show(String.format(failedText, message), type = ToastType.Error)
    }

    fun close(closing: CropRequest) {
        request = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) {
                deleteOwnedCameraFile(context.cacheDir, closing.cameraPath)
            }
        }
    }

    fun save(active: CropRequest, bitmap: Bitmap) {
        if (saving) return
        saving = true
        // Enter try/finally even if the page is cancelled before the job can dispatch.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var lease: CropPendingFiles? = null
            var failure: Throwable? = null
            var cancelled = false
            try {
                currentCoroutineContext().ensureActive()
                val encoding = withContext(Dispatchers.IO) {
                    // The policy looks at the source bytes, never at the decoded bitmap or a file name.
                    context.contentResolver.openInputStream(active.source.toUri())?.use { chooseCropOutputEncoding(it) }
                        ?: CropOutputEncoding.PNG
                }
                val claimed = withContext(Dispatchers.IO) { claimCropFiles(root) }.copy(encoding = encoding)
                lease = claimed
                withContext(Dispatchers.IO) {
                    val output = cropLeaseFile(root, claimed, "result.${encoding.extension}")
                    check(output.createNewFile()) { "Crop output already exists" }
                    output.outputStream().use { stream ->
                        val format = if (encoding == CropOutputEncoding.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                        if (!bitmap.compress(format, encoding.quality, stream)) throw IOException("Image encoding failed")
                    }
                }
                consumeCropOutput(root, claimed) {
                    consumeLatest(it.toUri()) // suspend until upload/avatar has actually read the output
                }
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } catch (error: Exception) {
                failure = error
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    lease?.let { cleanupCropFiles(root, it) }
                }
                // After a cancellation (page left / rotated) the request is not ours to close any more.
                if (!cancelled) {
                    saving = false
                    close(active)
                    failure?.let(::showFailure)
                }
            }
        }
    }

    request?.let { active ->
        ImageCropDialog(
            image = active.source.toUri(),
            aspectRatio = aspectRatio,
            busy = saving,
            onDismiss = { close(active) },
            onConfirm = { bitmap -> save(active, bitmap) },
        )
    }

    return { sourceUri, cameraTemporary ->
        val cameraPath = ownedCameraPath(context.cacheDir, cameraTemporary)
        val active = request
        if (active != null) {
            // Freeze the ownership comparison before dispatching cleanup to IO.
            val sameActiveCamera = cameraPath != null && cameraPath == active.cameraPath
            // Reject re-entry without replacing/deleting the active request's resources.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!sameActiveCamera) deleteOwnedCameraFile(context.cacheDir, cameraPath)
                }
            }
            toaster.show(busyText, type = ToastType.Error)
        } else {
            request = CropRequest(sourceUri.toString(), cameraPath)
        }
    }
}
