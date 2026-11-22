package me.rerere.rikkihub.data.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.R
import me.rerere.workspace.WorkspaceArchiveExporter
import me.rerere.workspace.WorkspaceArchiveExtractor
import me.rerere.workspace.WorkspaceBatchDeleter
import me.rerere.workspace.WorkspaceConfig
import me.rerere.workspace.WorkspaceDeleteResult
import me.rerere.workspace.WorkspaceExtractProgress
import me.rerere.workspace.WorkspaceExtractResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceFileNames
import me.rerere.workspace.WorkspaceFileOperationException
import me.rerere.workspace.WorkspaceFileSystem
import me.rerere.workspace.WorkspaceFileTransfer
import me.rerere.workspace.WorkspaceImportNode
import me.rerere.workspace.WorkspaceImportResult
import me.rerere.workspace.WorkspaceNames
import me.rerere.workspace.WorkspaceTextSnapshot
import me.rerere.workspace.WorkspaceTextWriteResult
import me.rerere.workspace.WorkspaceTransferItemResult
import me.rerere.workspace.WorkspaceTransferMode
import me.rerere.workspace.WorkspaceTransferProgress
import me.rerere.workspace.WorkspaceTransferResult
import me.rerere.workspace.WorkspaceTreeImporter
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.LocalDate

/** What one file manager pane browses: the in-app library, or one storage volume of the phone. */
@Serializable
sealed interface FileManagerRoot {
    @Serializable
    @SerialName("library")
    data object Library : FileManagerRoot

    @Serializable
    @SerialName("device")
    data class Device(val volumeId: String) : FileManagerRoot
}

/** What a copy out of the file manager reads from. */
sealed interface FileManagerSource {
    /** [paths] relative to the real folder [dir]. */
    data class Items(val dir: File, val paths: List<String>) : FileManagerSource

    /** All of [dir] as one folder named [name]: a workspace folder, whose files area is a root of its own. */
    data class Folder(val dir: File, val name: String) : FileManagerSource
}

/** A workspace as the library shows it: a folder named after it with its files area inside. */
data class FileManagerWorkspace(val name: String, val filesDir: File, val updatedAt: Long)

data class FileManagerVolume(
    val id: String,
    val label: String,
    val primary: Boolean,
    val directory: File,
)

enum class FileManagerDeviceAccess { READY, PERMISSION_REQUIRED }

/**
 * File operations of the built-in file manager, shared by the App pages and the Web API.
 *
 * Every path is relative to a [FileManagerRoot]; the workspace file layer refuses `..` and
 * symbolic links that would leave the root. Phone access is re-checked on every call (the user
 * can revoke it in system settings at any time).
 *
 * The library and chat attachments never share files: sending copies into `upload`
 * ([FilesManager.createChatPartsFromLocalFiles]) and collecting copies back ([collectChatUploads]).
 * Android does not let apps hard-link their own files (SELinux denies `link` on app data).
 */
