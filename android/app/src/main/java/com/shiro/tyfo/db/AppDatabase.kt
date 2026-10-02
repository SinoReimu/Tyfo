package com.shiro.tyfo.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        ChatMessageEntity::class,
        GlobalMemoryEntity::class,
        SessionHistoryEntity::class
    ],
    version = 7
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun globalMemoryDao(): GlobalMemoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `global_memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_global_memories_key` ON `global_memories` (`key`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN source TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN remoteChatId TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routines` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `icon` TEXT NOT NULL,
                        `instruction` TEXT NOT NULL,
                        `description` TEXT,
                        `category` TEXT NOT NULL,
                        `isPinned` INTEGER NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE routines ADD COLUMN triggerType TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleMinute INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleRepeatDays TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalMinutes INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalStartHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalEndHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLatitude REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLongitude REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoRadiusMeters REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoTriggerOnEnter INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLocationName TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN lastTriggeredAt INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN nextTriggerAt INTEGER")
            }
        }

        /** 保存的快捷指令 / 定时任务功能已移除，表一起删掉。 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS routines")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_history` (`conversationId` INTEGER NOT NULL, " +
                        "`messages` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`), " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tyfo.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
