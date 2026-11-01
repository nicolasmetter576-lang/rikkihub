package me.rerere.rikkihub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkihub.data.db.DatabaseMigrationTracker

val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(26, 27)
        try {
            db.execSQL("ALTER TABLE workspaces ADD COLUMN subagent_model_id TEXT")
            db.execSQL(
                "ALTER TABLE workspaces ADD COLUMN subagent_allow_context INTEGER NOT NULL DEFAULT 0",
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
