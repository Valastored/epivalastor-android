package com.example.epivalastor

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CourseEntity::class, IcsLinkEntity::class, ChangeEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun icsLinkDao(): IcsLinkDao
    abstract fun changeDao(): ChangeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Version 4 → 5 : id pour les liens, eventKey pour les cours, table des changements. Conserve tes agendas. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ics_links_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `name` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL)")
                db.execSQL("INSERT INTO `ics_links_new` (`url`, `name`, `colorArgb`) SELECT `url`, `name`, `colorArgb` FROM `ics_links_table`")
                db.execSQL("DROP TABLE `ics_links_table`")
                db.execSQL("ALTER TABLE `ics_links_new` RENAME TO `ics_links_table`")

                db.execSQL("ALTER TABLE `courses_table` ADD COLUMN `eventKey` TEXT NOT NULL DEFAULT ''")

                db.execSQL("CREATE TABLE IF NOT EXISTS `changes_table` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `icsName` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `changeType` TEXT NOT NULL, `title` TEXT NOT NULL, `oldTitle` TEXT, `oldStartEpoch` INTEGER, `oldEndEpoch` INTEGER, `newStartEpoch` INTEGER, `newEndEpoch` INTEGER, `oldLocation` TEXT, `newLocation` TEXT, `detectedAtEpoch` INTEGER NOT NULL, `seen` INTEGER NOT NULL)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "schedule_database"
                )
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration() // Uniquement pour les très vieilles versions (< 4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
