/*
 * Copyright 2023 RethinkDNS and its authors
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
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ConnectionTracker::class, DnsLog::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class LogDatabase : RoomDatabase() {

    companion object {
        private const val LOGS_DATABASE_NAME = "rethink_logs.db"
        private const val PRAGMA = "pragma wal_checkpoint(full)"
        private const val TABLE_NAME_DNS_LOGS = "DnsLogs"
        // previous table name for dns logs
        private const val TABLE_NAME_PREVIOUS_DNS = "DNSLogs"
        private const val TABLE_NAME_CONN_TRACKER = "ConnectionTracker"
        private var rethinkDnsDbPath = ""

        // setJournalMode() is added as part of issue #344
        // modified the journal mode from TRUNCATE to AUTOMATIC.
        // The actual value will be TRUNCATE when the it is a low-RAM device.
        // Otherwise, WRITE_AHEAD_LOGGING will be used.
        // https://developer.android.com/reference/android/arch/persistence/room/RoomDatabase.JournalMode#automatic
        fun buildDatabase(context: Context): LogDatabase {
            rethinkDnsDbPath = context.getDatabasePath(AppDatabase.DATABASE_NAME).toString()
            return Room.databaseBuilder(
                    context.applicationContext,
                    LogDatabase::class.java,
                    LOGS_DATABASE_NAME
                )
                .setJournalMode(JournalMode.AUTOMATIC)
                .addCallback(roomCallback)
                .build()
        }

        private val roomCallback: Callback =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    populateDatabase(db)
                }
            }

        private fun populateDatabase(database: SupportSQLiteDatabase) {
            try {
                database.execSQL("DROP TABLE IF EXISTS ConnectionTracker")
                database.execSQL("DROP TABLE IF EXISTS DnsLogs")
                database.execSQL(
                    "CREATE TABLE 'ConnectionTracker' ('id' INTEGER NOT NULL,'appName' TEXT DEFAULT '' NOT NULL, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT DEFAULT ''  NOT NULL, 'port' INTEGER NOT NULL, 'protocol' INTEGER NOT NULL,'isBlocked' INTEGER NOT NULL, 'blockedByRule' TEXT DEFAULT '' NOT NULL, 'flag' TEXT  DEFAULT '' NOT NULL, 'dnsQuery' TEXT DEFAULT '', 'timeStamp' INTEGER NOT NULL,PRIMARY KEY (id)  )"
                )
                database.execSQL(
                    "CREATE TABLE 'DnsLogs' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'queryStr' TEXT NOT NULL, 'time' INTEGER NOT NULL, 'flag' TEXT NOT NULL, 'resolver' TEXT NOT NULL, 'latency' INTEGER NOT NULL, 'typeName' TEXT NOT NULL, 'isBlocked' INTEGER NOT NULL, 'blockLists' LONGTEXT NOT NULL,  'serverIP' TEXT NOT NULL, 'relayIP' TEXT NOT NULL, 'responseTime' INTEGER NOT NULL, 'response' TEXT NOT NULL, 'status' TEXT NOT NULL,'dnsType' INTEGER NOT NULL, 'responseIps' TEXT NOT NULL) "
                )

                // to avoid the exception, the transaction should be ended before the
                // "attach database" is called.
                // here mainDB is the database which is LogDatabase
                // attach the rethinkDB to the LogDatabase
                database.setTransactionSuccessful()
                database.endTransaction()
                // disable WAL option before attaching the database
                database.disableWriteAheadLogging()
                database.beginTransaction()
                database.execSQL("ATTACH DATABASE '$rethinkDnsDbPath' AS tempDb")
                // delete logs from main database
                database.execSQL("delete from main.$TABLE_NAME_DNS_LOGS")
                database.execSQL("delete from main.$TABLE_NAME_CONN_TRACKER")
                // insert Dns and network logs to the new database tables
                if (tableExists(database, "tempDb.$TABLE_NAME_PREVIOUS_DNS")) {
                    database.execSQL(
                        "INSERT INTO main.$TABLE_NAME_DNS_LOGS SELECT * FROM tempDb.$TABLE_NAME_PREVIOUS_DNS"
                    )
                }
                if (tableExists(database, "tempDb.$TABLE_NAME_CONN_TRACKER")) {
                    database.execSQL(
                        "INSERT INTO main.$TABLE_NAME_CONN_TRACKER SELECT * FROM tempDb.$TABLE_NAME_CONN_TRACKER"
                    )
                }
                database.execSQL("DROP TABLE IF EXISTS tempDb.$TABLE_NAME_PREVIOUS_DNS")
                database.execSQL("DROP TABLE IF EXISTS tempDb.$TABLE_NAME_CONN_TRACKER")
                database.enableWriteAheadLogging()
            } catch (ignored: Exception) {
                Log.e(
                    "MIGRATION",
                    "error migrating from v1to2 on log db: ${ignored.message}",
                    ignored
                )
            }
        }

        private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
            var cursor: Cursor? = null
            return try {
                cursor = db.query("SELECT * FROM $table LIMIT 1")
                cursor.moveToFirst()
                // in the table if it exists, otherwise it will return -1
                cursor.getInt(0) > 0
            } catch (Exp: SQLiteException) {
                // Something went wrong with SQLite. Return false.
                false
            } finally {
                // close the cursor
                cursor?.close()
            }
        }
    }

    fun checkPoint() {
        connectionTrackerDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
        dnsLogDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
    }

    abstract fun connectionTrackerDAO(): ConnectionTrackerDAO
    abstract fun dnsLogDAO(): DnsLogDAO

    fun connectionTrackerRepository() = ConnectionTrackerRepository(connectionTrackerDAO())
    fun dnsLogRepository() = DnsLogRepository(dnsLogDAO())
}
