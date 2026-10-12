/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RpnLog::class], version = 1, exportSchema = false)
abstract class RpnLogDatabase : RoomDatabase() {
    companion object {
        fun buildDatabase(context: Context): RpnLogDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                RpnLogDatabase::class.java
            ).build()
        }
    }

    abstract fun rpnLogDAO(): RpnLogDAO

    fun rpnLogRepository() = RpnLogRepository(rpnLogDAO())
}
