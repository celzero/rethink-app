/*
Copyright 2020 RethinkDNS developers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AppInfo::class, CategoryInfo::class, ConnectionTracker::class, BlockedConnections::class, DoHEndpoint::class
, DNSCryptEndpoint::class, DNSProxyEndpoint::class, DNSCryptRelayEndpoint::class,ProxyEndpoint::class,DNSLogs::class],version = 7,exportSchema = false)
abstract class AppDatabase : RoomDatabase(){

    companion object {
        const val currentVersion:Int = 7

        fun buildDatabase(context: Context) = Room.databaseBuilder(
            context, AppDatabase::class.java,"bravedns.db")
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2)
            .addMigrations(MIGRATION_2_3)
            .addMigrations(MIGRATION_3_4)
            .addMigrations(MIGRATION_4_5)
            .addMigrations(MIGRATION_5_6)
            .addMigrations(MIGRATION_6_7)
            .build()

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

        private val MIGRATION_3_4 : Migration = object  : Migration(3,4){
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE BlockedConnections ADD COLUMN isActive INTEGER DEFAULT 1 NOT NULL")
                database.execSQL("ALTER TABLE BlockedConnections ADD COLUMN ruleType TEXT DEFAULT 'RULE4' NOT NULL")
                database.execSQL("ALTER TABLE BlockedConnections ADD COLUMN modifiedDateTime INTEGER DEFAULT 0  NOT NULL")
                database.execSQL("UPDATE BlockedConnections set ruleType = 'RULE5' where uid = -1000")
                database.execSQL("ALTER TABLE ConnectionTracker ADD COLUMN blockedByRule TEXT")
                database.execSQL("UPDATE ConnectionTracker set blockedByRule = 'RULE4' where uid <> -1000 and isBlocked = 1")
                database.execSQL("UPDATE ConnectionTracker set blockedByRule = 'RULE5' where uid = -1000  and isBlocked = 1")
                database.execSQL("ALTER TABLE AppInfo add column whiteListUniv1 INTEGER DEFAULT 0 NOT NULL")
                database.execSQL("ALTER TABLE AppInfo add column whiteListUniv2 INTEGER DEFAULT 0 NOT NULL")
                database.execSQL("ALTER TABLE AppInfo add column isExcluded INTEGER DEFAULT 0 NOT NULL")
                database.execSQL("CREATE TABLE 'DoHEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dohName' TEXT NOT NULL, 'dohURL' TEXT NOT NULL,'dohExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) ")
                database.execSQL("CREATE TABLE 'DNSCryptEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dnsCryptName' TEXT NOT NULL, 'dnsCryptURL' TEXT NOT NULL,'dnsCryptExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) ")
                database.execSQL("CREATE TABLE 'DNSCryptRelayEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'dnsCryptRelayName' TEXT NOT NULL, 'dnsCryptRelayURL' TEXT NOT NULL,'dnsCryptRelayExplanation' TEXT, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) ")
                database.execSQL("CREATE TABLE 'DNSProxyEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'proxyName' TEXT NOT NULL, 'proxyType' TEXT NOT NULL,'proxyAppName' TEXT , 'proxyIP' TEXT, 'proxyPort' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) ")
                database.execSQL("CREATE TABLE 'ProxyEndpoint' ( 'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'proxyName' TEXT NOT NULL,'proxyMode' INTEGER NOT NULL, 'proxyType' TEXT NOT NULL,'proxyAppName' TEXT , 'proxyIP' TEXT, 'userName' TEXT , 'password' TEXT, 'proxyPort' INTEGER NOT NULL, 'isSelected' INTEGER NOT NULL, 'isCustom' INTEGER NOT NULL , 'isUDP' INTEGER NOT NULL,'modifiedDataTime' INTEGER NOT NULL, 'latency' INTEGER NOT NULL) ")
                //Perform insert of endpoints
                //database.execSQL("INSERT INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(1,'No filter','https://cloudflare-dns.com/dns-query','Does not block any DNS requests. Uses Cloudflare''s 1.1.1.1 DNS endpoint.',0,0,0,0)")
                //database.execSQL("INSERT INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(2,'Family','https://family.cloudflare-dns.com/dns-query','Blocks malware and adult content. Uses Cloudflare''s 1.1.1.3 DNS endpoint.',0,0,0,0)")
                //database.execSQL("INSERT INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(3,'RethinkDNS Basic (default)','https://free.bravedns.com/dns-query','Blocks malware and more. Uses RethinkDNS''s non-configurable basic endpoint.',1,0,0,0)")
                //database.execSQL("INSERT INTO DoHEndpoint(id,dohName,dohURL,dohExplanation, isSelected,isCustom,modifiedDataTime,latency) values(4,'RethinkDNS Pro','https://free.bravedns.com/dns-query','Configurable DNS endpoint: Provides in-depth analytics of your Internet traffic, allows you to set custom rules and more. Coming soon.',0,0,0,0)")
            }
        }

        private val MIGRATION_4_5 : Migration = object  : Migration(4,5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DELETE from DNSProxyEndpoint")
                database.execSQL("UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:wBdgAIoBoB02kIAA5HI=' where id = 3")
                database.execSQL("UPDATE DNSCryptEndpoint set dnsCryptName='Quad9', dnsCryptURL='sdns://AQYAAAAAAAAAEzE0OS4xMTIuMTEyLjEwOjg0NDMgZ8hHuMh1jNEgJFVDvnVnRt803x2EwAuMRwNo34Idhj4ZMi5kbnNjcnlwdC1jZXJ0LnF1YWQ5Lm5ldA',dnsCryptExplanation='Quad9 (anycast) no-dnssec/no-log/no-filter 9.9.9.10 / 149.112.112.10' where id=5")
                database.execSQL("INSERT into DNSProxyEndpoint values (1,'Google','External','Nobody','8.8.8.8',53,0,0,0,0)")
                database.execSQL("INSERT into DNSProxyEndpoint values (2,'Cloudflare','External','Nobody','1.1.1.1',53,0,0,0,0)")
                database.execSQL("INSERT into DNSProxyEndpoint values (3,'Quad9','External','Nobody','9.9.9.9',53,0,0,0,0)")
                database.execSQL("UPDATE DNSCryptEndpoint set dnsCryptName ='Cleanbrowsing Family' where id = 1")
                database.execSQL("UPDATE DNSCryptEndpoint set dnsCryptName ='Adguard' where id = 2")
                database.execSQL("UPDATE DNSCryptEndpoint set dnsCryptName ='Adguard Family' where id = 3")
                database.execSQL("UPDATE DNSCryptEndpoint set dnsCryptName ='Cleanbrowsing Security' where id = 4")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-AMS-NL' where id = 1")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-FR' where id = 2")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-SE' where id = 3")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-CS-USCA' where id = 4")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Anon-Tiarap' where id = 5")
            }
        }

        private val MIGRATION_5_6 : Migration = object : Migration(5,6){
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE 'DNSLogs' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'queryStr' TEXT NOT NULL, 'time' INTEGER NOT NULL, 'flag' TEXT NOT NULL, 'resolver' TEXT NOT NULL, 'latency' INTEGER NOT NULL, 'typeName' TEXT NOT NULL, 'isBlocked' INTEGER NOT NULL, 'blockLists' LONGTEXT NOT NULL,  'serverIP' TEXT NOT NULL, 'relayIP' TEXT NOT NULL, 'responseTime' INTEGER NOT NULL, 'response' TEXT NOT NULL, 'status' TEXT NOT NULL,'dnsType' INTEGER NOT NULL) ")
                //https://basic.bravedns.com/1:YBIgACABAHAgAA== - New block list configured
                database.execSQL("UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:YBcgAIAQIAAIAABgIAA=' where id = 4")
                database.execSQL("UPDATE DNSCryptEndpoint set dnsCryptName='Quad9', dnsCryptURL='sdns://AQMAAAAAAAAADDkuOS45Ljk6ODQ0MyBnyEe4yHWM0SAkVUO-dWdG3zTfHYTAC4xHA2jfgh2GPhkyLmRuc2NyeXB0LWNlcnQucXVhZDkubmV0',dnsCryptExplanation='Quad9 (anycast) dnssec/no-log/filter 9.9.9.9 / 149.112.112.9' where id=5")
                database.execSQL("ALTER TABLE CategoryInfo add column numOfAppWhitelisted INTEGER DEFAULT 0 NOT NULL")
                database.execSQL("ALTER TABLE CategoryInfo add column numOfAppsExcluded INTEGER DEFAULT 0 NOT NULL")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Netherlands' where id = 1")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='France' where id = 2")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Sweden' where id = 3")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='US - Los Angeles, CA' where id = 4")
                database.execSQL("UPDATE DNSCryptRelayEndpoint set dnsCryptRelayName ='Singapore' where id = 5")
            }
        }

        private val MIGRATION_6_7 : Migration = object : Migration(6,7){
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE DoHEndpoint set dohURL  = 'https://security.cloudflare-dns.com/dns-query' where id = 3")
                database.execSQL("UPDATE DoHEndpoint set dohURL  = 'https://basic.bravedns.com/1:YBcgAIAQIAAIAABgIAA=' where id = 4")
            }
        }

    }

    abstract fun appInfoDAO(): AppInfoDAO
    abstract fun categoryInfoDAO(): CategoryInfoDAO
    abstract fun connectionTrackerDAO() : ConnectionTrackerDAO
    abstract fun blockedConnectionsDAO() : BlockedConnectionsDAO
    abstract fun dohEndpointsDAO () : DoHEndpointDAO
    abstract fun dnsCryptEndpointDAO() : DNSCryptEndpointDAO
    abstract fun dnsCryptRelayEndpointDAO() : DNSCryptRelayEndpointDAO
    abstract fun dnsProxyEndpointDAO() : DNSProxyEndpointDAO
    abstract fun proxyEndpointDAO() : ProxyEndpointDAO
    abstract fun dnsLogDAO() : DNSLogDAO

    fun appInfoRepository() = AppInfoRepository(appInfoDAO())
    fun categoryInfoRepository() = CategoryInfoRepository(categoryInfoDAO())
    fun connectionTrackerRepository() = ConnectionTrackerRepository(connectionTrackerDAO())
    fun blockedConnectionRepository() = BlockedConnectionsRepository(blockedConnectionsDAO())
    fun doHEndpointsRepository() = DoHEndpointRepository(dohEndpointsDAO())
    fun dnsCryptEndpointsRepository() = DNSCryptEndpointRepository(dnsCryptEndpointDAO())
    fun dnsCryptRelayEndpointsRepository() = DNSCryptRelayEndpointRepository(dnsCryptRelayEndpointDAO())
    fun dnsProxyEndpointRepository() = DNSProxyEndpointRepository(dnsProxyEndpointDAO())
    fun proxyEndpointRepository() = ProxyEndpointRepository(proxyEndpointDAO())
    fun dnsLogRepository() = DNSLogRepository(dnsLogDAO())

}