class FileManagerService(
    private val libraryRoot: File,
    private val libraryStaging: File,
    private val volumes: () -> List<FileManagerVolume>,
    private val deviceAccess: () -> FileManagerDeviceAccess,
    private val uploadDir: File,
    /** Original name of an upload (`upload/<name>`), from the managed file table. */
    private val uploadDisplayName: suspend (fileName: String) -> String?,
    /** Library folder that collects chat uploads (localized name). */
    private val chatUploadsFolder: () -> String,
    /** Whether an upload (`upload/<name>`) was made from a library file. */
    private val isLibraryCopy: (fileName: String) -> Boolean,
    /** Current workspaces (a cached list: called on the main thread too). */
    private val workspaces: () -> List<FileManagerWorkspace> = { emptyList() },
    /** Library folder that lists the workspaces (localized name). */
    private val workspacesFolderName: () -> String = { "Workspaces" },
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val fileSystem = WorkspaceFileSystem(
        WorkspaceConfig(maxReadBytes = MAX_TEXT_BYTES, maxListEntries = Int.MAX_VALUE),
    )
    private val transfer = WorkspaceFileTransfer(fileSystem)
    private val deleter = WorkspaceBatchDeleter(fileSystem)
    private val archiver = WorkspaceArchiveExporter(fileSystem)
    private val extractor = WorkspaceArchiveExtractor(fileSystem)
    private val treeImporter = WorkspaceTreeImporter(fileSystem)
    private val indexes = ConcurrentHashMap<FileManagerRoot, FileNameIndex>()

    fun volumes(): List<FileManagerVolume> = volumes.invoke()

    fun deviceAccess(): FileManagerDeviceAccess = deviceAccess.invoke()

    /** Directory of [root]; throws `FILE_PERMISSION_DENIED` when phone access is missing. */
    fun rootDir(root: FileManagerRoot): File = when (root) {
        FileManagerRoot.Library -> libraryRoot.apply { mkdirs() }
        is FileManagerRoot.Device -> {
            if (deviceAccess() != FileManagerDeviceAccess.READY) {
                throw WorkspaceFileOperationException(
                    WorkspaceFileOperationException.FILE_PERMISSION_DENIED,
                    "All files access is not granted",
                )
            }
            volumes().firstOrNull { it.id == root.volumeId }?.directory
                ?: throw WorkspaceFileOperationException(
                    WorkspaceFileOperationException.FILE_NOT_FOUND,
                    "Storage volume is not available: ${root.volumeId}",
                )
        }
    }

    /**
     * A file manager path resolved to the real folder it lives in: [dir] + [path] (relative to
     * [dir]); [prefix] is the file manager path of [dir] ("" for a root). Library paths under the
     * workspaces folder resolve into that workspace's files area.
     */
    private data class Location(val dir: File, val path: String, val prefix: String) {
        fun logical(relative: String): String = when {
            prefix.isEmpty() -> relative
            relative.isEmpty() -> prefix
            else -> "$prefix/$relative"
        }
    }

    /** The library folder that lists the workspaces (localized; " (n)" if a real folder took the name). */
    private fun workspacesFolder(): String {
        val base = workspacesFolderName()
        var name = base
        var n = 1
        while (Files.exists(File(libraryRoot, name).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            name = WorkspaceNames.fitConflictName(base, n++, isDirectory = true)
        }
        return name
    }

    /** Workspaces by folder name: names made safe for a path segment, clashes numbered. */
    private fun workspaceFolders(): Map<String, FileManagerWorkspace> {
        val folders = LinkedHashMap<String, FileManagerWorkspace>()
        for (workspace in workspaces()) {
            val base = workspace.name.replace('/', '／').trim().takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "workspace"
            var name = base
            var n = 1
            while (name in folders) name = WorkspaceNames.fitConflictName(base, n++, isDirectory = true)
            folders[name] = workspace
        }
        return folders
    }

    /**
     * Resolves [path]; null for the two virtual levels (the workspaces folder itself and, with
     * [allowWorkspaceRoot] false, a workspace folder), which can be entered but not changed.
     */
    private fun locate(root: FileManagerRoot, path: String, allowWorkspaceRoot: Boolean = true): Location? {
        val clean = path.trim('/')
        if (root != FileManagerRoot.Library) return Location(rootDir(root), clean, "")
        val folder = workspacesFolder()
        if (clean != folder && !clean.startsWith("$folder/")) return Location(rootDir(root), clean, "")
        val rest = clean.removePrefix(folder).trimStart('/')
        if (rest.isEmpty()) return null
        val name = rest.substringBefore('/')
        val inside = rest.substringAfter('/', missingDelimiterValue = "")
        // Directly in the workspaces folder nothing can be created or changed, whatever the name.
        if (inside.isEmpty() && !allowWorkspaceRoot) return null
        val workspace = workspaceFolders()[name]
            ?: throw WorkspaceFileOperationException(WorkspaceFileOperationException.FILE_NOT_FOUND, "No such workspace: $name")
        return Location(workspace.filesDir.apply { mkdirs() }, inside, "$folder/$name")
    }

    /** Like [locate] for something that is about to change: the virtual levels refuse. */
    private fun locateWritable(root: FileManagerRoot, path: String, allowWorkspaceRoot: Boolean = false): Location =
        locate(root, path, allowWorkspaceRoot) ?: throw readOnlyFolder()

    private fun readOnlyFolder() = WorkspaceFileOperationException(
        READ_ONLY_FOLDER,
        "The workspaces folder and the workspace folders in it cannot be changed here",
    )

    /** Groups [paths] about to change by the real folder they live in (one group unless they span workspaces). */
    private fun locateAll(root: FileManagerRoot, paths: List<String>): List<Pair<Location, List<String>>> =
        paths.map { locateWritable(root, it) }
            .groupBy { it.dir to it.prefix }
            .map { (_, located) -> located.first() to located.map { it.path } }

    private fun WorkspaceFileEntry.under(location: Location) = copy(path = location.logical(path))

    /**
     * The file name index of [root] (library: workspace folders included): reused while younger than
     * [INDEX_MAX_AGE_MS] and untouched by this service, otherwise walked again. [onProgress] gets the
     * number of entries found so far.
     */
    suspend fun nameIndex(root: FileManagerRoot, onProgress: (Int) -> Unit = {}): FileNameIndex = io {
        indexes[root]?.takeIf { System.currentTimeMillis() - it.builtAt < INDEX_MAX_AGE_MS }?.let { return@io it }
        val builder = FileNameIndexBuilder(
            onProgress = onProgress,
            // Android 11+ lists nothing there for other apps; skip instead of failing on every folder.
            skip = if (root is FileManagerRoot.Device) { path -> path == "Android/data" || path == "Android/obb" } else { _ -> false },
        )
        builder.add(rootDir(root), "")
        if (root == FileManagerRoot.Library) {
            val folders = workspaceFolders()
            if (folders.isNotEmpty()) {
                val folder = workspacesFolder()
                builder.addVirtualFolder(folder, folder, updatedAt = folders.values.maxOf { it.updatedAt })
                folders.forEach { (name, workspace) ->
                    builder.addVirtualFolder("$folder/$name", name, workspace.updatedAt)
                    builder.add(workspace.filesDir, "$folder/$name")
                }
            }
        }
        builder.build(System.currentTimeMillis()).also { indexes[root] = it }
    }

    /** Drops the cached index of [roots] after this service changed files there. */
    private fun changed(vararg roots: FileManagerRoot) = roots.forEach { indexes.remove(it) }

    /**
     * Where [paths] of [root] really live, for copying them elsewhere: grouped by folder, a workspace
     * folder as a whole. The workspaces folder itself has nothing real to copy and refuses.
     */
    fun sources(root: FileManagerRoot, paths: List<String>): List<FileManagerSource> {
        val (folders, items) = paths.map { locate(root, it) ?: throw readOnlyFolder() }
            .partition { it.path.isEmpty() && it.prefix.isNotEmpty() }
        return folders.map { FileManagerSource.Folder(it.dir, it.prefix.substringAfterLast('/')) } +
            items.groupBy { it.dir to it.prefix }.map { (_, located) -> FileManagerSource.Items(located.first().dir, located.map { it.path }) }
    }

    /** The real file at [path]; null for the workspaces folder, which exists only in the file manager. */
    fun realFile(root: FileManagerRoot, path: String): File? =
        locate(root, path)?.let { fileSystem.resolve(it.dir, it.path) }

    /** The file at [path] (for preview, sharing, download, attaching). */
    fun file(root: FileManagerRoot, path: String): File = realFile(root, path) ?: throw readOnlyFolder()

    suspend fun list(root: FileManagerRoot, path: String): List<WorkspaceFileEntry> = io {
        val location = locate(root, path)
            ?: return@io workspaceFolders().map { (name, workspace) ->
                WorkspaceFileEntry("${workspacesFolder()}/$name", name, isDirectory = true, sizeBytes = 0, updatedAt = workspace.updatedAt)
            }
        val entries = fileSystem.list(location.dir, location.path).map { it.under(location) }
        val workspaces = if (root == FileManagerRoot.Library && location.prefix.isEmpty() && location.path.isEmpty()) workspaces() else emptyList()
        if (workspaces.isNotEmpty()) {
            val folder = workspacesFolder()
            entries + WorkspaceFileEntry(folder, folder, isDirectory = true, sizeBytes = 0, updatedAt = workspaces.maxOf { it.updatedAt })
        } else entries
    }

    suspend fun createFolder(root: FileManagerRoot, path: String): WorkspaceFileEntry = io {
        val location = locateWritable(root, path)
        fileSystem.createDirectory(location.dir, location.path).under(location).also { changed(root) }
    }

    suspend fun createFile(root: FileManagerRoot, path: String): WorkspaceFileEntry = io {
        val location = locateWritable(root, path)
        fileSystem.createFile(location.dir, location.path).under(location).also { changed(root) }
    }

    suspend fun rename(root: FileManagerRoot, path: String, newName: String): WorkspaceFileEntry = io {
        WorkspaceFileNames.validate(newName)
        val location = locateWritable(root, path)
        val parent = location.path.substringBeforeLast('/', missingDelimiterValue = "")
        fileSystem.move(location.dir, location.path, if (parent.isEmpty()) newName else "$parent/$newName").under(location)
            .also { changed(root) }
    }

    suspend fun delete(root: FileManagerRoot, paths: List<String>): WorkspaceDeleteResult = io {
        val results = locateAll(root, paths).map { (location, relative) ->
            val result = deleter.deleteBatch(location.dir, relative)
            result.copy(deletedPaths = result.deletedPaths.map(location::logical))
        }
        changed(root)
        WorkspaceDeleteResult(results.flatMap { it.deletedPaths }, results.flatMap { it.failures })
    }

    /** Copy or move into [targetDir] of [to]; clashes become "name (n)", nothing is overwritten. */
    suspend fun transfer(
        from: FileManagerRoot,
        paths: List<String>,
        to: FileManagerRoot,
        targetDir: String,
        mode: WorkspaceTransferMode,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): WorkspaceTransferResult = io {
        val target = locateWritable(to, targetDir, allowWorkspaceRoot = true)
        try {
            WorkspaceTransferResult(when (mode) {
                WorkspaceTransferMode.MOVE -> locateAll(from, paths).flatMap { (location, relative) ->
                    transfer.transferBatch(location.dir, relative, target.dir, target.path, mode, onProgress).items
                }
                WorkspaceTransferMode.COPY -> sources(from, paths).flatMap { source ->
                    when (source) {
                        is FileManagerSource.Items ->
                            transfer.transferBatch(source.dir, source.paths, target.dir, target.path, mode, onProgress).items
                        is FileManagerSource.Folder -> listOf(copyFolder(source, target, onProgress))
                    }
                }
            })
        } finally {
            changed(from, to)
        }
    }

    /** [source] copied into [target] as one item; a refusal becomes a failed item, as in a batch. */
    private suspend fun copyFolder(
        source: FileManagerSource.Folder,
        target: Location,
        onProgress: (WorkspaceTransferProgress) -> Unit,
    ): WorkspaceTransferItemResult = try {
        transfer.copyFolder(source.dir, target.dir, target.path, source.name, onProgress)
    } catch (error: WorkspaceFileOperationException) {
        WorkspaceTransferItemResult(source = source.name, errorCode = error.code, error = error.message)
    }

    /** Stores [input] as [fileName] in [dir]; an existing name gets " (n)". */
    suspend fun import(root: FileManagerRoot, dir: String, fileName: String, input: InputStream): WorkspaceFileEntry = io {
        val name = WorkspaceNames.sanitize(fileName)
        val location = locateWritable(root, dir, allowWorkspaceRoot = true)
        fileSystem.importBytes(location.dir, childPath(location.path, name), input).under(location).also { changed(root) }
    }

    suspend fun importFolder(root: FileManagerRoot, dir: String, tree: WorkspaceImportNode): WorkspaceImportResult = io {
        val location = locateWritable(root, dir, allowWorkspaceRoot = true)
        treeImporter.import(location.dir, location.path, tree).also { changed(root) }
    }

    /** Zips [paths] into a new `<name>.zip` in [dir] (" (n)" on clash); no partial file is left on failure. */
    suspend fun zip(root: FileManagerRoot, dir: String, paths: List<String>, name: String): WorkspaceFileEntry = io {
        val location = locateWritable(root, dir, allowWorkspaceRoot = true)
        val parent = fileSystem.resolve(location.dir, location.path)
        val baseName = WorkspaceNames.sanitize(if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip")
        var target = File(parent, baseName)
        var n = 1
        while (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            target = File(parent, WorkspaceNames.fitConflictName(baseName, n++, isDirectory = false))
        }
        val sources = sources(root, paths)
        try {
            // One archive only covers one real folder; picking across workspaces is not offered.
            when (val source = sources.singleOrNull() ?: throw WorkspaceFileOperationException(
                WorkspaceFileOperationException.INVALID_PATH, "Selected items are in different places",
            )) {
                is FileManagerSource.Items -> archiver.exportZip(source.dir, source.paths, target.outputStream())
                is FileManagerSource.Folder -> archiver.exportFolderZip(source.dir, source.name, target.outputStream())
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        changed(root)
        fileSystem.list(location.dir, location.path).first { it.name == target.name }.under(location)
    }

    /** Extracts next to the archive (see [WorkspaceArchiveExtractor]). */
    suspend fun extract(
        root: FileManagerRoot,
        path: String,
        password: CharArray? = null,
        nameEncoding: String? = null,
        onProgress: (WorkspaceExtractProgress) -> Unit = {},
    ): WorkspaceExtractResult = io {
        val location = locateWritable(root, path)
        // Staging must share the archive's filesystem: the result is moved into place at the end.
        // Library and workspace folders are both in app storage.
        val staging = when (root) {
            FileManagerRoot.Library -> libraryStaging
            is FileManagerRoot.Device -> File(location.dir, DEVICE_STAGING_NAME)
        }
        try {
            extractor.extract(location.dir, staging, location.path, password, nameEncoding, onProgress)
                .let { it.copy(path = location.logical(it.path)) }
        } finally {
            changed(root)
            if (root is FileManagerRoot.Device) staging.delete()
        }
    }

    suspend fun readText(root: FileManagerRoot, path: String): WorkspaceTextSnapshot = io {
        val location = locateWritable(root, path)
        fileSystem.readTextSnapshot(location.dir, location.path)
    }

    suspend fun writeText(root: FileManagerRoot, path: String, text: String, expectedRevision: String): WorkspaceTextWriteResult = io {
        val location = locateWritable(root, path)
        fileSystem.writeTextWithRevision(location.dir, location.path, text, expectedRevision)
    }

    /**
     * Splits the local attachments of a message being sent: returns the parts to store (each
     * attachment marked as collected) and the parts whose files [collectChatUploads] still has to
     * copy into the library. The mark is stored with the message, so editing or re-sending never
     * collects a file twice; files that were picked from the library are only marked.
     */
    fun markChatUploads(parts: List<UIMessagePart>): Pair<List<UIMessagePart>, List<UIMessagePart>> {
        val pending = mutableListOf<UIMessagePart>()
        val marked = parts.map { part ->
            val url = part.localAttachmentUrl() ?: return@map part
            if (part.metadata?.get(COLLECTED_METADATA_KEY) != null) return@map part
            val name = runCatching { File(URI(url)).name }.getOrNull() ?: return@map part
            if (!isLibraryCopy(name)) pending += part
            part.withMetadata(COLLECTED_METADATA_KEY, JsonPrimitive(true))
        }
        return marked to pending
    }

    /**
     * Copies attachments from [markChatUploads] into `<chat uploads>/<yyyy-MM-dd>/<original name>`
     * of the library (" (n)" on clash). Best effort: a failure is logged and never affects sending.
     */
    suspend fun collectChatUploads(parts: List<UIMessagePart>) = io {
        val upload = uploadDir.canonicalFile
        for (part in parts) {
            try {
                val file = part.localAttachmentUrl()?.let { runCatching { File(URI(it)) }.getOrNull() } ?: continue
                if (file.canonicalFile.parentFile != upload || !file.isFile) continue
                val displayName = (part as? UIMessagePart.Document)?.fileName
                    ?: uploadDisplayName(file.name)
                    ?: file.name
                val dir = File(File(libraryRoot, chatUploadsFolder()), today().toString()).apply { mkdirs() }
                val baseName = runCatching { WorkspaceNames.sanitize(displayName) }.getOrDefault(file.name)
                var target = File(dir, baseName)
                var n = 1
                while (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    target = File(dir, WorkspaceNames.fitConflictName(baseName, n++, isDirectory = false))
                }
                Files.copy(file.toPath(), target.toPath())
            } catch (error: Exception) {
                Log.w(TAG, "collectChatUploads: could not collect an attachment", error)
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun childPath(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun UIMessagePart.localAttachmentUrl(): String? = when (this) {
        is UIMessagePart.Image -> url
        is UIMessagePart.Video -> url
        is UIMessagePart.Audio -> url
        is UIMessagePart.Document -> url
        else -> null
    }?.takeIf { it.startsWith("file:") }

    private fun UIMessagePart.withMetadata(key: String, value: JsonPrimitive): UIMessagePart {
        val metadata = buildJsonObject {
            this@withMetadata.metadata?.forEach { (k, v) -> put(k, v) }
            put(key, value)
        }
        return when (this) {
            is UIMessagePart.Image -> copy(metadata = metadata)
            is UIMessagePart.Video -> copy(metadata = metadata)
            is UIMessagePart.Audio -> copy(metadata = metadata)
            is UIMessagePart.Document -> copy(metadata = metadata)
            else -> this
        }
    }

    companion object {
        private const val TAG = "FileManagerService"
        const val MAX_TEXT_BYTES = 1L * 1024 * 1024
        /** Files can change outside the file manager (workspaces, other apps): re-walk after this. */
        const val INDEX_MAX_AGE_MS = 60_000L
        /** Error code: the workspaces folder or a workspace folder itself was to be changed. */
        const val READ_ONLY_FOLDER = "FILE_MANAGER_READ_ONLY_FOLDER"
        internal const val DEVICE_STAGING_NAME = ".rikkihub-extract"
        /** Part metadata: this attachment has been collected into the library (or came from it). */
        const val COLLECTED_METADATA_KEY = "libraryCollected"
        const val PRIMARY_VOLUME_ID = "primary"

        fun create(context: Context, filesManager: FilesManager, workspaces: () -> List<FileManagerWorkspace>) = FileManagerService(
            libraryRoot = File(context.filesDir, FileFolders.LIBRARY),
            libraryStaging = File(context.filesDir, "library_staging"),
            volumes = { storageVolumes(context) },
            deviceAccess = { deviceAccess(context) },
            uploadDir = filesManager.uploadDir(),
            uploadDisplayName = { name -> filesManager.getByRelativePath("${FileFolders.UPLOAD}/$name")?.displayName },
            chatUploadsFolder = { context.getString(R.string.file_manager_chat_uploads_folder) },
            isLibraryCopy = filesManager::isLibraryCopy,
            workspaces = workspaces,
            workspacesFolderName = { context.getString(R.string.file_manager_workspaces_folder) },
        )

        private fun deviceAccess(context: Context): FileManagerDeviceAccess {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).all {
                    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }
            }
            return if (granted) FileManagerDeviceAccess.READY else FileManagerDeviceAccess.PERMISSION_REQUIRED
        }

        /** Mounted volumes; never a hard-coded `/sdcard`. */
        private fun storageVolumes(context: Context): List<FileManagerVolume> {
            val manager = context.getSystemService(StorageManager::class.java)
            val roots = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED }.mapNotNull { it.directory }
            } else {
                // Before API 30 a volume's directory is only reachable through the app's own dir on it.
                context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
                    dir.path.substringBefore("/Android/data/", missingDelimiterValue = "").takeIf { it.isNotEmpty() }?.let(::File)
                }
            }
            return roots.mapNotNull { dir ->
                val volume = manager.getStorageVolume(dir) ?: return@mapNotNull null
                FileManagerVolume(
                    id = if (volume.isPrimary) PRIMARY_VOLUME_ID else volume.uuid ?: dir.name,
                    label = volume.getDescription(context),
                    primary = volume.isPrimary,
                    directory = dir,
                )
            }.sortedByDescending { it.primary }
        }
    }
}
