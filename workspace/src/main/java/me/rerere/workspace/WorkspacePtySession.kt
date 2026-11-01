package me.rerere.workspace

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class WorkspacePtySpec(
    val command: String,
    val cwd: File,
    val args: Array<String>,
    val env: Array<String>,
    val rows: Int = 24,
    val columns: Int = 80,
    val shellCompatibilityMode: Boolean = false,
)

class WorkspacePtySession(
    spec: WorkspacePtySpec,
) : WorkspacePty {
    private val pidHolder = IntArray(1)
    private val masterFd: Int = WorkspacePtyJni.createSubprocess(
        spec.command,
        spec.cwd.absolutePath,
        spec.args,
        if (spec.shellCompatibilityMode) {
            spec.env + ProotLauncher.PROOT_NO_SECCOMP_ENV
        } else {
            spec.env
        },
        pidHolder,
        spec.rows,
        spec.columns,
    )
    val pid: Int = pidHolder[0]
    private val master: ParcelFileDescriptor
    private val reader: ParcelFileDescriptor
    private val writer: ParcelFileDescriptor
    private val input: FileInputStream
    private val output: FileOutputStream
    private val closed = AtomicBoolean(false)

    /**
     * 唯一 waiter: JNI waitFor 只在线程里被调用一次, 结果经 [exitSignal] 广播给
     * 所有 awaiter —— 不二次 waitpid (二次 waitpid 会 ECHILD/误报退出码).
     */
    private val exitSignal = CompletableDeferred<Int>()
    @Volatile
    override var exitCode: Int? = null
        private set

    init {
        require(masterFd >= 0) { "Failed to create workspace PTY" }
        require(pid > 0) { "Failed to spawn workspace shell" }
        master = ParcelFileDescriptor.adoptFd(masterFd)
        reader = ParcelFileDescriptor.fromFd(master.fd)
        writer = ParcelFileDescriptor.fromFd(master.fd)
        input = FileInputStream(reader.fileDescriptor)
        output = FileOutputStream(writer.fileDescriptor)
        thread(name = "workspace-pty-wait-$pid", isDaemon = true) {
            val code = WorkspacePtyJni.waitFor(pid)
            exitCode = code
            exitSignal.complete(code)
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (closed.get() || bytes.isEmpty()) return@withContext
        output.write(bytes)
        output.flush()
    }

    override fun available(): Int {
        if (closed.get()) return 0
        return try {
            input.available()
        } catch (_: Exception) {
            0
        }
    }

    override fun read(buffer: ByteArray): Int {
        if (closed.get()) return -1
        return try {
            input.read(buffer)
        } catch (_: Exception) {
            -1
        }
    }

    override fun resize(rows: Int, columns: Int) {
        if (closed.get()) return
        WorkspacePtyJni.setPtyWindowSize(
            fd = master.fd,
            rows = rows.coerceAtLeast(1),
            columns = columns.coerceAtLeast(1),
        )
    }

    /**
     * suspend 出口: 等待唯一 waiter 线程回填退出码. 永不二次 waitpid;
     * 已 close 后仍可 await (close 只发信号, 进程退出由 waiter 观察).
     */
    override suspend fun awaitExit(): Int = exitSignal.await()

    /**
     * 给用户 terminal/gate 的 awaitClosed 回调: close 后等待进程真正退出,
     * 最多 [timeoutMillis]; 超时返回 false（不宣称进程已死, 由调用方决定）.
     */
    override suspend fun awaitClosed(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { exitSignal.await() } != null

    /** Duplicate of the PTY master for handing the terminal to the host process. */
    fun dupMaster(): ParcelFileDescriptor = master.dup()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (pid > 0) {
            runCatching { Os.kill(pid, OsConstants.SIGTERM) }
        }
        runCatching { reader.close() }
        runCatching { writer.close() }
        runCatching { master.close() }
        // SIGTERM 只是尽力而为: 若进程组里有忽略 TERM 的残留进程, 宽限期后补 SIGKILL,
        // 避免 shell 退出不干净导致 waitFor 线程永久悬挂
        if (pid > 0 && exitCode == null) {
            Thread.sleep(SIGKILL_GRACE_MS)
            if (exitCode == null) {
                runCatching { Os.kill(pid, OsConstants.SIGKILL) }
            }
        }
    }

    private companion object {
        private const val SIGKILL_GRACE_MS = 1_000L
        private const val AWAIT_CLOSED_TIMEOUT_MS = 5_000L
    }
}

/**
 * 通用「已退出」栅栏：单一 native/callback waiter 调 [signalExit]，所有 suspend awaiter
 * 挂起在同一 deferred 上 —— awaiter 自己永不 waitpid（App Termux 通道的 waiter 是
 * libtermux 的 waitFor 线程经 onSessionFinished 回调；PTY 通道是 wait-pid 守护线程）。
 * [signalExit] 幂等：第二次调用返回 false，测试据此证明唯一 waiter。
 */
class WorkspaceExitBarrier {
    private val signal = CompletableDeferred<Unit>()

    /** 进程已退出信号：唯一 waiter 调一次；重复调用返回 false（不覆盖已完成状态）。 */
    fun signalExit(): Boolean = signal.complete(Unit)

    /** 已收到退出信号（先于任何 awaiter 注册也安全）。 */
    val exited: Boolean get() = signal.isCompleted

    /** 等待唯一 waiter 回填退出；超时返回 false —— 由调用方决定（不宣称已退出）。 */
    suspend fun awaitClosed(timeoutMillis: Long): Boolean =
        exited || withTimeoutOrNull(timeoutMillis) { signal.await() } != null

    /** 无界等待退出信号（drain 协程专用；record 只在 true exit 后被 complete）。 */
    suspend fun awaitFinished() {
        signal.await()
    }
}

/**
 * 「已请求 close、尚未证明退出」会话登记处：同步 close 从活跃表移除会话时必须把
 * 对象放进这里，否则后到的 awaiter 会丢 join 对象（看不到要等的进程）。
 * 只有唯一 waiter 证明了退出（或 retire 全链路 close+await 成功）才 [complete]。
 * 纯内存结构、synchronized 串行，JVM 可测。
 */
class WorkspacePendingCloses<H> {
    private val pending = LinkedHashSet<H>()

    /** 与活跃表 remove 同一把锁内调用，保证 snapshot(pending) 不漏对象。 */
    fun record(handle: H) {
        synchronized(pending) { pending += handle }
    }

    /** 退出被证明后移除；并发 closeWorkspaceAndAwait 已快照到的条目 retire 幂等。 */
    fun complete(handle: H) {
        synchronized(pending) { pending -= handle }
    }

    fun pending(): List<H> = synchronized(pending) { pending.toList() }

    fun isEmpty(): Boolean = synchronized(pending) { pending.isEmpty() }
}
