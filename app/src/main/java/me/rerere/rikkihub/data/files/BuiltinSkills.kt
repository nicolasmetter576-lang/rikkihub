package me.rerere.rikkihub.data.files

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.ConcurrentHashMap

/** APK assets are published separately; neither user skills nor settings are touched. */
object BuiltinSkills {
    /**
     * App mount namespace 里 `/data/user/0` → `/data/data` 软链使 `filesDir` 的字符串
     * 拼写非 canonical；所有会进挂载表/发布根的路径统一先 canonicalize，否则
     * `canonicalFile == absoluteFile.normalize()` 校验对主用户必炸。
     */
    private fun filesRoot(context: Context): File = context.filesDir.canonicalFile

    /** Defer unsafe/unavailable source rejection to the selected workspace mount table. */
    fun workspaceMountSource(context: Context): File =
        builtinSkillMountDescriptor(filesRoot(context)) { mountDirectory(context) }

    /** A failed update may use the old (or empty, unstamped) safe mount; catalog reads retry. */
    fun mountDirectory(context: Context): File = try {
        ensure(context)
    } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        android.util.Log.w("BuiltinSkills", "Publication failed; retaining retryable mount", failure)
        val root = File(filesRoot(context), FileFolders.BUILTIN_SKILLS)
        require(root.canonicalFile == root.absoluteFile.normalize() && !Files.isSymbolicLink(root.toPath()))
        if (!root.exists()) check(root.mkdir())
        require(root.isDirectory)
        root
    }

    fun ensure(context: Context): File {
        val app = context.applicationContext
        val version = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
        return BuiltinSkillPublisher(filesRoot(app), object : BuiltinSkillAssets {
            override fun children(path: String) = app.assets.list(path).orEmpty().toList()
            override fun open(path: String): InputStream = app.assets.open(path)
        }).ensure(version)
    }
}

/**
 * A bind descriptor is not permission to open it. WorkspaceManager/Proot validate it
 * only after exposure filtering; a broken optional package must not break creation
 * of a workspace whose skills namespace is disabled. Never follow/clean the bad root.
 */
internal fun builtinSkillMountDescriptor(filesRoot: File, prepare: () -> File): File {
    // 只 canonicalize 父根（平台软链父级），descriptor 子路径本身保持词法拼写，
    // 以便挂载表仍能区分「builtin_skills 本身是软链」并做延迟拒绝。
    val descriptor = File(filesRoot.canonicalFile, FileFolders.BUILTIN_SKILLS)
    try {
        prepare()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        android.util.Log.w("BuiltinSkills", "Deferred unavailable builtin mount validation", failure)
    }
    return descriptor
}

/** Small offline seam: assets are byte streams, never decoded/re-encoded as text. */
internal interface BuiltinSkillAssets {
    fun children(path: String): List<String>
    fun open(path: String): InputStream
}

/**
 * Publication is serialized across instances and processes. Stage and backup are uniquely owned,
 * on the same filesystem; the version stamp travels WITH the successfully published tree.
 * Failed preparation/publication leaves the old tree intact and never marks a version checked.
 * The two directory renames are not a filesystem-wide atomic swap. A retained backup can be
 * recovered after interruption; readers in SkillManager also take the in-process lock.
 */
