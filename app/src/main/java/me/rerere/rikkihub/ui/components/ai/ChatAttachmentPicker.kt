package me.rerere.rikkihub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkihub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkihub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.hooks.ChatInputState
import me.rerere.rikkihub.utils.isAllowedFileType
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid
import androidx.compose.runtime.remember
import me.rerere.rikkihub.ui.components.sketch.SketchResult
import me.rerere.rikkihub.ui.components.sketch.SketchDialog

internal data class ChatAttachmentPickerActions(
    val onTakePicture: () -> Unit,
    val onPickImage: () -> Unit,
    val onPickVideo: () -> Unit,
    val onPickAudio: () -> Unit,
    val onPickFile: () -> Unit,
    val onSketch: () -> Unit,
)

@Composable
internal fun rememberChatAttachmentPickerActions(
    inputState: ChatInputState,
    setting: Settings,
    onAttachmentAdded: () -> Unit,
): ChatAttachmentPickerActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    suspend fun consumeCroppedImage(croppedUri: Uri) {
        val files = withContext(Dispatchers.IO) {
            filesManager.createChatFilesByContents(listOf(croppedUri))
        }
        if (files.isEmpty()) throw IOException(resources.getString(R.string.chat_crop_save_failed))
        withContext(Dispatchers.Main) {
            inputState.addImages(files)
            onAttachmentAdded()
        }
    }

    var cameraOutputUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by rememberSaveable { mutableStateOf<File?>(null) }
    val launchCameraCrop = useCropLauncher(onCroppedImageReady = ::consumeCroppedImage)
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        val capturedUri = cameraOutputUri
        val outputFile = cameraOutputFile
        // Transfer only this capture's temporary to the crop request; never read a
        // mutable camera pointer later when a newer capture could have replaced it.
        cameraOutputFile = null
        cameraOutputUri = null
        if (captureSuccessful && capturedUri != null) {
            if (setting.displaySetting.skipCropImage) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        consumeCroppedImage(capturedUri)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        toaster.show(error.message ?: resources.getString(R.string.chat_crop_save_failed), type = ToastType.Error)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            deleteOwnedCameraFile(context.cacheDir, outputFile?.path)
                        }
                    }
                }
            } else {
                launchCameraCrop(capturedUri, outputFile)
            }
        } else {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable + Dispatchers.IO) {
                    deleteOwnedCameraFile(context.cacheDir, outputFile?.path)
                }
            }
        }
    }
    val onTakePicture: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    val launchImageCrop = useCropLauncher(onCroppedImageReady = ::consumeCroppedImage)
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    scope.launch(Dispatchers.IO) {
                        val files = filesManager.createChatFilesByContents(selectedUris)
                        withContext(Dispatchers.Main) {
                            inputState.addImages(files)
                            onAttachmentAdded()
                        }
                    }
                } else if (selectedUris.size == 1) {
                    // The crop launcher owns copying/probing/HEIF preparation on IO,
                    // so input suffix and alpha policy no longer depend on a fake .jpg name.
                    launchImageCrop(selectedUris.first(), null)
                } else {
                    scope.launch(Dispatchers.IO) {
                        val files = filesManager.createChatFilesByContents(selectedUris)
                        withContext(Dispatchers.Main) {
                            inputState.addImages(files)
                            onAttachmentAdded()
                        }
                    }
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val files = filesManager.createChatFilesByContents(selectedUris)
                    withContext(Dispatchers.Main) {
                        inputState.addVideos(files)
                        onAttachmentAdded()
                    }
                }
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val files = filesManager.createChatFilesByContents(selectedUris)
                    withContext(Dispatchers.Main) {
                        inputState.addAudios(files)
                        onAttachmentAdded()
                    }
                }
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val documents = mutableListOf<UIMessagePart.Document>()
                    val readFailedNames = mutableListOf<String>()
                    val unsupportedNames = mutableListOf<String>()
                    for (uri in uris) {
                        val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                        val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                        if (isAllowedFileType(fileName, mime)) {
                            val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            if (localUri != null) {
                                documents.add(
                                    UIMessagePart.Document(
                                        url = localUri.toString(),
                                        fileName = fileName,
                                        mime = mime
                                    )
                                )
                            } else {
                                readFailedNames.add(fileName)
                            }
                        } else {
                            unsupportedNames.add(fileName)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        unsupportedNames.forEach { fileName ->
                            toaster.show(
                                resources.getString(R.string.chat_input_unsupported_file_type, fileName),
                                type = ToastType.Error
                            )
                        }
                        readFailedNames.forEach { fileName ->
                            toaster.show(
                                resources.getString(R.string.chat_input_file_read_failed, fileName),
                                type = ToastType.Error
                            )
                        }
                        if (documents.isNotEmpty()) {
                            inputState.addFiles(documents)
                            onAttachmentAdded()
                        }
                    }
                }
            }
        }

    var showSketch by remember { mutableStateOf(false) }
    if (showSketch) {
        SketchDialog(
            onDismiss = { showSketch = false },
            onConfirm = { result ->
                showSketch = false
                scope.launch {
                    inputState.addImages(listOfNotNull(filesManager.createChatFileBySketch(result)))
                    onAttachmentAdded()
                }
            },
        )
    }

    return ChatAttachmentPickerActions(
        onTakePicture = onTakePicture,
        onPickImage = { imagePickerLauncher.launch("image/*") },
        onPickVideo = { videoPickerLauncher.launch("video/*") },
        onPickAudio = { audioPickerLauncher.launch("audio/*") },
        onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        onSketch = { showSketch = true },
    )
}

/** 把画板的结果存成聊天附件。编码在 IO 线程上做 */
internal suspend fun FilesManager.createChatFileBySketch(result: SketchResult): Uri? = withContext(Dispatchers.IO) {
    createChatFilesByByteArrays(
        byteArrays = listOf(result.encode()),
        displayName = "sketch.${result.extension}",
        mimeType = result.mimeType,
    ).firstOrNull()
}
