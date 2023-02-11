/*
 * Copyright 2020 RethinkDNS and its authors
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
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.celzero.bravedns.util.Constants

@Database(
    entities =
        [
            AppInfo::class,
            CustomIp::class,
            DoHEndpoint::class,
            DnsCryptEndpoint::class,
            DnsProxyEndpoint::class,
            DnsCryptRelayEndpoint::class,
            ProxyEndpoint::class,
            CustomDomain::class,
            RethinkDnsEndpoint::class,
            RethinkRemoteFileTag::class,
            RethinkLocalFileTag::class,
            LocalBlocklistPacksMap::class,
            RemoteBlocklistPacksMap::class
        ],
    version = 17,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        const val DATABASE_NAME = "bravedns.db"
        private const val DATABASE_PATH = "database/rethink_v16.db"
        private const val PRAGMA = "pragma wal_checkpoint(full)"

        // setJournalMode() is added as part of issue #344
        // modified the journal mode from TRUNCATE to AUTOMATIC.
        // The actual value will be TRUNCATE when the it is a low-RAM device.
        // Otherwise, WRITE_AHEAD_LOGGING will be used.
        // Ref:
        // https://developer.android.com/reference/android/arch/persistence/room/RoomDatabase.JournalMode#automatic
        fun buildDatabase(context: Context) =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                .createFromAsset(DATABASE_PATH)
                .setJournalMode(JournalMode.AUTOMATIC)
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .addMigrations(MIGRATION_9_10)
                .addMigrations(MIGRATION_10_11)
                .addMigrations(MIGRATION_11_12)
                .addMigrations(MIGRATION_12_13)
                .addMigrations(MIGRATION_13_14)
                .addMigrations(MIGRATION_14_15)
                .addMigrations(MIGRATION_15_16)
                .addMigrations(MIGRATION_16_17)
                .build()

        private val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("DELETE from AppInfo")
                    database.execSQL("DELETE from CategoryInfo")
                    database.execSQL(
                        "CREATE TABLE 'CategoryInfo' ( 'categoryName' TEXT NOT NULL, 'numberOFApps' INTEGER NOT NULL,'numOfAppsBlocked' INTEGER NOT NULL, 'isInternetBlocked' INTEGER NOT NULL, PRIMARY KEY (categoryName)) "
                    )
                }
            }

        private val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("DELETE from AppInfo ")
                    database.execSQL("DELETE from CategoryInfo")
                    database.execSQL("DROP TABLE if exists ConnectionTracker")
                    database.execSQL(
                        "CREATE TABLE 'ConnectionTracker' ('id' INTEGER NOT NULL,'appName' TEXT, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT, 'port' INTEGER NOT NULL, 'protocol' INTEGER NOT NULL,'isBlocked' INTEGER NOT NULL, 'flag' TEXT, 'timeStamp' INTEGER NOT NULL,PRIMARY KEY (id)  )"
                    )
                    database.execSQL(
                        "CREATE TABLE 'BlockedConnections' ( 'id' INTEGER NOT NULL, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT, 'port' INTEGER NOT NULL, 'protocol' TEXT, PRIMARY KEY (id)) "
                    )
                }
            }

        private val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE BlockedConnections ADD COLUMN isActive INTEGER DEFAULT 1 NOT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE BlockedConnections ADD COLUMN ruleType TEXT DEFAULT 'RULE4' NOT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE BlockedConnections ADD COLUMN modifiedDateTime INTEGER DEFAULT 0  NOT NULL"
                    )
                    database.execSQL(
                        "UPDATE BlockedConnections set ruleType = 'RULE5' where uid = -1000"
                    )
                    database.execSQL("ALTER TABLE ConnectionTracker ADD COLUMN blockedByRule TEXT")
                    database.execSQL(
                        "UPDATE ConnectionTracker set blockedByRule = 'RULE4' where uid <> -1000 and isBlocked = 1"
                    )
                    database.execSQL(
                        "UPDATE ConnectionTracker set blockedByRule = 'RULE5' where uid = -1000  and isBlocked = 1"
                    )
                    database.execSQL(
                        "ALTER TABLE AppInfo add column whiteListUniv1 INTEGER DEFAULT 0 NOT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE AppInfo add column whiteListUniv2 INTEGER DEFAULT 0 NOT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE AppInfo add column isExcluded INTEGER DEFAULT 0 NOT NULL"
                    )
                    database.execSQL(
                        "CREATE TABLE 'DoHEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dohName' TEXT NOT NULL, 'dohURL' TEXT NOT NULL,'dohExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    database.execSQL(
                        "CREATE TABLE 'DNSCryptEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dnsCryptName' TEXT NOT NULL, 'dnsCryptURL' TEXT NOT NULL,'dnsCryptExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    database.execSQL(
                        "CREATE TABLE 'DNSCryptRelayEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dnsCryptRelayName' TEXT NOT NULL, 'dnsCryptRelayURL' TEXT NOT NULL,'dnsCryptRelayExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    database.execSQL(
                        "CREATE TABLE 'DNSProxyEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'proxyName' TEXT NOT NULL, 'proxyType' TEXT NOT NULL,'proxyAppName' TEXT , 'proxyIP' TEXT, 'proxyPort' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    database.execSQL(
                        "CREATE TABLE 'ProxyEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'proxyName' TEXT NOT NULL,'proxyMode' INTEGER NOT NULL, 'proxyType' TEXT NOT NULL,'proxyAppName' TEXT , 'proxyIP' TEXT, 'userName' TEXT , 'password' TEXT, 'proxyPort' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL , 'isUDP' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    // Perform insert of endpoints
                    database.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(1,'Cloudflare','https://cloudflare-dns.com/dns-query','Does not block any DNS requests. Uses Cloudflare''s 1.1.1.1 DNS endpoint.',0,0,0,0)"
                    )
                    database.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(2,'Cloudflare Family','https://family.cloudflare-dns.com/dns-query','Blocks malware and adult content. Uses Cloudflare''s 1.1.1.3 DNS endpoint.',0,0,0,0)"
                    )
                    database.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(3,'Cloudflare Security','https://security.cloudflare-dns.com/dns-query','Blocks malicious content. Uses Cloudflare''s 1.1.1.2 DNS endpoint.',0,0,0,0)"
                    )
                    database.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(4,'RethinkDNS Basic (default)','https://basic.bravedns.com/1:YBcgAIAQIAAIAABgIAA=','Blocks malware and more. Uses RethinkDNS''s non-configurable basic endpoint.',1,0,0,0)"
                    )
                    database.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(5,'RethinkDNS Plus','https://basic.bravedns.com/','Configurable DNS endpoint: Provides in-depth analytics of your Internet traffic, allows you to set custom rules and more.',0,0,0,0)"
                    )
                }
            }

        private val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("DELETE from DNSProxyEndpoint")
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:wBdgAIoBoB02kIAA5HI=' where id = 4"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName='Quad9', dnsCryptURL='sdns://AQYAAAAAAAAAEzE0OS4xMTIuMTEyLjEwOjg0NDMgZ8hHuMh1jNEgJFVDvnVnRt803x2EwAuMRwNo34Idhj4ZMi5kbnNjcnlwdC1jZXJ0LnF1YWQ5Lm5ldA',dnsCryptExplanation='Quad9 (anycast) no-dnssec/no-log/no-filter 9.9.9.10 / 149.112.112.10' where id=5"
                    )
                    database.execSQL(
                        "INSERT into DNSProxyEndpoint values (1,'Google','External','Nobody','8.8.8.8',53,0,0,0,0)"
                    )
                    database.execSQL(
                        "INSERT into DNSProxyEndpoint values (2,'Cloudflare','External','Nobody','1.1.1.1',53,0,0,0,0)"
                    )
                    database.execSQL(
                        "INSERT into DNSProxyEndpoint values (3,'Quad9','External','Nobody','9.9.9.9',53,0,0,0,0)"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName ='Cleanbrowsing Family' where id = 1"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName ='Adguard' where id = 2"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName ='Adguard Family' where id = 3"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName ='Cleanbrowsing Security' where id = 4"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-AMS-NL' where id = 1"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-FR' where id = 2"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-SE' where id = 3"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-USCA' where id = 4"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-Tiarap' where id = 5"
                    )
                }
            }

        private val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE 'DNSLogs' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'queryStr' TEXT NOT NULL, 'time' INTEGER NOT NULL, 'flag' TEXT NOT NULL, 'resolver' TEXT NOT NULL, 'latency' INTEGER NOT NULL, 'typeName' TEXT NOT NULL, 'isBlocked' INTEGER NOT NULL, 'blockLists' LONGTEXT NOT NULL,  'serverIP' TEXT NOT NULL, 'relayIP' TEXT NOT NULL, 'responseTime' INTEGER NOT NULL, 'response' TEXT NOT NULL, 'status' TEXT NOT NULL,'dnsType' INTEGER NOT NULL) "
                    )
                    // https://basic.bravedns.com/1:YBIgACABAHAgAA== - New block list configured
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:YBcgAIAQIAAIAABgIAA=' where id = 4"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName='Quad9', dnsCryptURL='sdns://AQMAAAAAAAAADDkuOS45Ljk6ODQ0MyBnyEe4yHWM0SAkVUO-dWdG3zTfHYTAC4xHA2jfgh2GPhkyLmRuc2NyeXB0LWNlcnQucXVhZDkubmV0',dnsCryptExplanation='Quad9 (anycast) dnssec/no-log/filter 9.9.9.9 / 149.112.112.9' where id=5"
                    )
                    database.execSQL(
                        "ALTER TABLE CategoryInfo add column numOfAppWhitelisted INTEGER DEFAULT 0 NOT NULL"
                    )
                    database.execSQL(
                        "ALTER TABLE CategoryInfo add column numOfAppsExcluded INTEGER DEFAULT 0 NOT NULL"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Netherlands' where id = 1"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='France' where id = 2"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Sweden' where id = 3"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='US - Los Angeles, CA' where id = 4"
                    )
                    database.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Singapore' where id = 5"
                    )
                }
            }

        private val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://security.cloudflare-dns.com/dns-query' where id = 3"
                    )
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:YBcgAIAQIAAIAABgIAA=' where id = 4"
                    )
                }
            }

        /**
         * For the version 053-1. Created a view for the AppInfo table so that the read will be
         * minimized. Also deleting the uid=0 row from AppInfo table. In earlier version the UID=0
         * is added as default and not used. Now the UID=0(ANDROID) is added to the non-app
         * category.
         */
        private val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE VIEW `AppInfoView` AS select appName, appCategory, isInternetAllowed, whiteListUniv1, isExcluded from AppInfo"
                    )
                    database.execSQL(
                        "UPDATE AppInfo set appCategory = 'System Components' where uid = 0"
                    )
                    database.execSQL(
                        "DELETE from AppInfo where appName = 'ANDROID' and appCategory = 'System Components'"
                    )
                }
            }

        private val MIGRATION_8_9: Migration =
            object : Migration(8, 9) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:YASAAQBwIAA=' where id = 4"
                    )
                }
            }

        private val MIGRATION_9_10: Migration =
            object : Migration(9, 10) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:IAAgAA==' where id = 4"
                    )
                }
            }

        private val MIGRATION_10_11: Migration =
            object : Migration(10, 11) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE DNSLogs add column responseIps TEXT DEFAULT '' NOT NULL"
                    )
                    database.execSQL(
                        "CREATE TABLE 'CustomDomain' ( 'domain' TEXT NOT NULL, 'ips' TEXT NOT NULL, 'status' INTEGER NOT NULL, 'type' INTEGER NOT NULL, 'createdTs' INTEGER NOT NULL, 'deletedTs' INTEGER NOT NULL, 'version' INTEGER NOT NULL, PRIMARY KEY (domain)) "
                    )
                }
            }

        private val MIGRATION_11_12: Migration =
            object : Migration(11, 12) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addMoreDohToList(database)
                    modifyAppInfoTableSchema(database)
                    modifyBlockedConnectionsTable(database)
                    database.execSQL("DROP VIEW AppInfoView")
                    database.execSQL("DROP TABLE if exists CategoryInfo")
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohURL = `replace`(dohURL,'bravedns','rethinkdns')"
                    )
                    modifyConnectionTrackerTable(database)
                    createRethinkDnsTable(database)
                    removeRethinkFromDohList(database)
                    updateDnscryptStamps(database)
                    createRethinkFileTagTables(database)
                    insertIpv6DnsProxyEndpoint(database)
                }

                private fun updateDnscryptStamps(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptURL='sdns://AQMAAAAAAAAAEjE0OS4xMTIuMTEyLjk6ODQ0MyBnyEe4yHWM0SAkVUO-dWdG3zTfHYTAC4xHA2jfgh2GPhkyLmRuc2NyeXB0LWNlcnQucXVhZDkubmV0' where id=5"
                        )
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptURL='sdns://AQMAAAAAAAAAETk0LjE0MC4xNC4xNTo1NDQzILgxXdexS27jIKRw3C7Wsao5jMnlhvhdRUXWuMm1AFq6ITIuZG5zY3J5cHQuZmFtaWx5Lm5zMS5hZGd1YXJkLmNvbQ' where id=3"
                        )
                    }
                }

                // add more doh options as default
                private fun addMoreDohToList(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "INSERT OR REPLACE INTO DoHEndpoint(dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values('Google','https://dns.google/dns-query','Traditional DNS queries and replies are sent over UDP or TCP without encryption, making them subject to surveillance, spoofing, and DNS-based Internet filtering.',0,0,0,0)"
                        )
                        execSQL(
                            "INSERT OR REPLACE INTO DoHEndpoint(dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values('CleanBrowsing Family','https://doh.cleanbrowsing.org/doh/family-filter/','Family filter blocks access to all adult, pornographic and explicit sites. It also blocks proxy and VPN domains that could be used to bypass our filters. Mixed content sites (like Reddit) are also blocked. Google, Bing and Youtube are set to the Safe Mode.',0,0,0,0)"
                        )
                        execSQL(
                            "INSERT OR REPLACE INTO DoHEndpoint(dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values('CleanBrowsing Adult','https://doh.cleanbrowsing.org/doh/adult-filter/','Adult filter blocks access to all adult, pornographic and explicit sites. It does not block proxy or VPNs, nor mixed-content sites. Sites like Reddit are allowed. Google and Bing are set to the Safe Mode.',0,0,0,0)"
                        )
                        execSQL(
                            "INSERT OR REPLACE INTO DoHEndpoint(dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values('Quad9 Secure','https://dns.quad9.net/dns-query','Quad9 routes your DNS queries through a secure network of servers around the globe.',0,0,0,0)"
                        )
                        execSQL(
                            "INSERT OR REPLACE INTO DoHEndpoint(dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values('Digitale Gesellschaft','https://dns.digitale-gesellschaft.ch','Public DoH resolver operated by the Digital Society (https://www.digitale-gesellschaft.ch). Hosted in Zurich, Switzerland.',0,0,0,0)"
                        )
                    }
                }

                // rename blockedConnections table to CustomIp
                private fun modifyBlockedConnectionsTable(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "CREATE TABLE 'CustomIp' ('uid' INTEGER NOT NULL, 'ipAddress' TEXT DEFAULT '' NOT NULL, 'port' INTEGER DEFAULT '' NOT NULL, 'protocol' TEXT DEFAULT '' NOT NULL, 'isActive' INTEGER DEFAULT 1 NOT NULL, 'status' INTEGER DEFAULT 1 NOT NULL,'ruleType' INTEGER DEFAULT 0 NOT NULL, 'wildcard' INTEGER DEFAULT 0 NOT NULL, 'modifiedDateTime' INTEGER DEFAULT 0 NOT NULL, PRIMARY KEY(uid, ipAddress, port, protocol))"
                        )
                        execSQL(
                            "INSERT INTO 'CustomIp' SELECT uid, ipAddress, port, protocol, isActive, 1, 0, 0, modifiedDateTime from BlockedConnections"
                        )
                        execSQL("DROP TABLE if exists BlockedConnections")
                    }
                }

                private fun modifyAppInfoTableSchema(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "CREATE TABLE 'AppInfo_backup' ('packageInfo' TEXT PRIMARY KEY NOT NULL, 'appName' TEXT NOT NULL, 'uid' INTEGER NOT NULL, 'isSystemApp' INTEGER NOT NULL, 'firewallStatus' INTEGER NOT NULL DEFAULT 0, 'appCategory' TEXT NOT NULL, 'wifiDataUsed' INTEGER NOT NULL, 'mobileDataUsed' INTEGER NOT NULL, 'metered' INTEGER NOT NULL DEFAULT 0, 'screenOffAllowed' INTEGER NOT NULL DEFAULT 0, 'backgroundAllowed' INTEGER NOT NULL DEFAULT 0,  'isInternetAllowed' INTEGER NOT NULL, 'whiteListUniv1' INTEGER NOT NULL, 'isExcluded' INTEGER NOT NULL)"
                        )
                        execSQL(
                            "INSERT INTO AppInfo_backup SELECT packageInfo, appName, uid, isSystemApp, 0, appCategory, wifiDataUsed, mobileDataUsed, 0, isScreenOff, isBackgroundEnabled, isInternetAllowed, whiteListUniv1, isExcluded FROM AppInfo"
                        )
                        execSQL(
                            "UPDATE AppInfo_backup set firewallStatus = 0 where isInternetAllowed = 1"
                        )
                        execSQL(
                            "UPDATE AppInfo_backup set firewallStatus = 1 where isInternetAllowed = 0"
                        )
                        execSQL(
                            "UPDATE AppInfo_backup set firewallStatus = 2 where whiteListUniv1 = 1"
                        )
                        execSQL("UPDATE AppInfo_backup set firewallStatus = 3 where isExcluded = 1")
                        execSQL(" DROP TABLE if exists AppInfo")
                        execSQL(
                            "CREATE TABLE 'AppInfo' ('packageInfo' TEXT PRIMARY KEY NOT NULL, 'appName' TEXT NOT NULL, 'uid' INTEGER NOT NULL, 'isSystemApp' INTEGER NOT NULL, 'firewallStatus' INTEGER NOT NULL DEFAULT 0, 'appCategory' TEXT NOT NULL, 'wifiDataUsed' INTEGER NOT NULL, 'mobileDataUsed' INTEGER NOT NULL, 'metered' INTEGER NOT NULL DEFAULT 0, 'screenOffAllowed' INTEGER NOT NULL DEFAULT 0, 'backgroundAllowed' INTEGER NOT NULL DEFAULT 0)"
                        )
                        execSQL(
                            "INSERT INTO AppInfo SELECT packageInfo, appName, uid, isSystemApp, firewallStatus, appCategory, wifiDataUsed, mobileDataUsed, metered, screenOffAllowed, backgroundAllowed FROM AppInfo_backup"
                        )

                        execSQL("DROP TABLE AppInfo_backup")
                    }
                }

                // introduce NOT NULL property for columns in the schema, alter table query cannot
                // add the not-null to the schema, so creating a backup and recreating the table
                // during migration.
                private fun modifyConnectionTrackerTable(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "CREATE TABLE 'ConnectionTracker_backup' ('id' INTEGER NOT NULL,'appName' TEXT DEFAULT '' NOT NULL, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT DEFAULT ''  NOT NULL, 'port' INTEGER NOT NULL, 'protocol' INTEGER NOT NULL,'isBlocked' INTEGER NOT NULL, 'blockedByRule' TEXT DEFAULT '' NOT NULL, 'flag' TEXT  DEFAULT '' NOT NULL, 'dnsQuery' TEXT DEFAULT '', 'timeStamp' INTEGER NOT NULL,PRIMARY KEY (id)  )"
                        )
                        execSQL(
                            "INSERT INTO ConnectionTracker_backup SELECT id, appName, uid, ipAddress, port, protocol, isBlocked, blockedByRule, flag, '', timeStamp from ConnectionTracker"
                        )
                        execSQL("DROP TABLE if exists ConnectionTracker")
                        execSQL(
                            "CREATE TABLE 'ConnectionTracker' ('id' INTEGER NOT NULL,'appName' TEXT DEFAULT '' NOT NULL, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT DEFAULT ''  NOT NULL, 'port' INTEGER NOT NULL, 'protocol' INTEGER NOT NULL,'isBlocked' INTEGER NOT NULL, 'blockedByRule' TEXT DEFAULT '' NOT NULL, 'flag' TEXT  DEFAULT '' NOT NULL, 'dnsQuery' TEXT DEFAULT '', 'timeStamp' INTEGER NOT NULL,PRIMARY KEY (id)  )"
                        )
                        execSQL(
                            "INSERT INTO ConnectionTracker SELECT id, appName, uid, ipAddress, port, protocol, isBlocked, blockedByRule, flag, '',  timeStamp from ConnectionTracker_backup"
                        )
                        execSQL("DROP TABLE if exists ConnectionTracker_backup")
                    }
                }

                // create new table to store Rethink dns endpoint
                // contains both the global and app specific dns endpoints
                private fun createRethinkDnsTable(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "CREATE TABLE 'RethinkDnsEndpoint' ('name' TEXT NOT NULL, 'url' TEXT NOT NULL, 'uid' INTEGER NOT NULL, 'desc' TEXT NOT NULL, 'isActive' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL, 'latency' INTEGER NOT NULL, 'blocklistCount' INTEGER NOT NULL DEFAULT 0,'modifiedDataTime' INTEGER NOT NULL,  PRIMARY KEY (name, url, uid))"
                        )
                        execSQL(
                            "INSERT INTO 'RethinkDnsEndpoint' ( 'name', 'url', 'uid', 'desc', 'isActive', 'isCustom', 'latency', 'blocklistCount', 'modifiedDataTime' ) VALUES ( 'RDNS Default', 'https://basic.rethinkdns.com/1:IAAQAA==',  ${Constants.MISSING_UID}, 'Blocks over 100,000+ phishing, malvertising, malware, spyware, ransomware, cryptojacking and other threats.', '0', '0', '0', '1','1633624616715')"
                        )
                        execSQL(
                            "INSERT INTO 'RethinkDnsEndpoint' ( 'name', 'url', 'uid', 'desc', 'isActive', 'isCustom', 'latency', 'blocklistCount', 'modifiedDataTime' ) VALUES ( 'RDNS Adult', 'https://basic.rethinkdns.com/1:EMABAADgIAA=', ${Constants.MISSING_UID}, 'Blocks over 30,000 adult websites.', '0', '0', '0','5', '1633624616715')"
                        )
                        execSQL(
                            "INSERT INTO 'RethinkDnsEndpoint' ( 'name', 'url', 'uid', 'desc', 'isActive', 'isCustom', 'latency', 'blocklistCount', 'modifiedDataTime' ) VALUES ( 'RDNS Piracy', 'https://basic.rethinkdns.com/1:EID-BwCB', ${Constants.MISSING_UID}, 'Blocks torrent, dubious video streaming and file sharing websites.', '0', '0', '0','12', '1633624616715')"
                        )
                        execSQL(
                            "INSERT INTO 'RethinkDnsEndpoint' ( 'name', 'url', 'uid', 'desc', 'isActive', 'isCustom', 'latency', 'blocklistCount', 'modifiedDataTime' ) VALUES ( 'RDNS Social Media', 'https://basic.rethinkdns.com/1:AEAAEA==', ${Constants.MISSING_UID}, 'Blocks popular social media including Facebook, Instagram, and WhatsApp.', '0', '0', '0','1', '1633624616715')"
                        )
                        execSQL(
                            "INSERT INTO 'RethinkDnsEndpoint' ( 'name', 'url', 'uid', 'desc', 'isActive', 'isCustom', 'latency', 'blocklistCount', 'modifiedDataTime' ) VALUES ( 'RDNS Security', 'https://basic.rethinkdns.com/1:4AIAgAABAHAgAA==', ${Constants.MISSING_UID}, 'Blocks over 150,000 malware, ransomware, phishing and other threats.', '0', '0', '0','37', '1633624616715')"
                        )
                        execSQL(
                            "INSERT INTO 'RethinkDnsEndpoint' ( 'name', 'url', 'uid', 'desc', 'isActive', 'isCustom', 'latency', 'blocklistCount', 'modifiedDataTime' ) VALUES ( 'RDNS Privacy', 'https://basic.rethinkdns.com/1:QAcCAIAcAhCkAg==', ${Constants.MISSING_UID}, 'Blocks over 100,000+ adware, spyware, and trackers through some of the most extensive blocklists.', '0', '0', '0','11', '1633624616715')"
                        )
                        execSQL(
                            "INSERT INTO 'RethinkDnsEndpoint' ( 'name', 'url', 'uid', 'desc', 'isActive', 'isCustom', 'latency', 'blocklistCount', 'modifiedDataTime' ) VALUES ( 'RDNS Plus', (Select dohurl from DoHEndpoint where id = 5), ${Constants.MISSING_UID}, 'User Configured', (select isSelected from DoHEndpoint where id = 5), '0', '1', '0', '1633624616715')"
                        )
                    }
                }

                private fun createRethinkFileTagTables(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "CREATE TABLE RethinkRemoteFileTag ('value' INTEGER NOT NULL, 'uname' TEXT NOT NULL, 'vname' TEXT NOT NULL, 'group' TEXT NOT NULL, 'subg' TEXT NOT NULL, 'url' TEXT NOT NULL, 'show' INTEGER NOT NULL, 'entries' INTEGER NOT NULL, 'simpleTagId' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL,  PRIMARY KEY (value))"
                        )
                        execSQL(
                            "CREATE TABLE RethinkLocalFileTag ('value' INTEGER NOT NULL, 'uname' TEXT NOT NULL, 'vname' TEXT NOT NULL, 'group' TEXT NOT NULL, 'subg' TEXT NOT NULL, 'url' TEXT NOT NULL, 'show' INTEGER NOT NULL, 'entries' INTEGER NOT NULL,  'simpleTagId' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, PRIMARY KEY (value))"
                        )
                    }
                }

                // remove the rethink doh from the list
                private fun removeRethinkFromDohList(database: SupportSQLiteDatabase) {
                    with(database) { execSQL("DELETE from DoHEndpoint where id in (4,5)") }
                }

                private fun insertIpv6DnsProxyEndpoint(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "INSERT OR REPLACE INTO DNSProxyEndpoint(proxyName, proxyType, proxyAppName, proxyIP, proxyPort, isSelected, isCustom, modifiedDataTime,latency) values ('Google IPv6','External','Nobody','2001:4860:4860::8888',53,0,0,0,0)"
                        )
                        execSQL(
                            "INSERT OR REPLACE INTO DNSProxyEndpoint(proxyName, proxyType, proxyAppName, proxyIP, proxyPort, isSelected, isCustom, modifiedDataTime,latency) values ('Cloudflare IPv6','External','Nobody','2606:4700:4700::1111',53,0,0,0,0)"
                        )
                        execSQL(
                            "INSERT OR REPLACE INTO DNSProxyEndpoint(proxyName, proxyType, proxyAppName, proxyIP, proxyPort, isSelected, isCustom, modifiedDataTime,latency) values ('Quad9 IPv6','External','Nobody','2620:fe::fe',53,0,0,0,0)"
                        )
                    }
                }
            }

        private val MIGRATION_12_13: Migration =
            object : Migration(12, 13) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "INSERT OR REPLACE INTO DNSProxyEndpoint(proxyName, proxyType, proxyAppName, proxyIP, proxyPort, isSelected, isCustom, modifiedDataTime,latency) values ('Orbot','External','org.torproject.android','127.0.0.1',5400,0,0,0,0)"
                    )
                }
            }

        // migration part of v053k
        private val MIGRATION_13_14: Migration =
            object : Migration(13, 14) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // modify the default blocklist to OISD
                    database.execSQL(
                        "UPDATE RethinkDnsEndpoint set url  = 'https://basic.rethinkdns.com/1:IAAgAA==' where name = 'RDNS Default' and isCustom = 0"
                    )
                    database.execSQL(
                        "Update AppInfo set appCategory = 'System Services' where appCategory = 'Non-App System' and isSystemApp = 1"
                    )
                    database.execSQL(
                        "Update RethinkDnsEndpoint set url = REPLACE(url, 'basic', 'sky')"
                    )
                }
            }

        // migration part of v053l
        private val MIGRATION_14_15: Migration =
            object : Migration(14, 15) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE RethinkLocalFileTag add column pack TEXT DEFAULT ''"
                    )
                    database.execSQL(
                        "ALTER TABLE RethinkRemoteFileTag add column pack TEXT DEFAULT ''"
                    )
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohExplanation = 'Family filter blocks access to all adult, graphic and explicit sites. It also blocks proxy and VPN domains that could be used to bypass our filters. Mixed content sites (like Reddit) are also blocked. Google, Bing and Youtube are set to the Safe Mode.' where dohName = 'CleanBrowsing Family'"
                    )
                    database.execSQL(
                        "UPDATE DoHEndpoint set dohExplanation = 'Adult filter blocks access to all adult, graphic and explicit sites. It does not block proxy or VPNs, nor mixed-content sites. Sites like Reddit are allowed. Google and Bing are set to the Safe Mode.'  where dohName = 'CleanBrowsing Adult'"
                    )
                }
            }

        // migration part of v053m
        private val MIGRATION_15_16: Migration =
            object : Migration(15, 16) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    modifyAppInfo(database)
                    database.execSQL("ALTER TABLE RethinkLocalFileTag add column level TEXT")
                    database.execSQL("ALTER TABLE RethinkRemoteFileTag add column level TEXT")
                    database.execSQL(
                        "CREATE TABLE 'LocalBlocklistPacksMap' ( 'pack' TEXT NOT NULL, 'level' INTEGER NOT NULL DEFAULT 0, 'blocklistIds' TEXT NOT NULL, 'group' TEXT NOT NULL, PRIMARY KEY (pack, level)) "
                    )
                    database.execSQL(
                        "CREATE TABLE 'RemoteBlocklistPacksMap' ( 'pack' TEXT NOT NULL, 'level' INTEGER NOT NULL DEFAULT 0, 'blocklistIds' TEXT NOT NULL, 'group' TEXT NOT NULL, PRIMARY KEY (pack, level)) "
                    )
                    database.execSQL(
                        "UPDATE RethinkDnsEndpoint set url = case when url = 'https://max.rethinkdns.com/1:IAAgAA=='  then 'https://max.rethinkdns.com/rec' else 'https://sky.rethinkdns.com/rec' end where name = 'RDNS Default' and isCustom = 0"
                    )
                }

                private fun modifyAppInfo(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "CREATE TABLE 'AppInfo_backup' ('packageName' TEXT NOT NULL, 'appName' TEXT NOT NULL, 'uid' INTEGER NOT NULL, 'isSystemApp' INTEGER NOT NULL, 'firewallStatus' INTEGER NOT NULL DEFAULT 0, 'appCategory' TEXT NOT NULL, 'wifiDataUsed' INTEGER NOT NULL, 'mobileDataUsed' INTEGER NOT NULL, 'metered' INTEGER NOT NULL DEFAULT 0, 'screenOffAllowed' INTEGER NOT NULL DEFAULT 0, 'backgroundAllowed' INTEGER NOT NULL DEFAULT 0,  PRIMARY KEY(uid, packageName))"
                        )
                        execSQL(
                            "INSERT INTO AppInfo_backup SELECT packageInfo, appName, uid, isSystemApp, firewallStatus, appCategory, wifiDataUsed, mobileDataUsed, metered, screenOffAllowed, backgroundAllowed FROM AppInfo"
                        )
                        execSQL(" DROP TABLE if exists AppInfo")
                        execSQL(
                            "CREATE TABLE 'AppInfo' ('packageName' TEXT NOT NULL, 'appName' TEXT NOT NULL, 'uid' INTEGER NOT NULL, 'isSystemApp' INTEGER NOT NULL, 'firewallStatus' INTEGER NOT NULL DEFAULT 0, 'appCategory' TEXT NOT NULL, 'wifiDataUsed' INTEGER NOT NULL, 'mobileDataUsed' INTEGER NOT NULL, 'metered' INTEGER NOT NULL DEFAULT 0, 'screenOffAllowed' INTEGER NOT NULL DEFAULT 0, 'backgroundAllowed' INTEGER NOT NULL DEFAULT 0,  PRIMARY KEY(uid, packageName))"
                        )
                        execSQL(
                            "INSERT INTO AppInfo SELECT packageName, appName, uid, isSystemApp, firewallStatus, appCategory, wifiDataUsed, mobileDataUsed, metered, screenOffAllowed, backgroundAllowed FROM AppInfo_backup"
                        )
                        execSQL("DROP TABLE AppInfo_backup")
                    }
                }
            }

        // migration part of v054
        private val MIGRATION_16_17: Migration =
            object : Migration(16, 17) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("DROP table if exists CustomDomain")
                    database.execSQL(
                        "CREATE TABLE 'CustomDomain' ( 'domain' TEXT NOT NULL, 'uid' INT NOT NULL,  'ips' TEXT NOT NULL, 'status' INTEGER NOT NULL, 'type' INTEGER NOT NULL, 'modifiedTs' INTEGER NOT NULL, 'deletedTs' INTEGER NOT NULL, 'version' INTEGER NOT NULL, PRIMARY KEY (domain, uid)) "
                    )
                    modifyAppInfo(database)
                }

                private fun modifyAppInfo(database: SupportSQLiteDatabase) {
                    with(database) {
                        execSQL(
                            "CREATE TABLE 'AppInfo_backup' ('packageName' TEXT NOT NULL, 'appName' TEXT NOT NULL, 'uid' INTEGER NOT NULL, 'isSystemApp' INTEGER NOT NULL, 'firewallStatus' INTEGER NOT NULL DEFAULT 5, 'appCategory' TEXT NOT NULL, 'wifiDataUsed' INTEGER NOT NULL, 'mobileDataUsed' INTEGER NOT NULL, 'connectionStatus' INTEGER NOT NULL DEFAULT 3, 'screenOffAllowed' INTEGER NOT NULL DEFAULT 0, 'backgroundAllowed' INTEGER NOT NULL DEFAULT 0,  PRIMARY KEY(uid, packageName))"
                        )
                        execSQL(
                            "INSERT INTO AppInfo_backup SELECT packageName, appName, uid, isSystemApp, firewallStatus, appCategory, wifiDataUsed, mobileDataUsed, metered, screenOffAllowed, backgroundAllowed FROM AppInfo"
                        )
                        execSQL(" DROP TABLE if exists AppInfo")
                        execSQL(
                            "CREATE TABLE 'AppInfo' ('packageName' TEXT NOT NULL, 'appName' TEXT NOT NULL, 'uid' INTEGER NOT NULL, 'isSystemApp' INTEGER NOT NULL, 'firewallStatus' INTEGER NOT NULL DEFAULT 5, 'appCategory' TEXT NOT NULL, 'wifiDataUsed' INTEGER NOT NULL, 'mobileDataUsed' INTEGER NOT NULL, 'connectionStatus' INTEGER NOT NULL DEFAULT 3, 'screenOffAllowed' INTEGER NOT NULL DEFAULT 0, 'backgroundAllowed' INTEGER NOT NULL DEFAULT 0,  PRIMARY KEY(uid, packageName))"
                        )
                        execSQL(
                            "INSERT INTO AppInfo SELECT packageName, appName, uid, isSystemApp, firewallStatus, appCategory, wifiDataUsed, mobileDataUsed, connectionStatus, screenOffAllowed, backgroundAllowed FROM AppInfo_backup"
                        )
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 5, connectionStatus = 3 where firewallStatus = 0"
                        )
                        execSQL("UPDATE AppInfo set firewallStatus = 5 where firewallStatus = 1")
                        execSQL("DROP TABLE AppInfo_backup")
                    }
                }
            }
    }

    // fixme: revisit the links to remove the pragma for each table
    // https://stackoverflow.com/questions/49030258/how-to-vacuum-roomdatabase
    // https://stackoverflow.com/questions/50987119/backup-room-database
    fun checkPoint() {
        appInfoDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
        dohEndpointsDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
        dnsCryptEndpointDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
        dnsCryptRelayEndpointDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
        dnsProxyEndpointDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
        proxyEndpointDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
        customDomainEndpointDAO().checkpoint(SimpleSQLiteQuery(PRAGMA))
        customIpEndpointDao().checkpoint(SimpleSQLiteQuery(PRAGMA))
        rethinkEndpointDao().checkpoint(SimpleSQLiteQuery(PRAGMA))
        rethinkRemoteFileTagDao().checkpoint(SimpleSQLiteQuery(PRAGMA))
        rethinkLocalFileTagDao().checkpoint(SimpleSQLiteQuery(PRAGMA))
    }

    abstract fun appInfoDAO(): AppInfoDAO
    abstract fun dohEndpointsDAO(): DoHEndpointDAO
    abstract fun dnsCryptEndpointDAO(): DnsCryptEndpointDAO
    abstract fun dnsCryptRelayEndpointDAO(): DnsCryptRelayEndpointDAO
    abstract fun dnsProxyEndpointDAO(): DnsProxyEndpointDAO
    abstract fun proxyEndpointDAO(): ProxyEndpointDAO
    abstract fun customDomainEndpointDAO(): CustomDomainDAO
    abstract fun customIpEndpointDao(): CustomIpDao
    abstract fun rethinkEndpointDao(): RethinkDnsEndpointDao
    abstract fun rethinkRemoteFileTagDao(): RethinkRemoteFileTagDao
    abstract fun rethinkLocalFileTagDao(): RethinkLocalFileTagDao
    abstract fun localBlocklistPacksMapDao(): LocalBlocklistPacksMapDao
    abstract fun remoteBlocklistPacksMapDao(): RemoteBlocklistPacksMapDao

    fun appInfoRepository() = AppInfoRepository(appInfoDAO())
    fun dohEndpointRepository() = DoHEndpointRepository(dohEndpointsDAO())
    fun dnsCryptEndpointRepository() = DnsCryptEndpointRepository(dnsCryptEndpointDAO())
    fun dnsCryptRelayEndpointRepository() =
        DnsCryptRelayEndpointRepository(dnsCryptRelayEndpointDAO())

    fun dnsProxyEndpointRepository() = DnsProxyEndpointRepository(dnsProxyEndpointDAO())
    fun proxyEndpointRepository() = ProxyEndpointRepository(proxyEndpointDAO())
    fun customDomainRepository() = CustomDomainRepository(customDomainEndpointDAO())
    fun customIpRepository() = CustomIpRepository(customIpEndpointDao())
    fun rethinkEndpointRepository() = RethinkDnsEndpointRepository(rethinkEndpointDao())
    fun rethinkRemoteFileTagRepository() = RethinkRemoteFileTagRepository(rethinkRemoteFileTagDao())
    fun rethinkLocalFileTagRepository() = RethinkLocalFileTagRepository(rethinkLocalFileTagDao())
    fun localBlocklistPacksMapRepository() =
        LocalBlocklistPacksMapRepository(localBlocklistPacksMapDao())
    fun remoteBlocklistPacksMapRepository() =
        RemoteBlocklistPacksMapRepository(remoteBlocklistPacksMapDao())
}
