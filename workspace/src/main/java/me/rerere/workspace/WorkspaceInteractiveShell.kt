package me.rerere.workspace

import java.io.File

object WorkspaceInteractiveShell {
    const val WORKSPACE_DIR = "/workspace"
    const val SKILLS_DIR = "/skills"

    fun hasRootfs(linuxDir: File): Boolean = WorkspaceManager.isUsableRootfs(linuxDir)

    fun start(
        nativeLibraryDir: File,
        filesDir: File,
        linuxDir: File,
        tempDir: File,
        bindMounts: List<WorkspaceBindMount>,
        rows: Int,
        columns: Int,
        extraEnv: Map<String, String> = emptyMap(),
        patcher: RootfsPatcher = RootfsPatcher(),
        patchOptions: RootfsPatchOptions = RootfsPatchOptions(),
        shellCompatibilityMode: Boolean = false,
        filesScope: String? = null,
    ): WorkspacePtySession {
        require(hasRootfs(linuxDir)) { "Rootfs is not installed" }
        val proot = ProotLauncher.prootExecutable(nativeLibraryDir)
        val loader = ProotLauncher.prootLoader(nativeLibraryDir)
        require(proot.isFile) { "proot executable not found: ${proot.absolutePath}" }
        require(loader.isFile) { "proot loader not found: ${loader.absolutePath}" }

        filesDir.mkdirs()
        tempDir.mkdirs()
        patcher.patch(linuxDir, patchOptions)
        // App 终端、Web 终端、持久 bash 共用：rootfs 里没有 bash 时用 /bin/sh
        val shell = WorkspaceManager.rootfsShell(linuxDir)

        // buildCommand 的 [0] 是 proot 自身路径, execve argv[0] 由 JNI 另行设置
        val args = ProotLauncher.buildCommand(
            proot = proot,
            filesDir = filesDir,
            linuxDir = linuxDir,
            cwd = filesScope?.let(WorkspaceFilesScope::guestPath) ?: WORKSPACE_DIR,
            bindMounts = bindMounts,
            workspaceBinds = filesScope?.let { WorkspaceFilesScope.workspaceBinds(filesDir, tempDir, it) },
            innerEnv = listOf(
                "USER=root",
                "SHELL=$shell",
            ),
            command = listOf(shell),
        ).drop(1)

        val env = ProotLauncher.buildProcessEnv(
            loader = loader,
            tempDir = tempDir,
            extraEnv = extraEnv,
        )

        return WorkspacePtySession(
            WorkspacePtySpec(
                command = proot.absolutePath,
                cwd = filesDir,
                args = args.toTypedArray(),
                env = env.toTypedArray(),
                rows = rows,
                columns = columns,
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )
    }
}
