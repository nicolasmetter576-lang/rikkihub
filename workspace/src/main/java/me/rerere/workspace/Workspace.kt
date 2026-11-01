package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

@kotlinx.serialization.Serializable
enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

@kotlinx.serialization.Serializable
enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

@kotlinx.serialization.Serializable
enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

@kotlinx.serialization.Serializable
data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
)

@kotlinx.serialization.Serializable
data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

@kotlinx.serialization.Serializable
data class WorkspacePathInfo(
    val path: String,
    val exists: Boolean,
    val isDirectory: Boolean,
    val isFile: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

@kotlinx.serialization.Serializable
data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

@kotlinx.serialization.Serializable
data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
