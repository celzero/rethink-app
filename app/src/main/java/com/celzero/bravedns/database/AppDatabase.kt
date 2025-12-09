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

import Logger
import Logger.LOG_TAG_APP_DB
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
        RemoteBlocklistPacksMap::class,
        WgConfigFiles::class,
        ProxyApplicationMapping::class,
        TcpProxyEndpoint::class,
        DoTEndpoint::class,
        ODoHEndpoint::class,
        RpnProxy::class,
        WgHopMap::class,
        SubscriptionStatus::class,
        SubscriptionStateHistory::class
    ],
    version = 28,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        const val DATABASE_NAME = "bravedns.db"
        private const val DATABASE_PATH = "database/rethink_v22.db"
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
                .addCallback(roomCallback)
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
                .addMigrations(MIGRATION_17_18)
                .addMigrations(migration1819(context))
                .addMigrations(MIGRATION_19_20)
                .addMigrations(MIGRATION_20_21)
                .addMigrations(MIGRATION_21_22)
                .addMigrations(MIGRATION_22_23)
                .addMigrations(MIGRATION_23_24)
                .addMigrations(MIGRATION_24_25)
                .addMigrations(MIGRATION_25_26)
                .addMigrations(MIGRATION_26_27)
                .addMigrations(MIGRATION_27_28)
                .build()

        private val roomCallback: Callback =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    Logger.i(LOG_TAG_APP_DB, "Database created, ${db.version}")
                }

                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    super.onDestructiveMigration(db)
                    Logger.i(LOG_TAG_APP_DB, "Database destructively migrated, ${db.version}")
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    Logger.i(LOG_TAG_APP_DB, "Database opened, ${db.version}")
                }
            }

        private val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DELETE from AppInfo")
                    db.execSQL("DELETE from CategoryInfo")
                    db.execSQL(
                        "CREATE TABLE 'CategoryInfo' ( 'categoryName' TEXT NOT NULL, 'numberOFApps' INTEGER NOT NULL,'numOfAppsBlocked' INTEGER NOT NULL, 'isInternetBlocked' INTEGER NOT NULL, PRIMARY KEY (categoryName)) "
                    )
                }
            }

        private val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DELETE from AppInfo ")
                    db.execSQL("DELETE from CategoryInfo")
                    db.execSQL("DROP TABLE if exists ConnectionTracker")
                    db.execSQL(
                        "CREATE TABLE 'ConnectionTracker' ('id' INTEGER NOT NULL,'appName' TEXT, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT, 'port' INTEGER NOT NULL, 'protocol' INTEGER NOT NULL,'isBlocked' INTEGER NOT NULL, 'flag' TEXT, 'timeStamp' INTEGER NOT NULL,PRIMARY KEY (id)  )"
                    )
                    db.execSQL(
                        "CREATE TABLE 'BlockedConnections' ( 'id' INTEGER NOT NULL, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT, 'port' INTEGER NOT NULL, 'protocol' TEXT, PRIMARY KEY (id)) "
                    )
                }
            }

        private val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE BlockedConnections ADD COLUMN isActive INTEGER DEFAULT 1 NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE BlockedConnections ADD COLUMN ruleType TEXT DEFAULT 'RULE4' NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE BlockedConnections ADD COLUMN modifiedDateTime INTEGER DEFAULT 0  NOT NULL"
                    )
                    db.execSQL("UPDATE BlockedConnections set ruleType = 'RULE5' where uid = -1000")
                    db.execSQL("ALTER TABLE ConnectionTracker ADD COLUMN blockedByRule TEXT")
                    db.execSQL(
                        "UPDATE ConnectionTracker set blockedByRule = 'RULE4' where uid <> -1000 and isBlocked = 1"
                    )
                    db.execSQL(
                        "UPDATE ConnectionTracker set blockedByRule = 'RULE5' where uid = -1000  and isBlocked = 1"
                    )
                    db.execSQL(
                        "ALTER TABLE AppInfo add column whiteListUniv1 INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE AppInfo add column whiteListUniv2 INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE AppInfo add column isExcluded INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "CREATE TABLE 'DoHEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dohName' TEXT NOT NULL, 'dohURL' TEXT NOT NULL,'dohExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    db.execSQL(
                        "CREATE TABLE 'DNSCryptEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dnsCryptName' TEXT NOT NULL, 'dnsCryptURL' TEXT NOT NULL,'dnsCryptExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    db.execSQL(
                        "CREATE TABLE 'DNSCryptRelayEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dnsCryptRelayName' TEXT NOT NULL, 'dnsCryptRelayURL' TEXT NOT NULL,'dnsCryptRelayExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    db.execSQL(
                        "CREATE TABLE 'DNSProxyEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'proxyName' TEXT NOT NULL, 'proxyType' TEXT NOT NULL,'proxyAppName' TEXT , 'proxyIP' TEXT, 'proxyPort' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    db.execSQL(
                        "CREATE TABLE 'ProxyEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'proxyName' TEXT NOT NULL,'proxyMode' INTEGER NOT NULL, 'proxyType' TEXT NOT NULL,'proxyAppName' TEXT , 'proxyIP' TEXT, 'userName' TEXT , 'password' TEXT, 'proxyPort' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL , 'isUDP' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) "
                    )
                    // Perform insert of endpoints
                    db.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(1,'Cloudflare','https://cloudflare-dns.com/dns-query','Does not block any DNS requests. Uses Cloudflare''s 1.1.1.1 DNS endpoint.',0,0,0,0)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(2,'Cloudflare Family','https://family.cloudflare-dns.com/dns-query','Blocks malware and adult content. Uses Cloudflare''s 1.1.1.3 DNS endpoint.',0,0,0,0)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(3,'Cloudflare Security','https://security.cloudflare-dns.com/dns-query','Blocks malicious content. Uses Cloudflare''s 1.1.1.2 DNS endpoint.',0,0,0,0)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(4,'RethinkDNS Basic (default)','https://basic.bravedns.com/1:YBcgAIAQIAAIAABgIAA=','Blocks malware and more. Uses RethinkDNS''s non-configurable basic endpoint.',1,0,0,0)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(5,'RethinkDNS Plus','https://basic.bravedns.com/','Configurable DNS endpoint: Provides in-depth analytics of your Internet traffic, allows you to set custom rules and more.',0,0,0,0)"
                    )
                }
            }

        private val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DELETE from DNSProxyEndpoint")
                    db.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:wBdgAIoBoB02kIAA5HI=' where id = 4"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName='Quad9', dnsCryptURL='sdns://AQYAAAAAAAAAEzE0OS4xMTIuMTEyLjEwOjg0NDMgZ8hHuMh1jNEgJFVDvnVnRt803x2EwAuMRwNo34Idhj4ZMi5kbnNjcnlwdC1jZXJ0LnF1YWQ5Lm5ldA',dnsCryptExplanation='Quad9 (anycast) no-dnssec/no-log/no-filter 9.9.9.10 / 149.112.112.10' where id=5"
                    )
                    db.execSQL(
                        "INSERT into DNSProxyEndpoint values (1,'Google','External','Nobody','8.8.8.8',53,0,0,0,0)"
                    )
                    db.execSQL(
                        "INSERT into DNSProxyEndpoint values (2,'Cloudflare','External','Nobody','1.1.1.1',53,0,0,0,0)"
                    )
                    db.execSQL(
                        "INSERT into DNSProxyEndpoint values (3,'Quad9','External','Nobody','9.9.9.9',53,0,0,0,0)"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName ='Cleanbrowsing Family' where id = 1"
                    )
                    db.execSQL("UPDATE DNSCryptEndpoint set dnsCryptName ='Adguard' where id = 2")
                    db.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName ='Adguard Family' where id = 3"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName ='Cleanbrowsing Security' where id = 4"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-AMS-NL' where id = 1"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-FR' where id = 2"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-SE' where id = 3"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-USCA' where id = 4"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-Tiarap' where id = 5"
                    )
                }
            }

        private val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE 'DNSLogs' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'queryStr' TEXT NOT NULL, 'time' INTEGER NOT NULL, 'flag' TEXT NOT NULL, 'resolver' TEXT NOT NULL, 'latency' INTEGER NOT NULL, 'typeName' TEXT NOT NULL, 'isBlocked' INTEGER NOT NULL, 'blockLists' LONGTEXT NOT NULL,  'serverIP' TEXT NOT NULL, 'relayIP' TEXT NOT NULL, 'responseTime' INTEGER NOT NULL, 'response' TEXT NOT NULL, 'status' TEXT NOT NULL,'dnsType' INTEGER NOT NULL) "
                    )
                    // https://basic.bravedns.com/1:YBIgACABAHAgAA== - New block list configured
                    db.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:YBcgAIAQIAAIAABgIAA=' where id = 4"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptEndpoint set dnsCryptName='Quad9', dnsCryptURL='sdns://AQMAAAAAAAAADDkuOS45Ljk6ODQ0MyBnyEe4yHWM0SAkVUO-dWdG3zTfHYTAC4xHA2jfgh2GPhkyLmRuc2NyeXB0LWNlcnQucXVhZDkubmV0',dnsCryptExplanation='Quad9 (anycast) dnssec/no-log/filter 9.9.9.9 / 149.112.112.9' where id=5"
                    )
                    db.execSQL(
                        "ALTER TABLE CategoryInfo add column numOfAppWhitelisted INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE CategoryInfo add column numOfAppsExcluded INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Netherlands' where id = 1"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='France' where id = 2"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Sweden' where id = 3"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='US - Los Angeles, CA' where id = 4"
                    )
                    db.execSQL(
                        "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Singapore' where id = 5"
                    )
                }
            }

        private val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://security.cloudflare-dns.com/dns-query' where id = 3"
                    )
                    db.execSQL(
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
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE VIEW `AppInfoView` AS select appName, appCategory, isInternetAllowed, whiteListUniv1, isExcluded from AppInfo"
                    )
                    db.execSQL("UPDATE AppInfo set appCategory = 'System Components' where uid = 0")
                    db.execSQL(
                        "DELETE from AppInfo where appName = 'ANDROID' and appCategory = 'System Components'"
                    )
                }
            }

        private val MIGRATION_8_9: Migration =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:YASAAQBwIAA=' where id = 4"
                    )
                }
            }

        private val MIGRATION_9_10: Migration =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:IAAgAA==' where id = 4"
                    )
                }
            }

        private val MIGRATION_10_11: Migration =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE DNSLogs add column responseIps TEXT DEFAULT '' NOT NULL"
                    )
                    db.execSQL(
                        "CREATE TABLE 'CustomDomain' ( 'domain' TEXT NOT NULL, 'ips' TEXT NOT NULL, 'status' INTEGER NOT NULL, 'type' INTEGER NOT NULL, 'createdTs' INTEGER NOT NULL, 'deletedTs' INTEGER NOT NULL, 'version' INTEGER NOT NULL, PRIMARY KEY (domain)) "
                    )
                }
            }

        private val MIGRATION_11_12: Migration =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    addMoreDohToList(db)
                    modifyAppInfoTableSchema(db)
                    modifyBlockedConnectionsTable(db)
                    db.execSQL("DROP VIEW AppInfoView")
                    db.execSQL("DROP TABLE if exists CategoryInfo")
                    db.execSQL(
                        "UPDATE DoHEndpoint set dohURL = `replace`(dohURL,'bravedns','rethinkdns')"
                    )
                    modifyConnectionTrackerTable(db)
                    createRethinkDnsTable(db)
                    removeRethinkFromDohList(db)
                    updateDnscryptStamps(db)
                    createRethinkFileTagTables(db)
                    insertIpv6DnsProxyEndpoint(db)
                }

                private fun updateDnscryptStamps(db: SupportSQLiteDatabase) {
                    with(db) {
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptURL='sdns://AQMAAAAAAAAAEjE0OS4xMTIuMTEyLjk6ODQ0MyBnyEe4yHWM0SAkVUO-dWdG3zTfHYTAC4xHA2jfgh2GPhkyLmRuc2NyeXB0LWNlcnQucXVhZDkubmV0' where id=5"
                        )
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptURL='sdns://AQMAAAAAAAAAETk0LjE0MC4xNC4xNTo1NDQzILgxXdexS27jIKRw3C7Wsao5jMnlhvhdRUXWuMm1AFq6ITIuZG5zY3J5cHQuZmFtaWx5Lm5zMS5hZGd1YXJkLmNvbQ' where id=3"
                        )
                    }
                }

                // add more doh options as default
                private fun addMoreDohToList(db: SupportSQLiteDatabase) {
                    with(db) {
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
                    }
                }

                // rename blockedConnections table to CustomIp
                private fun modifyBlockedConnectionsTable(db: SupportSQLiteDatabase) {
                    with(db) {
                        execSQL(
                            "CREATE TABLE 'CustomIp' ('uid' INTEGER NOT NULL, 'ipAddress' TEXT DEFAULT '' NOT NULL, 'port' INTEGER DEFAULT '' NOT NULL, 'protocol' TEXT DEFAULT '' NOT NULL, 'isActive' INTEGER DEFAULT 1 NOT NULL, 'status' INTEGER DEFAULT 1 NOT NULL,'ruleType' INTEGER DEFAULT 0 NOT NULL, 'wildcard' INTEGER DEFAULT 0 NOT NULL, 'modifiedDateTime' INTEGER DEFAULT 0 NOT NULL, PRIMARY KEY(uid, ipAddress, port, protocol))"
                        )
                        execSQL(
                            "INSERT INTO 'CustomIp' SELECT uid, ipAddress, port, protocol, isActive, 1, 0, 0, modifiedDateTime from BlockedConnections"
                        )
                        execSQL("DROP TABLE if exists BlockedConnections")
                    }
                }

                private fun modifyAppInfoTableSchema(db: SupportSQLiteDatabase) {
                    with(db) {
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
                private fun modifyConnectionTrackerTable(db: SupportSQLiteDatabase) {
                    with(db) {
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
                private fun createRethinkDnsTable(db: SupportSQLiteDatabase) {
                    with(db) {
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

                private fun createRethinkFileTagTables(db: SupportSQLiteDatabase) {
                    with(db) {
                        execSQL(
                            "CREATE TABLE RethinkRemoteFileTag ('value' INTEGER NOT NULL, 'uname' TEXT NOT NULL, 'vname' TEXT NOT NULL, 'group' TEXT NOT NULL, 'subg' TEXT NOT NULL, 'url' TEXT NOT NULL, 'show' INTEGER NOT NULL, 'entries' INTEGER NOT NULL, 'simpleTagId' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL,  PRIMARY KEY (value))"
                        )
                        execSQL(
                            "CREATE TABLE RethinkLocalFileTag ('value' INTEGER NOT NULL, 'uname' TEXT NOT NULL, 'vname' TEXT NOT NULL, 'group' TEXT NOT NULL, 'subg' TEXT NOT NULL, 'url' TEXT NOT NULL, 'show' INTEGER NOT NULL, 'entries' INTEGER NOT NULL,  'simpleTagId' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, PRIMARY KEY (value))"
                        )
                    }
                }

                // remove the rethink doh from the list
                private fun removeRethinkFromDohList(db: SupportSQLiteDatabase) {
                    with(db) { execSQL("DELETE from DoHEndpoint where id in (4,5)") }
                }

                private fun insertIpv6DnsProxyEndpoint(db: SupportSQLiteDatabase) {
                    with(db) {
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
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO DNSProxyEndpoint(proxyName, proxyType, proxyAppName, proxyIP, proxyPort, isSelected, isCustom, modifiedDataTime,latency) values ('Orbot','External','org.torproject.android','127.0.0.1',5400,0,0,0,0)"
                    )
                }
            }

        // migration part of v053k
        private val MIGRATION_13_14: Migration =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // modify the default blocklist to OISD
                    db.execSQL(
                        "UPDATE RethinkDnsEndpoint set url  = 'https://basic.rethinkdns.com/1:IAAgAA==' where name = 'RDNS Default' and isCustom = 0"
                    )
                    db.execSQL(
                        "Update AppInfo set appCategory = 'System Services' where appCategory = 'Non-App System' and isSystemApp = 1"
                    )
                    db.execSQL("Update RethinkDnsEndpoint set url = REPLACE(url, 'basic', 'sky')")
                }
            }

        // migration part of v053l
        private val MIGRATION_14_15: Migration =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE RethinkLocalFileTag add column pack TEXT DEFAULT ''")
                    db.execSQL("ALTER TABLE RethinkRemoteFileTag add column pack TEXT DEFAULT ''")
                    db.execSQL(
                        "UPDATE DoHEndpoint set dohExplanation = 'Family filter blocks access to all adult, graphic and explicit sites. It also blocks proxy and VPN domains that could be used to bypass our filters. Mixed content sites (like Reddit) are also blocked. Google, Bing and Youtube are set to the Safe Mode.' where dohName = 'CleanBrowsing Family'"
                    )
                    db.execSQL(
                        "UPDATE DoHEndpoint set dohExplanation = 'Adult filter blocks access to all adult, graphic and explicit sites. It does not block proxy or VPNs, nor mixed-content sites. Sites like Reddit are allowed. Google and Bing are set to the Safe Mode.'  where dohName = 'CleanBrowsing Adult'"
                    )
                }
            }

        // migration part of v053m
        private val MIGRATION_15_16: Migration =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    modifyAppInfo(db)
                    db.execSQL("ALTER TABLE RethinkLocalFileTag add column level TEXT")
                    db.execSQL("ALTER TABLE RethinkRemoteFileTag add column level TEXT")
                    db.execSQL(
                        "CREATE TABLE 'LocalBlocklistPacksMap' ( 'pack' TEXT NOT NULL, 'level' INTEGER NOT NULL DEFAULT 0, 'blocklistIds' TEXT NOT NULL, 'group' TEXT NOT NULL, PRIMARY KEY (pack, level)) "
                    )
                    db.execSQL(
                        "CREATE TABLE 'RemoteBlocklistPacksMap' ( 'pack' TEXT NOT NULL, 'level' INTEGER NOT NULL DEFAULT 0, 'blocklistIds' TEXT NOT NULL, 'group' TEXT NOT NULL, PRIMARY KEY (pack, level)) "
                    )
                    db.execSQL(
                        "UPDATE RethinkDnsEndpoint set url = case when url = 'https://max.rethinkdns.com/1:IAAgAA=='  then 'https://max.rethinkdns.com/rec' else 'https://sky.rethinkdns.com/rec' end where name = 'RDNS Default' and isCustom = 0"
                    )
                }

                private fun modifyAppInfo(db: SupportSQLiteDatabase) {
                    with(db) {
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
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP table if exists CustomDomain")
                    db.execSQL(
                        "CREATE TABLE 'CustomDomain' ( 'domain' TEXT NOT NULL, 'uid' INT NOT NULL,  'ips' TEXT NOT NULL, 'status' INTEGER NOT NULL, 'type' INTEGER NOT NULL, 'modifiedTs' INTEGER NOT NULL, 'deletedTs' INTEGER NOT NULL, 'version' INTEGER NOT NULL, PRIMARY KEY (domain, uid)) "
                    )
                    modifyAppInfo(db)
                    modifyRethinkDnsUrls(db)
                    updateDnscryptStamps(db)
                }

                private fun updateDnscryptStamps(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "update DnsCryptEndpoint set dnsCryptURL = 'sdns://AQMAAAAAAAAAFDE4NS4yMjguMTY4LjE2ODo4NDQzILysMvrVQ2kXHwgy1gdQJ8MgjO7w6OmflBjcd2Bl1I8pEWNsZWFuYnJvd3Npbmcub3Jn' where dnsCryptName = 'Cleanbrowsing Family' and id = 1"
                    )
                    db.execSQL(
                        "update DnsCryptEndpoint set dnsCryptURL = 'sdns://AQMAAAAAAAAAETk0LjE0MC4xNC4xNDo1NDQzINErR_JS3PLCu_iZEIbq95zkSV2LFsigxDIuUso_OQhzIjIuZG5zY3J5cHQuZGVmYXVsdC5uczEuYWRndWFyZC5jb20' where dnsCryptName = 'Adguard'  and id = 2"
                    )
                    db.execSQL(
                        "update DnsCryptEndpoint set dnsCryptURL = 'sdns://AQMAAAAAAAAAETk0LjE0MC4xNC4xNTo1NDQzILgxXdexS27jIKRw3C7Wsao5jMnlhvhdRUXWuMm1AFq6ITIuZG5zY3J5cHQuZmFtaWx5Lm5zMS5hZGd1YXJkLmNvbQ' where dnsCryptName = 'Adguard Family'  and id = 3"
                    )
                    db.execSQL(
                        "update DnsCryptEndpoint set dnsCryptURL = 'sdns://AQMAAAAAAAAAFDE0OS4xMTIuMTEyLjExMjo4NDQzIGfIR7jIdYzRICRVQ751Z0bfNN8dhMALjEcDaN-CHYY-GTIuZG5zY3J5cHQtY2VydC5xdWFkOS5uZXQ', dnsCryptName = 'Quad9 Security', dnsCryptExplanation = 'Quad9 (anycast) dnssec/no-log/filter 9.9.9.9 - 149.112.112.9 - 149.112.112.112' where dnsCryptName = 'Cleanbrowsing Security'  and id = 4"
                    )
                    db.execSQL(
                        "update DnsCryptEndpoint set dnsCryptURL = 'sdns://AQMAAAAAAAAAEzE0OS4xMTIuMTEyLjExOjg0NDMgZ8hHuMh1jNEgJFVDvnVnRt803x2EwAuMRwNo34Idhj4ZMi5kbnNjcnlwdC1jZXJ0LnF1YWQ5Lm5ldA', dnsCryptExplanation = 'Quad9 (anycast) no-dnssec/no-log/no-filter/ecs 9.9.9.12 - 149.112.112.12' where dnsCryptName = 'Quad9' and id = 5"
                    )
                }

                private fun modifyRethinkDnsUrls(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "UPDATE RethinkDnsEndpoint set url = case when url = 'https://max.rethinkdns.com/1:EMABAADgIAA='  then 'https://max.rethinkdns.com/pec' else 'https://sky.rethinkdns.com/pec' end where name = 'RDNS Adult' and isCustom = 0"
                    )
                    db.execSQL(
                        "UPDATE RethinkDnsEndpoint set url = case when url = 'https://max.rethinkdns.com/1:4AIAgAABAHAgAA=='  then 'https://max.rethinkdns.com/sec' else 'https://sky.rethinkdns.com/sec' end where name = 'RDNS Security' and isCustom = 0"
                    )
                    db.execSQL(
                        "UPDATE RethinkDnsEndpoint set blocklistCount = 0 where isCustom = 0 and name != 'RDNS Plus'"
                    )
                }

                private fun modifyAppInfo(db: SupportSQLiteDatabase) {
                    with(db) {
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
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 2, connectionStatus = 3 where firewallStatus = 2"
                        )
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 3, connectionStatus = 3 where firewallStatus = 3"
                        )
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 4, connectionStatus = 3 where firewallStatus = 4"
                        )
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 7, connectionStatus = 3 where firewallStatus = 7"
                        )
                        execSQL("UPDATE AppInfo set firewallStatus = 5 where firewallStatus = 1")
                        execSQL("DROP TABLE AppInfo_backup")
                    }
                }
            }

        private val MIGRATION_17_18: Migration =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    with(db) {
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 2, connectionStatus = 3 where firewallStatus = 2"
                        )
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 3, connectionStatus = 3 where firewallStatus = 3"
                        )
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 4, connectionStatus = 3 where firewallStatus = 4"
                        )
                        execSQL(
                            "UPDATE AppInfo set firewallStatus = 7, connectionStatus = 3 where firewallStatus = 7"
                        )
                    }
                }
            }

        private fun migration1819(context: Context): Migration =
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    with(db) {
                        execSQL("DROP TABLE IF EXISTS WgConfigFiles")
                        execSQL("DROP TABLE IF EXISTS ProxyApplicationMapping")
                        execSQL(
                            "CREATE TABLE WgConfigFiles('id' INTEGER NOT NULL, 'name' TEXT NOT NULL, 'configPath' TEXT NOT NULL, 'serverResponse' TEXT NOT NULL, 'isActive' INTEGER NOT NULL, 'isDeletable' INTEGER NOT NULL, PRIMARY KEY (id))"
                        )
                        execSQL(
                            "CREATE TABLE ProxyApplicationMapping('uid' INTEGER NOT NULL, 'packageName' TEXT NOT NULL, 'appName' TEXT NOT NULL, 'proxyName' TEXT NOT NULL, 'isActive' INTEGER NOT NULL, 'proxyId' TEXT NOT NULL ,PRIMARY KEY (uid, packageName, proxyId))"
                        )
                        execSQL(
                            "INSERT INTO ProxyApplicationMapping SELECT uid, packageName, appName, '', 1, '' FROM AppInfo order by lower(appName)"
                        )
                        execSQL("DROP TABLE IF EXISTS TcpProxyEndpoint")
                        execSQL(
                            "CREATE TABLE TcpProxyEndpoint ('id' INTEGER NOT NULL, 'name' TEXT NOT NULL, 'token' TEXT NOT NULL, 'url' TEXT NOT NULL, 'paymentStatus' INTEGER NOT NULL, 'isActive' INTEGER NOT NULL, PRIMARY KEY (id))"
                        )
                        execSQL(
                            "INSERT INTO TcpProxyEndpoint(id, name, token, url, paymentStatus, isActive) VALUES(0, 'Default', '', 'proxy.nile.workers.dev/ws/', 0, 0)"
                        )
                        execSQL(
                            "ALTER TABLE AppInfo add column downloadBytes INTEGER DEFAULT 0 NOT NULL"
                        )
                        execSQL(
                            "ALTER TABLE AppInfo add column uploadBytes INTEGER DEFAULT 0 NOT NULL"
                        )
                        // doh
                        execSQL(
                            "UPDATE DoHEndpoint set dohExplanation = 'R.string.cloudflare_dns_desc' where dohName = 'Cloudflare'"
                        )
                        execSQL(
                            "UPDATE DoHEndpoint set dohExplanation = 'R.string.cloudflare_family_dns_desc' where dohName = 'Cloudflare Family'"
                        )
                        execSQL(
                            "UPDATE DoHEndpoint set dohExplanation = 'R.string.cloudflare_security_dns_desc' where dohName = 'Cloudflare Security'"
                        )
                        execSQL(
                            "UPDATE DoHEndpoint set dohExplanation = 'R.string.google_dns_desc' where dohName = 'Google'"
                        )
                        execSQL(
                            "UPDATE DoHEndpoint set dohExplanation = 'R.string.cleanbrowsing_family_dns_desc' where dohName = 'CleanBrowsing Family'"
                        )
                        execSQL(
                            "UPDATE DoHEndpoint set dohExplanation = 'R.string.cleanbrowsing_adult_dns_desc' where dohName = 'CleanBrowsing Adult'"
                        )
                        execSQL(
                            "UPDATE DoHEndpoint set dohExplanation = 'R.string.quad9_dns_desc' where dohName = 'Quad9 Secure'"
                        )
                        // dns crypt
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptExplanation = 'R.string.crypt_cleanbrowsing_family_desc' where dnsCryptName = 'Cleanbrowsing Family'"
                        )
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptExplanation = 'R.string.crypt_adguard_desc' where dnsCryptName = 'Adguard'"
                        )
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptExplanation = 'R.string.crypt_adguard_family_desc' where dnsCryptName = 'Adguard Family'"
                        )
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptExplanation = 'R.string.crypt_quad9_security_desc' where dnsCryptName = 'Quad9 Security'"
                        )
                        execSQL(
                            "UPDATE DNSCryptEndpoint set dnsCryptExplanation = 'R.string.crypt_quad9_desc' where dnsCryptName = 'Quad9'"
                        )
                        // dns crypt relay
                        execSQL(
                            "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayExplanation = 'R.string.crypt_relay_netherlands' where dnsCryptRelayName = 'Netherlands'"
                        )
                        execSQL(
                            "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayExplanation = 'R.string.crypt_relay_france' where dnsCryptRelayName = 'France'"
                        )
                        execSQL(
                            "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayExplanation = 'R.string.crypt_relay_sweden' where dnsCryptRelayName = 'Sweden'"
                        )
                        execSQL(
                            "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayExplanation = 'R.string.crypt_relay_us' where dnsCryptRelayName = 'US - Los Angeles, CA'"
                        )
                        execSQL(
                            "UPDATE DNSCryptRelayEndpoint set dnsCryptRelayExplanation = 'R.string.crypt_relay_singapore' where dnsCryptRelayName = 'Singapore'"
                        )
                        execSQL(
                            "ALTER TABLE DoHEndpoint ADD COLUMN isSecure INTEGER NOT NULL DEFAULT 1"
                        )
                        execSQL(
                            "UPDATE DNSProxyEndpoint set proxyAppName = 'None' where proxyAppName = 'Nobody'"
                        )
                    }
                }
            }

        private val MIGRATION_19_20: Migration =
            object : Migration(19, 20) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // quad9
                    db.execSQL(
                        "UPDATE DnsCryptEndpoint set dnsCryptURL = 'sdns://AQYAAAAAAAAADTkuOS45LjEyOjg0NDMgZ8hHuMh1jNEgJFVDvnVnRt803x2EwAuMRwNo34Idhj4ZMi5kbnNjcnlwdC1jZXJ0LnF1YWQ5Lm5ldA' where id = 5"
                    )
                    // quad9 security
                    db.execSQL(
                        "UPDATE DnsCryptEndpoint set dnsCryptURL = 'sdns://AQMAAAAAAAAAEjE0OS4xMTIuMTEyLjk6ODQ0MyBnyEe4yHWM0SAkVUO-dWdG3zTfHYTAC4xHA2jfgh2GPhkyLmRuc2NyeXB0LWNlcnQucXVhZDkubmV0' where id = 4"
                    )
                    Logger.i(LOG_TAG_APP_DB, "Migrating to version 20")
                }
            }

        private val MIGRATION_20_21: Migration =
            object : Migration(20, 21) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE 'DoTEndpoint' ('id' INTEGER NOT NULL, 'name' TEXT NOT NULL, 'url' TEXT NOT NULL, 'desc' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL, 'isSecure' INTEGER NOT NULL, 'latency' INTEGER NOT NULL, 'modifiedDataTime' INTEGER NOT NULL, PRIMARY KEY (id))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS 'ODoHEndpoint' ('id' INTEGER NOT NULL, 'name' TEXT NOT NULL, 'proxy' TEXT NOT NULL, 'resolver' TEXT NOT NULL, 'proxyIps' TEXT NOT NULL, 'desc' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL, 'latency' INTEGER NOT NULL, 'modifiedDataTime' INTEGER NOT NULL, PRIMARY KEY (id))"
                    )
                    db.execSQL("delete from ODoHEndpoint")
                    db.execSQL("delete from DoTEndpoint")
                    // insert default odoh endpoints
                    db.execSQL(
                        "INSERT INTO ODoHEndpoint(id, name, proxy, resolver, proxyIps, desc, isSelected, isCustom, latency, modifiedDataTime) VALUES(0, 'Cloudflare', '', 'https://odoh.cloudflare-dns.com/dns-query', '', 'Cloudflare ODoH server', 0, 0, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO ODoHEndpoint(id, name, proxy, resolver, proxyIps, desc, isSelected, isCustom, latency, modifiedDataTime) VALUES(1, 'ODoH Crypto', '', 'https://odoh.crypto.sx/dns-query', '', 'ODoH target server. Anycast, no logs. Backend hosted by Scaleway. Maintained by Frank Denis.', 0, 0, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO ODoHEndpoint(id, name, proxy, resolver, proxyIps, desc, isSelected, isCustom, latency, modifiedDataTime) VALUES(2, 'Ibksturm', '', 'https://ibksturm.synology.me/dns-query', '', 'ODoH target server hosted by Ibksturm. No logs, No Filter, DNSSEC.', 0, 0, 0, 0)"
                    )
                    // insert default DoT endpoints
                    db.execSQL(
                        "INSERT INTO DoTEndpoint(id, name, url, desc, isSelected, isCustom, isSecure, latency, modifiedDataTime) VALUES(0, 'Cloudflare', 'tls://1dot1dot1dot1.cloudflare-dns.com', 'Cloudflare’s DNS over TLS. No blocking.', 0, 0, 1, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO DoTEndpoint(id, name, url, desc, isSelected, isCustom, isSecure, latency, modifiedDataTime) VALUES(1, 'Cloudflare family', 'tls://family.cloudflare-dns.com', 'Cloudflare’s DNS over TLS. Blocks Malware and Adult content.', 0, 0, 1, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO DoTEndpoint(id, name, url, desc, isSelected, isCustom, isSecure, latency, modifiedDataTime) VALUES(2, 'Adguard', 'tls://dns.adguard-dns.com', 'Cloudflare’s DNS over TLS. Block ads, tracking, and phishing.', 0, 0, 1, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO DoTEndpoint(id, name, url, desc, isSelected, isCustom, isSecure, latency, modifiedDataTime) VALUES(3, 'Mullvad Ad-block', 'tls://adblock.dns.mullvad.net', 'Mullvad’s DNS over TLS. Includes ad-blocking and tracker blocking.', 0, 0, 1, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO DoTEndpoint(id, name, url, desc, isSelected, isCustom, isSecure, latency, modifiedDataTime) VALUES(4, 'Mullvad Extended', 'tls://extended.dns.mullvad.net', 'Mullvad’s DNS over TLS. Includes ad-blocking, tracker, malware and social media blocking.', 0, 0, 1, 0, 0)"
                    )
                    db.execSQL(
                        "ALTER TABLE WgConfigFiles ADD COLUMN isLockdown INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        "ALTER TABLE WgConfigFiles ADD COLUMN isCatchAll INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        "ALTER TABLE WgConfigFiles ADD COLUMN oneWireGuard INTEGER NOT NULL DEFAULT 0"
                    )
                    // socks5
                    val pappSocks5 =
                        "CASE WHEN EXISTS (select proxyName from ProxyEndpoint_backup where proxyName = 'Socks5') THEN (select proxyName from ProxyEndpoint_backup where proxyName = 'Socks5') ELSE '' END"
                    val pipSocks5 =
                        "CASE WHEN EXISTS (select proxyIP from ProxyEndpoint_backup where proxyName = 'Socks5') THEN (select proxyIP from ProxyEndpoint_backup where proxyName = 'Socks5') ELSE '127.0.0.1' END"
                    val portSocks5 =
                        "CASE WHEN EXISTS (select proxyPort from ProxyEndpoint_backup where proxyName = 'Socks5') THEN (select proxyPort from ProxyEndpoint_backup where proxyName = 'Socks5') ELSE 9050 END"
                    val unameSocks5 =
                        "CASE WHEN EXISTS (select userName from ProxyEndpoint_backup where proxyName = 'Socks5') THEN (select userName from ProxyEndpoint_backup where proxyName = 'Socks5') ELSE '' END"
                    val pwdSocks5 =
                        "CASE WHEN EXISTS (select password from ProxyEndpoint_backup where proxyName = 'Socks5') THEN (select password from ProxyEndpoint_backup where proxyName = 'Socks5') ELSE '' END"
                    val isSelectedSocks5 =
                        "CASE WHEN EXISTS (select isSelected from ProxyEndpoint_backup where proxyName = 'Socks5') THEN (select isSelected from ProxyEndpoint_backup where proxyName = 'Socks5') ELSE 0 END"
                    val isUDPSocks5 =
                        "CASE WHEN EXISTS (select isUDP from ProxyEndpoint_backup where proxyName = 'Socks5') THEN (select isUDP from ProxyEndpoint_backup where proxyName = 'Socks5') ELSE 0 END"
                    // orbot
                    val pipOrbot =
                        "CASE WHEN EXISTS (select proxyIP from ProxyEndpoint_backup where proxyName = 'ORBOT') THEN (select proxyIP from ProxyEndpoint_backup where proxyName = 'ORBOT') ELSE '127.0.0.1' END"
                    val portOrbot =
                        "CASE WHEN EXISTS (select proxyPort from ProxyEndpoint_backup where proxyName = 'ORBOT') THEN (select proxyPort from ProxyEndpoint_backup where proxyName = 'ORBOT') ELSE 9050 END"
                    val isSelectedOrbot =
                        "CASE WHEN EXISTS (select isSelected from ProxyEndpoint_backup where proxyName = 'ORBOT') THEN (select isSelected from ProxyEndpoint_backup where proxyName = 'ORBOT') ELSE 0 END"

                    // backup the table ProxyEndpoint
                    db.execSQL("DROP TABLE IF EXISTS ProxyEndpoint_backup")
                    db.execSQL(
                        "CREATE TABLE 'ProxyEndpoint_backup' ('id' INTEGER NOT NULL, 'proxyName' TEXT NOT NULL, 'proxyMode' INTEGER NOT NULL, 'proxyType' TEXT NOT NULL, 'proxyAppName' TEXT NOT NULL, 'proxyIP' TEXT NOT NULL, 'userName' TEXT NOT NULL, 'password' TEXT NOT NULL, 'proxyPort' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL, 'isUDP' INTEGER NOT NULL, 'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL, PRIMARY KEY (id))"
                    )
                    db.execSQL(
                        "INSERT INTO ProxyEndpoint_backup SELECT id, proxyName, proxyMode, proxyType, proxyAppName, proxyIP, userName, password, proxyPort, isSelected, isCustom, isUDP, modifiedDataTime, latency FROM ProxyEndpoint"
                    )
                    db.execSQL("DELETE FROM ProxyEndpoint")
                    db.execSQL(
                        "INSERT INTO ProxyEndpoint (proxyName, proxyMode, proxyType, proxyAppName, proxyIP, userName, password, proxyPort, isSelected, isCustom, isUDP, modifiedDataTime, latency) VALUES('SOCKS5', 0, 'NONE', ($pappSocks5), ($pipSocks5), ($unameSocks5), ($pwdSocks5), ($portSocks5), ($isSelectedSocks5), 0, ($isUDPSocks5), 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO ProxyEndpoint (proxyName, proxyMode, proxyType, proxyAppName, proxyIP, userName, password, proxyPort, isSelected, isCustom, isUDP, modifiedDataTime, latency) VALUES('HTTP', 1, 'NONE', '', '', '', '', 0, 0, 0, 0, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO ProxyEndpoint (proxyName, proxyMode, proxyType, proxyAppName, proxyIP, userName, password, proxyPort, isSelected, isCustom, isUDP, modifiedDataTime, latency) VALUES('SOCKS5 Orbot', 2, 'NONE', 'org.torproject.android', ($pipOrbot), '', '', ($portOrbot), ($isSelectedOrbot), 0, 0, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO ProxyEndpoint (proxyName, proxyMode, proxyType, proxyAppName, proxyIP, userName, password, proxyPort, isSelected, isCustom, isUDP, modifiedDataTime, latency) VALUES('HTTP Orbot', 3, 'NONE', 'org.torproject.android', '', '', '', 0, 0, 0, 0, 0, 0)"
                    )
                    db.execSQL("DROP TABLE IF EXISTS ProxyEndpoint_backup")
                    Logger.i(LOG_TAG_APP_DB, "MIGRATION_20_21: added DoT and ODoH endpoints")
                }
            }

        private val MIGRATION_21_22: Migration =
            object : Migration(21, 22) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // fix: migration with the WgConfigFiles seen in play store crash
                    try {
                        if (!doesColumnExistInTable(db, "WgConfigFiles", "isLockdown")) {
                            db.execSQL(
                                "ALTER TABLE WgConfigFiles ADD COLUMN isLockdown INTEGER NOT NULL DEFAULT 0"
                            )
                        }
                        Logger.i(LOG_TAG_APP_DB, "MIGRATION_21_22: added isLockdown column")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "isLockdown column already exists, ignore")
                    }
                    try {
                        if (!doesColumnExistInTable(db, "WgConfigFiles", "isCatchAll")) {
                            db.execSQL(
                                "ALTER TABLE WgConfigFiles ADD COLUMN isCatchAll INTEGER NOT NULL DEFAULT 0"
                            )
                        }
                        Logger.i(LOG_TAG_APP_DB, "MIGRATION_21_22: added isCatchAll column")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "isCatchAll column already exists, ignore")
                    }
                    try {
                        if (!doesColumnExistInTable(db, "WgConfigFiles", "oneWireGuard")) {
                            db.execSQL(
                                "ALTER TABLE WgConfigFiles ADD COLUMN oneWireGuard INTEGER NOT NULL DEFAULT 0"
                            )
                        }
                        Logger.i(LOG_TAG_APP_DB, "MIGRATION_21_22: added oneWireGuard column")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "oneWireGuard column already exists, ignore")
                    }
                }
            }

        private val MIGRATION_22_23: Migration =
            object : Migration(22, 23) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE AppInfo ADD COLUMN isProxyExcluded INTEGER NOT NULL DEFAULT 0"
                    )
                    Logger.i(LOG_TAG_APP_DB, "MIGRATION_22_23: added isProxyExcluded column")
                }
            }

        private val MIGRATION_23_24: Migration =
            object : Migration(23, 24) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "UPDATE DoTEndpoint set desc = 'Adguard DNS over TLS. Blocks ads, tracking, and phishing.' where name = 'Adguard' and id = 2"
                    )
                }
            }

        // migration part of v055o
        private val MIGRATION_24_25: Migration =
            object : Migration(24, 25) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE 'RpnProxy' (
                            'id' INTEGER NOT NULL,
                            'name' TEXT NOT NULL,
                            'configPath' TEXT NOT NULL,
                            'serverResPath' TEXT NOT NULL,
                            'isActive' INTEGER NOT NULL,
                            'isLockdown' INTEGER NOT NULL,
                            'createdTs' INTEGER NOT NULL,
                            'modifiedTs' INTEGER NOT NULL,
                            'lastRefreshTime' INTEGER NOT NULL DEFAULT 0,
                            'misc' TEXT NOT NULL,
                            'tunId' TEXT NOT NULL,
                            'latency' INTEGER NOT NULL,
                            PRIMARY KEY (id)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE 'WgHopMap' (
                            'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            'src' TEXT NOT NULL,
                            'hop' TEXT NOT NULL,
                            'isActive' INTEGER NOT NULL,
                            'status' TEXT NOT NULL
                        )
                        """.trimIndent()
                    )

                    try {
                        db.execSQL("ALTER TABLE CustomDomain ADD COLUMN proxyId TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE CustomDomain ADD COLUMN proxyCC TEXT NOT NULL DEFAULT ''")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "proxyId, proxyCC; columns already exist, ignore")
                    }

                    try {
                        db.execSQL("ALTER TABLE CustomIp ADD COLUMN proxyId TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE CustomIp ADD COLUMN proxyCC TEXT NOT NULL DEFAULT ''")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "proxyId, proxyCC; columns already exist, ignore")
                    }

                    try {
                        db.execSQL("ALTER TABLE AppInfo ADD COLUMN tombstoneTs INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "tombstoneTs: column already exists, ignore")
                    }

                    try {
                        db.execSQL("ALTER TABLE WgConfigFiles ADD COLUMN useOnlyOnMetered INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "useOnlyOnMetered: column already exists, ignore")
                    }

                    db.execSQL(
                        """
                            CREATE TABLE IF NOT EXISTS SubscriptionStatus (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            accountId TEXT NOT NULL,
                            purchaseToken TEXT NOT NULL,
                            productId TEXT NOT NULL,
                            planId TEXT NOT NULL,
                            sessionToken TEXT NOT NULL,
                            productTitle TEXT NOT NULL,
                            state INTEGER NOT NULL DEFAULT 0,
                            status INTEGER NOT NULL DEFAULT -1,
                            lastUpdatedTs INTEGER NOT NULL DEFAULT 0,
                            purchaseTime INTEGER NOT NULL DEFAULT 0,
                            accountExpiry INTEGER NOT NULL DEFAULT 0,
                            billingExpiry INTEGER NOT NULL DEFAULT 0,
                            developerPayload TEXT NOT NULL
                            )""".trimIndent()
                    )

                    db.execSQL(
                    """
                            CREATE TABLE IF NOT EXISTS SubscriptionStateHistory (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            subscriptionId INTEGER NOT NULL,
                            fromState INTEGER NOT NULL,
                            toState INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL DEFAULT 0,
                            reason TEXT)
                         """.trimIndent()
                    )
                }
            }

        private val MIGRATION_25_26: Migration =
            object : Migration(25, 26) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    try {
                        db.execSQL("ALTER TABLE WgConfigFiles ADD COLUMN ssidEnabled INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "MIGRATION_25_26: ssidEnabled already exists")
                    }
                    try {
                        db.execSQL("ALTER TABLE WgConfigFiles ADD COLUMN ssids TEXT NOT NULL DEFAULT ''")
                    } catch (_: Exception) {
                        Logger.i(LOG_TAG_APP_DB, "MIGRATION_25_26: ssids already exists")
                    }
                    Logger.i(LOG_TAG_APP_DB, "MIGRATION_25_26: added ssidEnabled & ssids columns")
                }
            }

        private val MIGRATION_26_27: Migration =
            object : Migration(26, 27) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // delete the column isLockdown from WgConfigFiles
                    db.execSQL("CREATE TABLE 'WgConfigFiles_new' ('id' INTEGER NOT NULL, 'name' TEXT NOT NULL, 'configPath' TEXT NOT NULL, 'serverResponse' TEXT NOT NULL, 'isActive' INTEGER NOT NULL, 'isDeletable' INTEGER NOT NULL, 'isCatchAll' INTEGER NOT NULL, 'oneWireGuard' INTEGER NOT NULL, 'useOnlyOnMetered' INTEGER NOT NULL, 'ssidEnabled' INTEGER NOT NULL, 'ssids' TEXT NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO WgConfigFiles_new SELECT id, name, configPath, serverResponse, isActive, isDeletable, isCatchAll, oneWireGuard, useOnlyOnMetered, ssidEnabled, ssids FROM WgConfigFiles")
                    db.execSQL("DROP TABLE WgConfigFiles")
                    db.execSQL("ALTER TABLE WgConfigFiles_new RENAME TO WgConfigFiles")
                    // insert new columns with default values (modifiedTs)
                    db.execSQL("ALTER TABLE WgConfigFiles ADD COLUMN modifiedTs INTEGER NOT NULL DEFAULT 0")
                    Logger.i(LOG_TAG_APP_DB, "MIGRATION_26_27: removed isLockdown column")
                }
            }

        private val MIGRATION_27_28: Migration =
            object : Migration(27, 28) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Add modifiedTs column to AppInfo table to track when firewall/proxy rules change
                    try {
                        db.execSQL("ALTER TABLE AppInfo ADD COLUMN modifiedTs INTEGER NOT NULL DEFAULT 0")
                        // Backfill all existing rows with 0 (already done by DEFAULT 0)
                        Logger.i(LOG_TAG_APP_DB, "MIGRATION_27_28: added modifiedTs column to AppInfo")
                    } catch (e: Exception) {
                        Logger.e(LOG_TAG_APP_DB, "MIGRATION_27_28: modifiedTs column already exists, ignore", e)
                    }
                }
            }


        // ref: stackoverflow.com/a/57204285
        private fun doesColumnExistInTable(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnToCheck: String
        ): Boolean {
            try {
                db.query("SELECT * FROM $tableName LIMIT 0", emptyArray())
                    .use { cursor -> return cursor.getColumnIndex(columnToCheck) != -1 }
            } catch (_: Exception) {
                return false
            }
        }
    }

    // fixme: revisit the links to remove the pragma for each table
    // https://stackoverflow.com/questions/49030258/how-to-vacuum-roomdatabase
    // https://stackoverflow.com/questions/50987119/backup-room-databas
    fun checkPoint() {
        appDatabaseRawQueries().checkpoint(SimpleSQLiteQuery(PRAGMA))
        appDatabaseRawQueries().vacuum(SimpleSQLiteQuery("VACUUM"))
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

    abstract fun appDatabaseRawQueries(): AppDatabaseRawQueryDao

    abstract fun wgConfigFilesDAO(): WgConfigFilesDAO

    abstract fun wgApplicationMappingDao(): ProxyApplicationMappingDAO

    abstract fun tcpProxyEndpointDao(): TcpProxyDAO

    abstract fun dotEndpointDao(): DoTEndpointDAO

    abstract fun odohEndpointDao(): ODoHEndpointDAO

    abstract fun rpnProxyDao(): RpnProxyDao

    abstract fun wgHopMapDao(): WgHopMapDao

    abstract fun subscriptionStatusDao(): SubscriptionStatusDao

    abstract fun subscriptionStateHistoryDao(): SubscriptionStateHistoryDao

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

    fun wgConfigFilesRepository() = WgConfigFilesRepository(wgConfigFilesDAO())

    fun wgApplicationMappingRepository() = ProxyAppMappingRepository(wgApplicationMappingDao())

    fun tcpProxyEndpointRepository() = TcpProxyRepository(tcpProxyEndpointDao())

    fun dotEndpointRepository() = DoTEndpointRepository(dotEndpointDao())

    fun odohEndpointRepository() = ODoHEndpointRepository(odohEndpointDao())

    fun rpnProxyRepository() = RpnProxyRepository(rpnProxyDao())

    fun wgHopMapRepository() = WgHopMapRepository(wgHopMapDao())

    fun subscriptionStatusRepository() = SubscriptionStatusRepository(subscriptionStatusDao())

}
