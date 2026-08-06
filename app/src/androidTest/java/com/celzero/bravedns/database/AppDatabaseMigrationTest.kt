package com.celzero.bravedns.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.sqlite.db.SupportSQLiteOpenHelper

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val TEST_DB = "migration-test"

    @Test
    fun migrate30To31() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create version 30 schema (minimal to verify DB version)
                    db.execSQL("CREATE TABLE IF NOT EXISTS AppInfo (packageName TEXT PRIMARY KEY NOT NULL, appName TEXT NOT NULL, uid INTEGER NOT NULL, isSystemApp INTEGER NOT NULL, firewallStatus INTEGER NOT NULL DEFAULT 5, appCategory TEXT NOT NULL, wifiDataUsed INTEGER NOT NULL, mobileDataUsed INTEGER NOT NULL, connectionStatus INTEGER NOT NULL DEFAULT 3, screenOffAllowed INTEGER NOT NULL DEFAULT 0, backgroundAllowed INTEGER NOT NULL DEFAULT 0, tombstoneTs INTEGER NOT NULL DEFAULT 0, modifiedTs INTEGER NOT NULL DEFAULT 0, isProxyExcluded INTEGER NOT NULL DEFAULT 0, tempAllowEnabled INTEGER NOT NULL DEFAULT 0, tempAllowExpiryTime INTEGER NOT NULL DEFAULT 0, downloadBytes INTEGER NOT NULL DEFAULT 0, uploadBytes INTEGER NOT NULL DEFAULT 0)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        assertEquals(30, db.version)
        
        // Open again and migrate to 31
        AppDatabase.MIGRATION_30_31.migrate(db)
        
        // Verify table exists and has correct columns
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='Sponsor'")
        assertTrue("Sponsor table should exist", cursor.moveToFirst())
        cursor.close()

        val columnsCursor = db.query("PRAGMA table_info(Sponsor)")
        val columns = mutableListOf<String>()
        while (columnsCursor.moveToNext()) {
            columns.add(columnsCursor.getString(columnsCursor.getColumnIndexOrThrow("name")))
        }
        columnsCursor.close()

        assertTrue(columns.contains("id"))
        assertTrue(columns.contains("purchase_token"))
        assertTrue(columns.contains("product_id"))
        assertTrue(columns.contains("purchase_time"))
        assertTrue(columns.contains("sponsor_since"))
        assertTrue(columns.contains("consumed"))
        assertTrue(columns.contains("contribution_count"))
        assertTrue(columns.contains("last_contribution_time"))
        
        db.close()
        context.deleteDatabase(TEST_DB)
    }
}
