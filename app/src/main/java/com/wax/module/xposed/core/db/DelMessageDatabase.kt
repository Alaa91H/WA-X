package com.wax.module.xposed.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wax.module.xposed.core.db.dao.DelMessageDao
import com.wax.module.xposed.core.db.entity.DelMessage
import com.wax.module.xposed.utils.Utils

@Database(entities = [DelMessage::class], version = 14, exportSchema = false)
abstract class DelMessageDatabase : RoomDatabase() {
    abstract fun delMessageDao(): DelMessageDao

    companion object {
        @Volatile
        private var instance: DelMessageDatabase? = null

        private val MIGRATION_1_4 =
            object : Migration(1, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    try {
                        db.execSQL("ALTER TABLE delmessages ADD COLUMN timestamp INTEGER DEFAULT 0;")
                    } catch (_: Exception) {
                    }
                }
            }

        private class NoOpMigration(
            startVersion: Int,
            endVersion: Int,
        ) : Migration(startVersion, endVersion) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_4_6 = NoOpMigration(4, 6)
        private val MIGRATION_5_6 = NoOpMigration(5, 6)
        private val MIGRATION_6_7 = NoOpMigration(6, 7)
        private val MIGRATION_7_8 = NoOpMigration(7, 8)
        private val MIGRATION_8_9 = NoOpMigration(8, 9)
        private val MIGRATION_9_10 = NoOpMigration(9, 10)

        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // --- Fix delmessages table ---
                    db.execSQL("DROP TABLE IF EXISTS delmessages_new")
                    db.execSQL(
                        "CREATE TABLE delmessages_new (" +
                            "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "jid TEXT, " +
                            "msgid TEXT, " +
                            "timestamp INTEGER DEFAULT 0)",
                    )
                    db.execSQL(
                        "INSERT INTO delmessages_new (_id, jid, msgid, timestamp) " +
                            "SELECT _id, jid, msgid, timestamp FROM delmessages",
                    )
                    db.execSQL("DROP TABLE delmessages")
                    db.execSQL("ALTER TABLE delmessages_new RENAME TO delmessages")
                    db.execSQL("CREATE UNIQUE INDEX index_delmessages_jid_msgid ON delmessages (jid, msgid)")
                }
            }

        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS deleted_for_me")
                }
            }

        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS idx_delmessages_msgid " +
                            "ON delmessages (msgid)",
                    )
                }
            }

        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Recreate table to ensure _id has NOT NULL constraint
                    // and the unique index on (jid, msgid) exists
                    db.execSQL("DROP TABLE IF EXISTS delmessages_new")
                    db.execSQL(
                        "CREATE TABLE delmessages_new (" +
                            "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "jid TEXT, " +
                            "msgid TEXT, " +
                            "timestamp INTEGER DEFAULT 0)",
                    )
                    db.execSQL(
                        "INSERT OR IGNORE INTO delmessages_new (_id, jid, msgid, timestamp) " +
                            "SELECT _id, jid, msgid, timestamp FROM delmessages",
                    )
                    db.execSQL("DROP TABLE delmessages")
                    db.execSQL("ALTER TABLE delmessages_new RENAME TO delmessages")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_delmessages_jid_msgid ON delmessages (jid, msgid)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_delmessages_msgid ON delmessages (msgid)")
                }
            }

        fun getInstance(context: Context): DelMessageDatabase =
            instance ?: synchronized(this) {
                instance ?: createDatabaseBuilder(context).build().also { instance = it }
            }

        fun resetInstance() {
            synchronized(this) {
                try {
                    instance?.close()
                } catch (_: Exception) {
                }
                instance = null
            }
        }

        private fun createDatabaseBuilder(context: Context): RoomDatabase.Builder<DelMessageDatabase> =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    DelMessageDatabase::class.java,
                    "delmessages.db",
                ).addMigrations(
                    MIGRATION_1_4,
                    MIGRATION_4_6,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                ).setQueryExecutor(Utils.databaseExecutor)
                .setTransactionExecutor(Utils.databaseExecutor)
                .fallbackToDestructiveMigration(true)
    }
}