internal class BuiltinSkillPublisher(
    filesDir: File,
    private val assets: BuiltinSkillAssets,
    private val move: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE)
        Unit
    },
) {
    /**
     * 注入的 filesDir 可能带软链父级（`/data/user/0` → `/data/data`），构造时
     * canonicalize 一次：下游 `absoluteFile.normalize() == canonicalFile` 校验
     * 才能成立，且与 SkillManager 读侧（canonical 根）共用同一把进程内锁。
     */
    private val filesDir: File = filesDir.canonicalFile

    companion object {
        private const val STAMP = ".package-stamp"
        private const val OWNER = ".owner"
        private const val PREFIX = ".builtin-skills-update-"
        private const val OWNER_CONTENT = "rikkihub-builtin-skills-v1"
        private val locks = ConcurrentHashMap<String, Any>()
        internal fun <T> locked(root: File, block: () -> T): T =
            synchronized(locks.getOrPut(root.absoluteFile.normalize().path) { Any() }, block)
    }

    fun ensure(version: String): File = locked(filesDir) {
        require(version.isNotBlank() && version.length <= 128 && '\n' !in version)
        require(filesDir.absoluteFile.normalize() == filesDir.canonicalFile) { "Unsafe files root" }
        val root = File(filesDir, FileFolders.BUILTIN_SKILLS)
        val lockFile = File(filesDir, ".builtin-skills.lock")
        requirePlain(lockFile)
        RandomAccessFile(lockFile, "rw").use { file ->
            file.channel.lock().use {
                requirePlain(root)
                recover(root)
                val stamp = File(root, STAMP)
                requirePlain(stamp)
                if (root.isDirectory && stamp.isFile && matchesMetadata(stamp, version)) return@locked root
                val transaction = Files.createTempDirectory(filesDir.toPath(), PREFIX).toFile()
                val stage = File(transaction, "stage")
                val backup = File(transaction, "backup")
                var published = false
                try {
                    writeDurably(File(transaction, OWNER), OWNER_CONTENT.toByteArray())
                    check(stage.mkdir())
                    extract("builtin_skills", stage)
                    writeDurably(File(stage, STAMP), version.toByteArray())
                    requirePlain(root)
                    if (root.exists()) move(root, backup)
                    try {
                        move(stage, root)
                        published = true
                    } catch (failure: Exception) {
                        if (backup.exists() && !root.exists()) {
                            try { move(backup, root) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
                        }
                        throw failure
                    }
                    root
                } finally {
                    // Never destroy the only remaining old tree if rollback also failed.
                    if (published || !backup.exists()) deleteOwnedTree(transaction)
                }
            }
        }
    }

    private fun recover(root: File) {
        val transactions = filesDir.listFiles().orEmpty().filter { it.name.startsWith(PREFIX) }
        for (transaction in transactions.sortedBy { it.name }) {
            // Do not adopt an unowned directory or follow symlinks, including forged owner files.
            if (transaction.canonicalFile != transaction.absoluteFile || !transaction.isDirectory) continue
            val owner = File(transaction, OWNER)
            if (owner.canonicalFile != owner.absoluteFile || !owner.isFile || !matchesMetadata(owner, OWNER_CONTENT)) continue
            val backup = File(transaction, "backup")
            requirePlain(backup)
            if (backup.isDirectory && root.exists() && !hasPublishedStamp(root)) {
                // mountDirectory may have supplied an empty, unstamped fallback after
                // BOTH publication and rollback failed. It is not a replacement for
                // the retained old package: restore it instead of discarding backup.
                if (root.isDirectory && root.listFiles()?.isEmpty() == true) {
                    check(root.delete())
                    move(backup, root)
                } else {
                    // Do not destroy an unknown nonempty fallback or the only old tree.
                    // A later successful publication can make cleanup safe.
                    continue
                }
            }
            if (!root.exists() && backup.isDirectory) move(backup, root)
            if (root.exists() || !backup.exists()) deleteOwnedTree(transaction)
        }
    }

    private fun hasPublishedStamp(root: File): Boolean {
        val stamp = File(root, STAMP)
        return root.isDirectory && stamp.isFile &&
            stamp.canonicalFile == stamp.absoluteFile.normalize() &&
            !Files.isSymbolicLink(stamp.toPath()) && stamp.length() in 1..512
    }

    private fun extract(path: String, directory: File) {
        for (name in assets.children(path)) {
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name)
            val assetPath = "$path/$name"
            val target = File(directory, name)
            requirePlain(target)
            if (assets.children(assetPath).isNotEmpty()) {
                check(target.mkdir())
                extract(assetPath, target)
            } else {
                assets.open(assetPath).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                }
                target.inputStream().use { input ->
                    if (input.read() == '#'.code && input.read() == '!'.code) check(target.setExecutable(true, true))
                }
            }
        }
    }

    private fun requirePlain(file: File) {
        require(file.absoluteFile.normalize() == file.canonicalFile && !Files.isSymbolicLink(file.toPath())) {
            "Unsafe builtin publication path"
        }
    }

    /** Published metadata is mutable through shell binds: never read it without a bound. */
    private fun matchesMetadata(file: File, expected: String): Boolean {
        val bytes = expected.toByteArray(Charsets.UTF_8)
        return file.inputStream().use { input ->
            for (byte in bytes) if (input.read() != (byte.toInt() and 0xff)) return@use false
            input.read() == -1 // at most expected.size + 1 bytes, including growth/oversize
        }
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        requirePlain(file)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

    private fun deleteOwnedTree(root: File) {
        requirePlain(root)
        // walkFileTree does not follow symlinks; deleting a link never deletes its referent.
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return
        Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
}
