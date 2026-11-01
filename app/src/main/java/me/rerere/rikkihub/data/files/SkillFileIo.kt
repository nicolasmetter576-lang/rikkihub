package me.rerere.rikkihub.data.files

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 技能根目录下"可列出的技能目录"。
 *
 * 排除隐藏目录（`createTempSkillDir` 产生的 `.<name>.staging/backup.*.tmp`
 * 残留），避免被中断的原子写入过程把半成品目录当成可用技能列出。
 */
internal fun listSkillDirs(root: File): List<File> =
    root.listFiles()
        ?.filter {
            it.isDirectory && !it.name.startsWith(".") &&
                it.canonicalFile == it.absoluteFile.normalize() &&
                !Files.isSymbolicLink(it.toPath())
        }
        ?: emptyList()

/**
 * 把文本内容原子写入 [target]：同目录独占临时文件 + fsync + rename 替换。
 * 写出失败或取消均不触碰原文件；取消经 [ensureActive] 正常传播。
 */
internal suspend fun writeSkillFileTextAtomically(target: File, content: String): Boolean =
    writeSkillFileBytesAtomically(target, content.toByteArray())

internal suspend fun writeSkillFileBytesAtomically(target: File, content: ByteArray): Boolean =
    runAtomicFileReplace(target) { temp ->
        FileOutputStream(temp).use { out ->
            out.write(content)
            out.fd.sync() // 先 fsync 再 rename，避免替换后落盘内容不完整
        }
    }

/**
 * 原子替换核心：[File.createTempFile] 生成独占唯一临时文件（并发保存同一目标
 * 互不截断，也绝不误写/误删其他任务预留的固定名临时文件或软链），写出后
 * ensureActive 校验取消，再 rename 覆盖。
 *
 * IO 异常返回 false；[kotlin.coroutines.CancellationException] 原样向上传播，
 * 调用方不得把取消伪装成普通保存失败。finally 只清理本次自己创建的临时文件。
 */
internal suspend fun runAtomicFileReplace(target: File, write: (File) -> Unit): Boolean {
    val parent = target.parentFile ?: return false
    var tempFile: File? = null
    return try {
        if (!parent.exists() && !parent.mkdirs()) return false
        currentCoroutineContext().ensureActive()
        val created = File.createTempFile(".${target.name}-", null, parent)
        tempFile = created
        write(created)
        currentCoroutineContext().ensureActive()
        Files.move(
            created.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    } finally {
        tempFile?.let { if (it.exists()) it.delete() }
    }
}
