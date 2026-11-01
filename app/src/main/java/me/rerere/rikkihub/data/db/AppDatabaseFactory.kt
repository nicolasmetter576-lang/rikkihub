package me.rerere.rikkihub.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkihub.data.db.fts.SimpleDictManager
import me.rerere.rikkihub.data.db.fts.createMessageFtsTables
import me.rerere.rikkihub.data.db.migrations.Migration_6_7
import me.rerere.rikkihub.data.db.migrations.Migration_11_12
import me.rerere.rikkihub.data.db.migrations.Migration_13_14
import me.rerere.rikkihub.data.db.migrations.Migration_14_15
import me.rerere.rikkihub.data.db.migrations.Migration_15_16
import me.rerere.rikkihub.data.db.migrations.Migration_25_26
import me.rerere.rikkihub.data.db.migrations.Migration_26_27
import me.rerere.rikkihub.data.db.migrations.Migration_39_40

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_25_26,
                Migration_26_27,
                Migration_39_40,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    createMessageFtsTables(db)
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
