package me.rerere.workspace

import java.io.File

/**
 * 统一的 proot 启动参数拼装。交互式 PTY (Web 终端 / 持久 bash) 与非交互命令执行
 * 共用同一套前导参数 / bind mount / 内核伪文件系统挂载 / 环境变量, 避免三处各自漂移。
 */
object ProotLauncher {
    const val PROOT_EXEC = "libproot_exec.so"
    const val PROOT_LOADER = "libproot_loader.so"
    const val PROOT_NO_SECCOMP_ENV = "PROOT_NO_SECCOMP=1"

    private val BASE_INNER_ENV = listOf(
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
    )

    fun prootExecutable(nativeLibraryDir: File): File = File(nativeLibraryDir, PROOT_EXEC)

    fun prootLoader(nativeLibraryDir: File): File = File(nativeLibraryDir, PROOT_LOADER)

    /**
     * 完整命令行: [0] 是 proot 可执行文件路径, 其后是参数。
     * [innerEnv] 追加在 env -i 之后 (如交互 shell 的 USER/SHELL, 或非交互的 CI/NO_COLOR),
     * [command] 是最终执行的 shell 参数 (如 ["/bin/bash"] 或 ["/bin/bash", "-l", "-c", ...])。
     */
    fun buildCommand(
        proot: File,
        filesDir: File,
        linuxDir: File,
        cwd: String = WorkspaceManager.ROOTFS_WORKSPACE_DIR,
        bindMounts: List<WorkspaceBindMount> = emptyList(),
        innerEnv: List<String> = emptyList(),
        command: List<String> = listOf("/bin/bash"),
        /** `/workspace` binds; null = whole FILES area. Scoped: see [WorkspaceFilesScope.workspaceBinds]. */
        workspaceBinds: List<WorkspaceBindMount>? = null,
    ): List<String> {
        val args = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            cwd,
        )
        if (workspaceBinds == null) {
            args += "-b"
            args += "${filesDir.absolutePath}:${WorkspaceManager.ROOTFS_WORKSPACE_DIR}"
        }
        (workspaceBinds.orEmpty() + bindMounts).forEach { mount ->
            if (mount.required) {
                require(mount.source.canonicalFile == mount.source.absoluteFile.normalize()) {
                    "Required bind source changed: ${mount.target}"
                }
            }
            when {
                // Regular files bind too (unavailable device directories use a note file).
                mount.source.isDirectory || mount.source.isFile -> {
                    args += "-b"
                    args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
                }
                mount.required -> error(
                    "Required bind mount source is missing: ${mount.target}"
                )
                // 可选挂载沿用旧行为: source 不存在时静默跳过, 不伤 oldtmp/技能挂载
            }
        }
        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        args += "/usr/bin/env"
        args += "-i"
        args += BASE_INNER_ENV
        args += innerEnv
        args += command
        return args
    }

    /** proot 进程的宿主侧环境变量 (PROOT_* + TMPDIR), 供 ProcessBuilder 或 execve envp 使用 */
    fun buildProcessEnv(
        loader: File,
        tempDir: File,
        extraEnv: Map<String, String> = emptyMap(),
    ): List<String> = buildList {
        add("PROOT_LOADER=${loader.absolutePath}")
        add("PROOT_TMP_DIR=${tempDir.absolutePath}")
        add("TMPDIR=${tempDir.absolutePath}")
        extraEnv.forEach { (key, value) -> add("$key=$value") }
    }
}
