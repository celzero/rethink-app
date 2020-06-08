package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AppInfo::class],version = 1,exportSchema = false)
abstract class AppDatabase : RoomDatabase(){

    companion object {
        const val currentVersion:Int = 1
        @Volatile private var instance: AppDatabase? = null
           private val LOCK = Any()

        operator fun invoke(context: Context)= instance ?: synchronized(LOCK){
                instance ?: buildDatabase(context).also { instance = it}
        }

            private fun buildDatabase(context: Context) = Room.databaseBuilder(context,
                AppDatabase::class.java, "brave.db")
                .build()

        fun getDatabase(): AppDatabase {
            return instance!!
        }

    }

    abstract fun appInfoDAO(): AppInfoDAO
    //abstract fun dnsRuleDao(): DnsRuleDao

    fun appInfoRepository() =AppInfoRepository(appInfoDAO())

}