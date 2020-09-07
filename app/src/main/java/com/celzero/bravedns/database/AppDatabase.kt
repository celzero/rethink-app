package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


@Database(entities = [AppInfo::class, CategoryInfo::class, ConnectionTracker::class, BlockedConnections::class],version = 3,exportSchema = false)
abstract class AppDatabase : RoomDatabase(){

    companion object {
        const val currentVersion:Int = 3

        @Volatile private var instance: AppDatabase? = null
           private val LOCK = Any()

        operator fun invoke(context: Context) = instance ?: synchronized(LOCK) {
            instance ?: buildDatabase(context).also { instance = it }
        }


        private fun buildDatabase(context: Context) = Room.databaseBuilder(
            context, AppDatabase::class.java,"bravedns.db")
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2)
            .addMigrations(MIGRATION_2_3)
            .build()

        fun getDatabase(): AppDatabase {
            return instance!!
        }

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DELETE from AppInfo")
                database.execSQL("DELETE from CategoryInfo")
                database.execSQL("CREATE TABLE 'CategoryInfo' ( 'categoryName' TEXT NOT NULL, 'numberOFApps' INTEGER NOT NULL,'numOfAppsBlocked' INTEGER NOT NULL, 'isInternetBlocked' INTEGER NOT NULL, PRIMARY KEY (categoryName)) ")
            }
        }


        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DELETE from AppInfo ")
                database.execSQL("DELETE from CategoryInfo")
                database.execSQL("DROP TABLE if exists ConnectionTracker")
                database.execSQL("CREATE TABLE 'ConnectionTracker' ('id' INTEGER NOT NULL,'appName' TEXT, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT, 'port' INTEGER NOT NULL, 'protocol' INTEGER NOT NULL,'isBlocked' INTEGER NOT NULL, 'flag' TEXT, 'timeStamp' INTEGER NOT NULL,PRIMARY KEY (id)  )")
                database.execSQL("CREATE TABLE 'BlockedConnections' ( 'id' INTEGER NOT NULL, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT, 'port' INTEGER NOT NULL, 'protocol' TEXT, PRIMARY KEY (id)) ")
            }
        }

    }

    abstract fun appInfoDAO(): AppInfoDAO
    abstract fun categoryInfoDAO(): CategoryInfoDAO
    abstract fun connectionTrackerDAO() : ConnectionTrackerDAO
    abstract fun blockedConnectionsDAO() : BlockedConnectionsDAO

    fun appInfoRepository() = AppInfoRepository(appInfoDAO())
    fun categoryInfoRepository() = CategoryInfoRepository(categoryInfoDAO())
    fun connectionTrackerRepository() = ConnectionTrackerRepository(connectionTrackerDAO())
    fun blockedConnectionRepository() = BlockedConnectionsRepository(blockedConnectionsDAO())


}