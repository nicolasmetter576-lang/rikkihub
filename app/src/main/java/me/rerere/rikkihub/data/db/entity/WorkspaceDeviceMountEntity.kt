package me.rerere.rikkihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 工作区设备目录连接（直连写，无 upper/COW/commit/discard）。
 *
 * - [sourceRelativePath] 是相对主共享存储卷根的相对路径（不含卷根前缀、不含
 *   `..`/绝对路径），由 `WorkspaceDeviceStorage.primaryRoot()` +
 *   `WorkspaceDeviceDirectoryGuard` 校验解析；不持久化任意宿主绝对路径。
 * - FK `workspace_id -> workspaces.id ON DELETE CASCADE`：父工作区删除时
 *   连接配置随之删除（只删配置，绝不删宿主源目录）。
 * - `UNIQUE(workspace_id, alias)`：别名在工作区内唯一。
 * - 只存配置意图；「本安装是否已激活授权」在
 *   `noBackupFilesDir` 的 activation store（不进备份）。
 */
@Entity(
    tableName = "workspace_device_mounts",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspace_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["workspace_id"]),
        Index(value = ["workspace_id", "alias"], unique = true),
    ],
)
data class WorkspaceDeviceMountEntity(
    /** 稳定连接 id（UUID 字符串） */
    @PrimaryKey
    val id: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    /** guest `/device/<alias>` 别名，`^[a-z][a-z0-9_-]{0,31}$` */
    @ColumnInfo("alias")
    val alias: String,
    /** 主共享存储卷根下的相对 source 目录（受控，非任意宿主路径） */
    @ColumnInfo("source_relative_path")
    val sourceRelativePath: String,
    /** 配置意图开关；运行态还需授权/激活/目录校验通过 */
    @ColumnInfo("enabled", defaultValue = "1")
    val enabled: Boolean = true,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
