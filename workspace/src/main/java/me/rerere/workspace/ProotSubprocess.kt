package me.rerere.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A long-lived program inside the workspace rootfs on three plain pipes (no PTY: no echo, no
 * line discipline, stderr kept apart), e.g. an ACP agent speaking JSON-RPC on stdio.
 * [command] runs through the rootfs login shell, so `~/.profile` PATH additions apply, with
 * `~/.local/bin` prepended; anything the profile prints goes to stderr, never to stdout.
 */
data class WorkspaceSubprocessRequest(
    val command: List<String>,
    /** Guest working directory; null = the files scope root, or `/workspace`. */
    val cwd: String? = null,
    /** Extra variables inside the container (after the fixed non-interactive ones). */
    val env: Map<String, String> = emptyMap(),
    val mounts: WorkspaceMountSelection = WorkspaceMountSelection(),
    val shellCompatibilityMode: Boolean = false,
    val patchOptions: RootfsPatchOptionsSpec = RootfsPatchOptionsSpec(),
) {
    init {
        require(command.isNotEmpty()) { "Subprocess command is required" }
    }
}

/**
 * A running [WorkspaceSubprocessRequest]. One waiter thread reaps the process; [close] is the
 * staged shutdown: stdin EOF, then SIGTERM after [TERM_AFTER_MS], then SIGKILL after [KILL_AFTER_MS].
 */
class WorkspaceSubprocess(private val process: Process) : AutoCloseable {
    val stdin: OutputStream get() = process.outputStream
    val stdout: InputStream get() = process.inputStream
    val stderr: InputStream get() = process.errorStream

    private val exitSignal = CompletableDeferred<Int>()
    private val closing = AtomicBoolean(false)

    @Volatile
    var exitCode: Int? = null
        private set

    val isAlive: Boolean get() = exitCode == null

    init {
        thread(name = "workspace-subprocess-wait", isDaemon = true) {
            val code = process.waitFor()
            exitCode = code
            exitSignal.complete(code)
        }
    }

    suspend fun awaitExit(): Int = exitSignal.await()

    /** True once the process has exited (it is not killed here; call [close] first). */
    suspend fun awaitClosed(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { exitSignal.await() } != null

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        runCatching { process.outputStream.close() }
        if (exitCode != null) return
        thread(name = "workspace-subprocess-close", isDaemon = true) {
            if (process.waitFor(TERM_AFTER_MS, TimeUnit.MILLISECONDS)) return@thread
            process.destroy()
            if (process.waitFor(KILL_AFTER_MS - TERM_AFTER_MS, TimeUnit.MILLISECONDS)) return@thread
            process.killForcibly()
        }
    }

    companion object {
        const val TERM_AFTER_MS = 500L
        const val KILL_AFTER_MS = 3_000L

        /**
         * `sh -c` (no profile) parks the clean stdout on fd 3 and points fd 1 at stderr, then
         * execs the login shell, so profile output lands on stderr; the login shell restores
         * fd 1 and execs the program. The program and its arguments travel as positional
         * parameters, never through shell quoting.
         */
        internal const val LAUNCH_SCRIPT =
            "sh_=\$1; shift; exec 3>&1 1>&2; exec \"\$sh_\" -lc " +
                "'export PATH=\"\$HOME/.local/bin:\$PATH\"; exec 1>&3 3>&-; exec \"\$@\"' rikkihub \"\$@\""

        /** argv that runs [command] through [loginShell] as described in [LAUNCH_SCRIPT]. */
        fun launchCommand(loginShell: String, command: List<String>): List<String> =
            listOf("/bin/sh", "-c", LAUNCH_SCRIPT, "sh", loginShell) + command

        // Fixed variables for a program driven over pipes: no colours, pager or prompts.
        private val NON_INTERACTIVE_ENV = listOf("TERM=dumb", "NO_COLOR=1", "PAGER=cat", "CI=true")

        internal fun start(
            nativeLibraryDir: File,
            filesDir: File,
            linuxDir: File,
            tempDir: File,
            bindMounts: List<WorkspaceBindMount>,
            request: WorkspaceSubprocessRequest,
            patcher: RootfsPatcher,
        ): WorkspaceSubprocess {
            require(WorkspaceManager.isUsableRootfs(linuxDir)) { "Rootfs is not installed" }
            val proot = ProotLauncher.prootExecutable(nativeLibraryDir)
            val loader = ProotLauncher.prootLoader(nativeLibraryDir)
            require(proot.isFile) { "proot executable not found: ${proot.absolutePath}" }
            require(loader.isFile) { "proot loader not found: ${loader.absolutePath}" }

            filesDir.mkdirs()
            tempDir.mkdirs()
            patcher.patch(linuxDir, RootfsPatchOptions(nameservers = request.patchOptions.nameservers.take(8)))
            val shell = WorkspaceManager.rootfsShell(linuxDir)
            val scope = request.mounts.filesScope
            val args = ProotLauncher.buildCommand(
                proot = proot,
                filesDir = filesDir,
                linuxDir = linuxDir,
                cwd = request.cwd ?: scope?.let(WorkspaceFilesScope::guestPath) ?: WorkspaceManager.ROOTFS_WORKSPACE_DIR,
                bindMounts = bindMounts,
                workspaceBinds = scope?.let { WorkspaceFilesScope.workspaceBinds(filesDir, tempDir, it) },
                innerEnv = listOf("USER=root", "SHELL=$shell") + NON_INTERACTIVE_ENV +
                    request.env.map { (key, value) -> "$key=$value" },
                command = launchCommand(shell, request.command),
            )
            val process = ProcessBuilder(args)
                .directory(filesDir)
                .redirectErrorStream(false)
                .apply {
                    if (request.shellCompatibilityMode) {
                        environment()["PROOT_NO_SECCOMP"] = "1"
                    } else {
                        environment().remove("PROOT_NO_SECCOMP")
                    }
                    ProotLauncher.buildProcessEnv(loader, tempDir).forEach { entry ->
                        val separator = entry.indexOf('=')
                        environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
                    }
                }
                .start()
            return WorkspaceSubprocess(process)
        }
    }
}
