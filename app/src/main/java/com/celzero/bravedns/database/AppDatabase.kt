package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


@Database(entities = [AppInfo::class, CategoryInfo::class],version = 2,exportSchema = false)
abstract class AppDatabase : RoomDatabase(){

    companion object {
        const val currentVersion:Int = 2

        @Volatile private var instance: AppDatabase? = null
           private val LOCK = Any()

        operator fun invoke(context: Context) = instance ?: synchronized(LOCK) {
            instance ?: buildDatabase(context).also { instance = it }
        }


        private fun buildDatabase(context: Context) = Room.databaseBuilder(
            context, AppDatabase::class.java,"bravedns.db")
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2)
            .build()

        fun getDatabase(): AppDatabase {
            return instance!!
        }

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DELETE from AppInfo ")
                database.execSQL("CREATE TABLE 'CategoryInfo' ( 'categoryName' TEXT NOT NULL, 'numberOFApps' INTEGER NOT NULL,'numOfAppsBlocked' INTEGER NOT NULL, 'isInternetBlocked' INTEGER NOT NULL, PRIMARY KEY (categoryName)) ")
            }
        }

    }

    abstract fun appInfoDAO(): AppInfoDAO
    abstract fun categoryInfoDAO(): CategoryInfoDAO

    fun appInfoRepository() = AppInfoRepository(appInfoDAO())
    fun categoryInfoRepository() = CategoryInfoRepository(categoryInfoDAO())


}