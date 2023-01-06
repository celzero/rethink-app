/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.provider

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_PROVIDER
import org.koin.android.ext.android.inject

class BlocklistProvider : ContentProvider() {

    private val localFileTagRepository by inject<RethinkLocalFileTagRepository>()
    private val persistentState by inject<PersistentState>()
    private val appDatabase by inject<AppDatabase>()

    companion object {
        private const val AUTHORITY = "com.celzero.bravedns.contentprovider"
        private const val URI_DNS = "vnd.android.cursor.dir/$AUTHORITY.blocklists"

        // blocklists: Uri.parse("content://$AUTHORITY/blocklists")
        // selected: Uri.parse("content://$AUTHORITY/blocklists/selected")
        // all: Uri.parse("content://$AUTHORITY/blocklists/all")
        // *: Uri.parse("content://$AUTHORITY/blocklists/*")
        val uriMatcher =
            UriMatcher(UriMatcher.NO_MATCH).apply {
                addURI(AUTHORITY, "blocklists", 1)
                addURI(AUTHORITY, "blocklists/selected", 2)
                addURI(AUTHORITY, "blocklists/all", 3)
                addURI(AUTHORITY, "blocklists/*", 4)
                addURI(AUTHORITY, "blocklists/#", 5)
            }

        // method name to update the preference
        private const val METHOD_UPDATE_STAMP = "content://$AUTHORITY/blocklists/stamp/update"
        // method name to get preference value
        private const val METHOD_GET_STAMP = "content://$AUTHORITY/blocklists/stamp/get"

        private const val STAMP = "stamp"

        private const val BLOCKLIST_TABLE = "RethinkLocalFileTag"

        private const val RESOLVER_PACKAGE_NAME = "com.celzero.contentresolversample"
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        // access the appDatabase directly and create using default SQLiteQueryBuilder
        // commented out this option in all the methods.
        // return
        // appDatabase.query(SupportSQLiteQueryBuilder.builder(BLOCKLIST_TABLE).selection(selection,
        // selectionArgs).columns(projection).orderBy(sortOrder).create())
        if (!isValidRequest(uri)) {
            Log.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("Invalid URI, cannot update without ID$uri")
        }

        var cursor: Cursor? = null
        if (context == null) {
            return null
        }
        Log.i(LOG_PROVIDER, "blocklists query with parameters $uri, $selection, $selectionArgs")
        uriMatcher.match(uri).let {
            cursor =
                when (it) {
                    // can be various options based on the uri/parse the selection and selection
                    // arguments to query from database
                    1 -> {
                        localFileTagRepository.contentGetFileTags()
                    }
                    2 -> {
                        localFileTagRepository.contentGetSelectedFileTags()
                    }
                    3 -> {
                        localFileTagRepository.contentGetAllFileTags()
                    }
                    4 -> {
                        localFileTagRepository.contentGetFileTagById(
                            ContentUris.parseId(uri).toInt()
                        )
                    }
                    else -> throw IllegalArgumentException("Unknown URI: $uri")
                }
        }
        cursor?.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    override fun getType(uri: Uri): String {
        uriMatcher.match(uri).let {
            return when (it) {
                1 -> URI_DNS
                2 -> URI_DNS
                3 -> URI_DNS
                4 -> URI_DNS
                else -> throw IllegalArgumentException("Unknown URI: $uri")
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!isValidRequest(uri)) {
            Log.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("Invalid URI, cannot update without ID$uri")
        }

        Log.i(LOG_PROVIDER, "request to insert new blocklist, parameters $values")
        val localFileTags = RethinkLocalFileTag().fromContentValues(values) ?: return null
        val id = localFileTagRepository.contentInsert(localFileTags)
        context?.contentResolver?.notifyChange(uri, null)
        // val retId: Long = appDatabase.openHelper.writableDatabase.insert(BLOCKLIST_TABLE, 0,
        // values)
        // return ContentUris.withAppendedId(uri, retId)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!isValidRequest(uri)) {
            Log.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("Invalid URI, cannot update without ID$uri")
        }
        /*return appDatabase.openHelper.writableDatabase.delete(
            BLOCKLIST_TABLE,
            selection,
            selectionArgs
        )*/
        Log.i(
            LOG_PROVIDER,
            "request to delete blocklist, parameters $uri, $selection, $selectionArgs"
        )
        val id = ContentUris.parseId(uri).toInt()
        val count = localFileTagRepository.contentDelete(id)
        context?.contentResolver?.notifyChange(uri, null)
        return count
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val bundle = Bundle()

        when (method) {
            METHOD_GET_STAMP -> {
                val stamp = persistentState.localBlocklistStamp
                Log.i(LOG_PROVIDER, "request for blocklist stamp, sending: $stamp")
                bundle.putString(STAMP, persistentState.localBlocklistStamp)
            }
            METHOD_UPDATE_STAMP -> {
                val rcvStamp = arg ?: ""
                Log.i(LOG_PROVIDER, "received stamp to update local blocklist: $rcvStamp")

                if (rcvStamp.isNotEmpty()) {
                    persistentState.localBlocklistStamp = rcvStamp
                }
            }
        }
        return bundle
    }

    @Throws(IllegalArgumentException::class)
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        if (!isValidRequest(uri)) {
            Log.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("invalid uri, cannot update without ID$uri")
        }

        // return appDatabase.openHelper.writableDatabase.update(BLOCKLIST_TABLE, 0, values,
        // selection, selectionArgs)
        val context = context ?: return 0
        val localFileTags = RethinkLocalFileTag().fromContentValues(values) ?: return 0
        // TODO: Update the stamp here
        val count = localFileTagRepository.contentUpdate(localFileTags)
        context.contentResolver?.notifyChange(uri, null)
        return count
    }

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        return super.bulkInsert(uri, values)
    }

    override fun applyBatch(
        operations: ArrayList<ContentProviderOperation>
    ): Array<ContentProviderResult> {
        return super.applyBatch(operations)
    }

    private fun isValidRequest(uri: Uri): Boolean {

        // check the calling package
        if (callingPackage != RESOLVER_PACKAGE_NAME) {
            Log.e(LOG_PROVIDER, "request received from unknown package: $callingPackage")
            return false
        }

        uriMatcher.match(uri).let {
            return when (it) {
                1 -> true
                2 -> true
                3 -> true
                4 -> true
                5 -> true
                else -> false
            }
        }
    }
}
