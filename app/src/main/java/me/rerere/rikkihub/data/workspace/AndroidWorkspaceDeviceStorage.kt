package me.rerere.rikkihub.data.workspace

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.io.File

/**
 * [WorkspaceDeviceStorage] 的 Android 实现。
 *
 * - API30+ 才支持直连；API26~29 一律 [WorkspaceDeviceAvailability.UNSUPPORTED_API]，
 *   原 `/workspace`/rootfs 功能不受影响（调用方不得在 fallback 走裸宿主路径）。
 * - 授权用 [Environment.isExternalStorageManager] **每次访问真实重检**，不缓存：
 *   用户在系统设置撤权后下一次判定即失效（fail-closed），绝不沿用陈旧结果。
 * - 卷根用 `StorageManager.storageVolumes.first { isPrimary }.directory`，
 *   不硬编码 `/sdcard`/`/storage/emulated/0`（OEM/多用户下路径不固定）。
 */
class AndroidWorkspaceDeviceStorage(
    private val context: Context,
) : WorkspaceDeviceStorage {

    override fun availability(): WorkspaceDeviceAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return WorkspaceDeviceAvailability.UNSUPPORTED_API
        }
        if (!Environment.isExternalStorageManager()) {
            return WorkspaceDeviceAvailability.PERMISSION_REQUIRED
        }
        return if (primaryVolumeDirectory() != null) {
            WorkspaceDeviceAvailability.READY
        } else {
            WorkspaceDeviceAvailability.PRIMARY_VOLUME_UNAVAILABLE
        }
    }

    override fun primaryRoot(): File {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw WorkspaceDeviceStorageException(
                "Device directory access requires Android 11 (API 30) or later",
            )
        }
        return primaryVolumeDirectory()
            ?: throw WorkspaceDeviceStorageException("Primary shared storage volume unavailable")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun primaryVolumeDirectory(): File? {
        val storageManager = context.getSystemService(StorageManager::class.java)
            ?: return null
        return runCatching {
            storageManager.storageVolumes
                .firstOrNull { it.isPrimary }
                ?.directory
        }.getOrNull()
    }

    /**
     * 打开系统「所有文件访问」授权页的 Intent（调用方自行 startActivity；
     * UI 接线不在这里）。优先应用专属设置页 `package:<运行时包名>`（debug/
     * release/coexist 各自包名），系统找不到该页时 fallback 通用所有文件页，
     * 都没有则返回 null 由 UI 提示。授权结果不可信 resultCode，回到前台后
     * 经 [availability] 重检为准。
     */
    fun manageAllFilesAccessIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val appSpecific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (appSpecific.resolveActivity(context.packageManager) != null) return appSpecific
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        if (generic.resolveActivity(context.packageManager) != null) return generic
        // 部分设备两个 ACTION 都查不到 Activity：退回应用详情设置页兜底
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return details.takeIf {
            it.resolveActivity(context.packageManager) != null ||
                context.packageManager.queryIntentActivities(it, PackageManager.MATCH_ALL).isNotEmpty()
        }
    }
}

class WorkspaceDeviceStorageException(message: String) : IllegalStateException(message)
