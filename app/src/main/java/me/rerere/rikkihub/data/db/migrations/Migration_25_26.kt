package me.rerere.rikkihub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkihub.data.db.DatabaseMigrationTracker

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(25, 26)
        try {
            db.execSQL(
                "ALTER TABLE workspaces ADD COLUMN expose_upload INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                "ALTER TABLE workspaces ADD COLUMN expose_skills INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                "ALTER TABLE workspaces ADD COLUMN expose_tool_outputs INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                """
                UPDATE workspaces
                SET expose_upload = 0, expose_skills = 0, expose_tool_outputs = 1
                WHERE harness_minimal = 1
                """.trimIndent(),
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
