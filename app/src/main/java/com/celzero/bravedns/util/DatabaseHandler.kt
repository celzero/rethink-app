/*
Copyright 2020 RethinkDNS and its authors

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
package com.celzero.bravedns.util

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.celzero.bravedns.automaton.PermissionsManager

import com.celzero.bravedns.automaton.PermissionsManager.Rules

class DatabaseHandler(context: Context)  : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION){
    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "GZERO"
        private const val TABLE_PERMISSION_MANAGER = "PermissionManagerMapping"
        private const val KEY_PACKAGE_NAME = "package_name"
        private const val KEY_PACKAGE_RULE = "package_rule"
    }



    override fun onCreate(db: SQLiteDatabase?) {
        val CREATE_PERMISSION_MANAGER_TABLE = ("CREATE TABLE " + TABLE_PERMISSION_MANAGER + "("
                + KEY_PACKAGE_NAME + " TEXT PRIMARY KEY,"
                + KEY_PACKAGE_RULE + " INTEGER" + ")")
        db?.execSQL(CREATE_PERMISSION_MANAGER_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase?, p1: Int, p2: Int) {
        db!!.execSQL("DROP TABLE IF EXISTS $TABLE_PERMISSION_MANAGER" )
        onCreate(db)
    }

    fun addPackageName(packageName : String , rules : Int): Long{

        val db = this.writableDatabase
        val contentValues = ContentValues()
        contentValues.put(KEY_PACKAGE_NAME, packageName)
        contentValues.put(KEY_PACKAGE_RULE,rules)
        // Inserting Row
        val success = db.insert(TABLE_PERMISSION_MANAGER, null, contentValues)
        //2nd argument is String containing nullColumnHack
        db.close() // Closing database connection

        return success
    }

    fun getAllPackageDetails(): LinkedHashMap<String, Rules>{

        val packageList:LinkedHashMap<String, Rules> = LinkedHashMap()
        val selectQuery = "SELECT  * FROM $TABLE_PERMISSION_MANAGER"
        val db = this.readableDatabase
        var cursor: Cursor? = null
        try{
            cursor = db.rawQuery(selectQuery, null)
        }catch (e: SQLiteException) {
            db.execSQL(selectQuery)
            return LinkedHashMap()
        }

        var packageName: String
        var packageRule: Int
        if (cursor.moveToFirst()) {
            do {

                packageName = cursor.getString(cursor.getColumnIndex("package_name"))
                packageRule  = cursor.getInt(cursor.getColumnIndex("package_rule"))
                if(packageRule  == 0)
                    packageList[packageName] = Rules.NONE
                else if(packageRule == 1)
                    packageList[packageName] = Rules.BG_REMOVE
                else
                    packageList[packageName] = Rules.BG_REMOVE_FG_ADD
            } while (cursor.moveToNext())
        }
        cursor?.close()
        return packageList
    }


    fun getSpecificPackageRule(packageName : String ):Int{
        val packageRule : Int
        val selectQuery = "SELECT  * FROM $TABLE_PERMISSION_MANAGER where $KEY_PACKAGE_NAME = '$packageName'"
        val db = this.readableDatabase
        var cursor: Cursor? = null
        try{
            cursor = db.rawQuery(selectQuery, null)
        }catch (e: SQLiteException) {
            db.execSQL(selectQuery)
            return -1
        }

        if(cursor.moveToFirst()){

            packageRule  = cursor.getInt(cursor.getColumnIndex("package_rule"))
                return packageRule
        }
        cursor?.close()
        return -1
    }

    fun updatePackage(packageName : String, packageRule : Int): Long{
        val db = this.writableDatabase
        val contentValues = ContentValues()

        contentValues.put(KEY_PACKAGE_NAME, packageName)
        contentValues.put(KEY_PACKAGE_RULE,packageRule )

        if(packageRule  == 0)
            PermissionsManager.packageRules[packageName] = Rules.NONE
        else if(packageRule == 1)
            PermissionsManager.packageRules[packageName] = Rules.BG_REMOVE
        else if(packageRule == 2){
            PermissionsManager.packageRules[packageName] = Rules.BG_REMOVE_FG_ADD
        }


        var success = 0L
        val id = db.update(TABLE_PERMISSION_MANAGER,contentValues,"package_name='$packageName'",null)
        if(id == 0){
            success = db.insert(TABLE_PERMISSION_MANAGER,null,contentValues)
        }
        // Updating Row
        //val success = db.replace(TABLE_PERMISSION_MANAGER,"package_name",contentValues)
         //db.replace(TABLE_PERMISSION_MANAGER, contentValues,"package_name="+packageName,null)
        //2nd argument is String containing nullColumnHack
        db.close() // Closing database connection
        return success
    }


    fun updateAllPackage(packageName : String, packageRule : Int): Long{
        val db = this.writableDatabase
        val contentValues = ContentValues()

        contentValues.put(KEY_PACKAGE_NAME, packageName)
        contentValues.put(KEY_PACKAGE_RULE,packageRule  )

        if(packageRule  == 0)
            PermissionsManager.packageRules[packageName] = Rules.NONE
        else if(packageRule == 1)
            PermissionsManager.packageRules[packageName] = Rules.BG_REMOVE
        else if(packageRule == 2){
            PermissionsManager.packageRules[packageName] = Rules.BG_REMOVE_FG_ADD
        }


        var success = 0L
        val id = db.update(TABLE_PERMISSION_MANAGER,contentValues,"package_name='$packageName'",null)
        if(id == 0){
            success = db.insert(TABLE_PERMISSION_MANAGER,null,contentValues)
        }
        // Updating Row
        //val success = db.replace(TABLE_PERMISSION_MANAGER,"package_name",contentValues)
        //db.replace(TABLE_PERMISSION_MANAGER, contentValues,"package_name="+packageName,null)
        //2nd argument is String containing nullColumnHack
        db.close() // Closing database connection
        return success
    }



}