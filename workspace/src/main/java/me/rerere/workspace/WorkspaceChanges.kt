package me.rerere.workspace

import java.io.File

/** 交给 [WorkspaceChanges.observer] 的一次文件改动；路径都是绝对路径。 */
sealed interface WorkspaceChange {
    /** 已存在的 [file] 马上要被 [content] 整个换掉（[content] 按需才读）。 */
    class Overwrite(val file: File, val content: () -> ByteArray) : WorkspaceChange

    /** [Overwrite] 之后，[file] 已经整个换成了 [content]（写成功才发；写失败就没有这一条）。 */
    class Written(val file: File, val content: () -> ByteArray) : WorkspaceChange

    /** 新文件 [file] 刚建好，内容是 [content]（写成功才发；只管新建的文件，覆盖已有文件走 [Overwrite]、[Written]）。 */
    class Created(val file: File, val content: () -> ByteArray) : WorkspaceChange

    /** [file]（文件或目录）马上要被删；观察者可以先把里面的文件移走。 */
    class Delete(val file: File) : WorkspaceChange

    /** [from] 马上要移到 [to]（文件或目录，可能跨存储根）；目标被覆盖时在 [Delete] 目标之前发。 */
    class Moving(val from: File, val to: File) : WorkspaceChange

    /** [from] 刚被移到了 [to]（文件或目录，可能跨存储根）。 */
    class Moved(val from: File, val to: File) : WorkspaceChange
}

/**
 * 观察者拒绝一次写入（作品的定稿锁）：带固定错误码 [FILE_LOCKED]，各层原样交给调用方，
 * 由调用方给出专门的说明。
 */
open class WorkspaceLockedException(message: String) : WorkspaceFileOperationException(FILE_LOCKED, message)

/**
 * 进程级的写入观察口（作品版本史与定稿锁用），App 启动时设置。
 *
 * 观察者在写入方持有的存储根锁里同步运行：不能再调会加锁的文件 API（[WorkspaceFileSystem] 等），
 * 调了立刻报错，而不是卡死在不可重入的锁上。写前的通知（[WorkspaceChange.Overwrite]、[WorkspaceChange.Delete]、
 * [WorkspaceChange.Moving]）里抛出的异常会让这次写入失败；写后的通知（[WorkspaceChange.Written]、
 * [WorkspaceChange.Created]、[WorkspaceChange.Moved]）里的异常被吞掉，因为写入已经成功了。
 */
object WorkspaceChanges {
    @Volatile
    var observer: ((WorkspaceChange) -> Unit)? = null

    private val notifying = ThreadLocal<Boolean>()

    internal fun notify(change: WorkspaceChange) {
        val observer = observer ?: return
        guarding { observer(change) }
    }

    /** 写入已经成功之后的通知：观察者出错不影响这次写入。 */
    internal fun notifyDone(change: WorkspaceChange) {
        runCatching { notify(change) }
    }

    /** 在 [block] 里调会加锁的文件 API 立刻报错（存储根锁不可重入）。 */
    internal fun <T> guarding(block: () -> T): T {
        val outer = notifying.get() == true
        notifying.set(true)
        try {
            return block()
        } finally {
            if (!outer) notifying.remove()
        }
    }

    internal fun checkNotNotifying() {
        check(notifying.get() != true) { "A workspace change observer must not call locking file APIs" }
    }
}
