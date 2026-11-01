package me.rerere.rikkihub.data.workspace

import java.io.File

/**
 * 设备目录直连的宿主存储抽象（可注入，便于 JVM 测试）。
 *
 * 语义基准：直连真实主共享存储目录，无 upper/COW/commit/discard；
 * 授权状态不缓存，由实现每次访问真实重检。
 */
interface WorkspaceDeviceStorage {

    /** 平台/授权可用性快照（每次调用实时重检，不缓存）。 */
    fun availability(): WorkspaceDeviceAvailability

    /** 主共享存储卷根（如 primary StorageVolume 的 directory）；不可用时抛异常。 */
    fun primaryRoot(): File
}

enum class WorkspaceDeviceAvailability {
    /** API30+ 且所有文件访问授权有效、主卷可用 */
    READY,

    /** API26~29：直连不受支持；/workspace 等既有功能不受影响 */
    UNSUPPORTED_API,

    /** API30+ 但 MANAGE_EXTERNAL_STORAGE 未授予/被撤销 */
    PERMISSION_REQUIRED,

    /** 授权有效但主共享存储卷目录暂不可用 */
    PRIMARY_VOLUME_UNAVAILABLE,
}
