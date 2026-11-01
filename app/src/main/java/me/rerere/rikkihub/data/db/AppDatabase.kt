package me.rerere.rikkihub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkihub.data.db.dao.ConversationDAO
import me.rerere.rikkihub.data.db.dao.FavoriteDAO
import me.rerere.rikkihub.data.db.dao.FolderDAO
import me.rerere.rikkihub.data.db.dao.GenMediaDAO
import me.rerere.rikkihub.data.db.dao.ManagedFileDAO
import me.rerere.rikkihub.data.db.dao.MediaCreationDAO
import me.rerere.rikkihub.data.db.dao.MemoryConsolidationDAO
import me.rerere.rikkihub.data.db.dao.MemoryDAO
import me.rerere.rikkihub.data.db.dao.MessageNodeDAO
import me.rerere.rikkihub.data.db.dao.AttachmentRetentionDAO
import me.rerere.rikkihub.data.db.dao.OperationReceiptDAO
import me.rerere.rikkihub.data.db.dao.SubagentPresetLibraryDAO
import me.rerere.rikkihub.data.db.dao.TranslationHistoryDAO
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.dao.WorkspaceDeviceMountDAO
import me.rerere.rikkihub.data.db.entity.ConversationEntity
import me.rerere.rikkihub.data.db.entity.FavoriteEntity
import me.rerere.rikkihub.data.db.entity.FolderEntity
import me.rerere.rikkihub.data.db.entity.GenMediaEntity
import me.rerere.rikkihub.data.db.entity.ManagedFileEntity
import me.rerere.rikkihub.data.db.entity.MediaCreationNodeEntity
import me.rerere.rikkihub.data.db.entity.MediaCreationRecordEntity
import me.rerere.rikkihub.data.db.entity.MediaCreationSessionEntity
import me.rerere.rikkihub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkihub.data.db.entity.MemoryFileEntity
import me.rerere.rikkihub.data.db.entity.MessageNodeEntity
import me.rerere.rikkihub.data.db.entity.AttachmentRetentionEntity
import me.rerere.rikkihub.data.db.entity.OperationReceiptEntity
import me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity
import me.rerere.rikkihub.data.db.entity.SubagentPresetLibraryEntity
import me.rerere.rikkihub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.db.migrations.Migration_16_17
import me.rerere.rikkihub.data.db.migrations.Migration_22_23
import me.rerere.rikkihub.data.db.migrations.Migration_8_9
import me.rerere.rikkihub.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryFileEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        WorkspaceDeviceMountEntity::class,
        SubagentPresetLibraryEntity::class,
        FolderEntity::class,
        OperationReceiptEntity::class,
        AttachmentRetentionEntity::class,
        TranslationHistoryEntity::class,
        MediaCreationSessionEntity::class,
        MediaCreationNodeEntity::class,
        MediaCreationRecordEntity::class,
        MemoryConsolidationEntity::class,
    ],
    version = 40,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        AutoMigration(from = 32, to = 33),
        AutoMigration(from = 33, to = 34),
        AutoMigration(from = 34, to = 35),
        AutoMigration(from = 35, to = 36),
        AutoMigration(from = 36, to = 37),
        AutoMigration(from = 37, to = 38),
        AutoMigration(from = 38, to = 39),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun memoryConsolidationDao(): MemoryConsolidationDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun workspaceDeviceMountDao(): WorkspaceDeviceMountDAO

    abstract fun subagentPresetLibraryDao(): SubagentPresetLibraryDAO

    abstract fun folderDao(): FolderDAO

    abstract fun operationReceiptDao(): OperationReceiptDAO

    abstract fun attachmentRetentionDao(): AttachmentRetentionDAO

    abstract fun translationHistoryDao(): TranslationHistoryDAO

    abstract fun mediaCreationDao(): MediaCreationDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
