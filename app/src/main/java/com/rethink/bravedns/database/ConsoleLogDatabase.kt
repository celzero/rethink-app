/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rethinkdns.retrixed.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ConsoleLog::class], version = 2, exportSchema = false)
abstract class ConsoleLogDatabase : RoomDatabase() {
    companion object {
        fun buildDatabase(context: Context): ConsoleLogDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                ConsoleLogDatabase::class.java
            )
                .addMigrations(MIGRATION_1_2)
            .build()
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // set default log level to 3 (INFO)
                database.execSQL("ALTER TABLE ConsoleLog ADD COLUMN level INTEGER DEFAULT 3")
            }
        }
    }

    abstract fun consoleLogDAO(): ConsoleLogDAO

    fun consoleLogRepository() = ConsoleLogRepository(consoleLogDAO())
}
