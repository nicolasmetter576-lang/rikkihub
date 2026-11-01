package me.rerere.rikkihub.data.db.fts

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object SimpleDictManager {

    private const val DICT_ASSET_DIR = "simple_dict"
    private const val VERSION_FILE = "version.txt"

    // 与 assets 中的词典版本对齐，更新词典时递增此值
    private const val CURRENT_VERSION = 1

    private val LOCK = Any()

    /**
     * 将 assets/simple_dict 解压到 files/simple_dict，返回词典目录。
     * 已是最新版本时直接返回，无需重复拷贝。
     * 可在任意线程调用；DB onOpen 可能并发触发，解压全程串行且先落临时目录再原子替换。
     */
    fun extractDict(context: Context): File {
        val assets = object : DictAssetSource {
            override fun children(path: String): List<String> =
                context.assets.list(path).orEmpty().toList()

            override fun open(path: String): InputStream = context.assets.open(path)
        }
        return extractTo(context.filesDir, DICT_ASSET_DIR, CURRENT_VERSION, assets)
    }

    /**
     * 核心解压逻辑（JVM 可测）：version 已是最新直接返回；否则解压进同目录的
     * 临时目录，最后写 version 作提交标记，然后原子 rename 替换目标。
     * rename 不可用时回退直拷。任何一步失败都不留下「版本已是最新但内容残缺」
     * 的假象——version.txt 永远最后写。
     */
    internal fun extractTo(
        filesDir: File,
        assetDir: String,
        currentVersion: Int,
        assets: DictAssetSource,
    ): File = synchronized(LOCK) {
        val destDir = File(filesDir, assetDir)
        val versionFile = File(destDir, VERSION_FILE)

        if (versionFile.isFile &&
            versionFile.readText().trim().toIntOrNull() == currentVersion
        ) {
            return destDir
        }

        val tmpDir = File(filesDir, ".$assetDir.tmp").apply {
            deleteRecursively()
            if (!mkdirs()) throw java.io.IOException("Cannot create $absolutePath")
        }
        try {
            copyAssetDir(assets, assetDir, tmpDir)
            // 提交标记最后写：崩溃/中断留下的目录没有最新 version，下次重解压
            File(tmpDir, VERSION_FILE).writeText(currentVersion.toString())
            destDir.deleteRecursively()
            val renamed = try {
                Files.move(
                    tmpDir.toPath(),
                    destDir.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
                true
            } catch (e: Exception) {
                false // 某些文件系统不支持 ATOMIC_MOVE，回退直拷
            }
            if (!renamed) {
                if (!destDir.mkdirs() && !destDir.isDirectory) {
                    throw java.io.IOException("Cannot create ${destDir.absolutePath}")
                }
                copyAssetDir(assets, assetDir, destDir)
                File(destDir, VERSION_FILE).writeText(currentVersion.toString())
            }
            destDir
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun copyAssetDir(assets: DictAssetSource, assetPath: String, destDir: File) {
        val children = assets.children(assetPath)
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val destFile = File(destDir, name)
            val grandchildren = assets.children(childAsset)
            if (grandchildren.isNotEmpty()) {
                destFile.mkdirs()
                copyAssetDir(assets, childAsset, destFile)
            } else {
                assets.open(childAsset).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

/** assets 的 JVM 可测抽象：list + open 两个原语即可撑起递归拷贝。 */
internal interface DictAssetSource {
    fun children(path: String): List<String>
    fun open(path: String): InputStream
}
