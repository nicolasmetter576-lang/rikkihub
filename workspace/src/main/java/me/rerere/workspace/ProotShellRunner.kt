package me.rerere.workspace

import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    /**
     * required = true 时 source 缺失会让 [ProotLauncher.buildCommand] 直接报错,
     * 而不是像可选挂载那样静默跳过 (设备目录用此保证 /device 契约不被 bare 环境吞掉).
     */
    val required: Boolean = false,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
        require(!target.contains(":") && !target.contains('\u0000')) {
            "Bind mount target contains invalid character: $target"
        }
        require(!source.path.contains(":") && !source.path.contains('\u0000')) {
            "Bind mount source contains invalid character: ${source.path}"
        }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!WorkspaceManager.isUsableRootfs(context.linuxDir)) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = ProotLauncher.prootExecutable(nativeLibraryDir)
        val loader = ProotLauncher.prootLoader(nativeLibraryDir)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patchOnce(context.linuxDir)
        val process = ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                if (context.shellCompatibilityMode) {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                } else {
                    environment().remove("PROOT_NO_SECCOMP")
                }
                ProotLauncher.buildProcessEnv(loader, context.tempDir).forEach { entry ->
                    val separator = entry.indexOf('=')
                    environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
                }
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    /**
     * patch 是对同一 rootfs 的幂等修正, 同一 linuxDir 只做一次,
     * 避免每条 shell 命令都重扫一遍 etc/ 下的一组文件
     */
    private fun patchOnce(linuxDir: File) {
        val key = runCatching { linuxDir.canonicalPath }.getOrDefault(linuxDir.absolutePath)
        if (patchedLinuxDirs.add(key)) {
            patcher.patch(linuxDir)
        }
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> = ProotLauncher.buildCommand(
        proot = proot,
        filesDir = context.filesDir,
        linuxDir = context.linuxDir,
        cwd = context.prootCwd(),
        // manager 已把 deviceMounts 合进 bindMounts; 这里再合一次 + distinct
        // 保证直接构造 context 的调用方也不会漏掉 required 的设备挂载
        bindMounts = (context.bindMounts + context.deviceMounts.map { it.toBindMount() }).distinct(),
        workspaceBinds = context.filesScope?.let {
            WorkspaceFilesScope.workspaceBinds(context.filesDir, context.tempDir, it)
        },
        // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
        innerEnv = listOf(
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
        ),
        // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
        command = listOf(
            WorkspaceManager.rootfsShell(context.linuxDir),
            "-l",
            "-c",
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkihub",
            context.prootCwd(),
            context.command,
        ),
    )

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        } else {
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$normalized"
        }
    }

    private companion object {
        private val patchedLinuxDirs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
