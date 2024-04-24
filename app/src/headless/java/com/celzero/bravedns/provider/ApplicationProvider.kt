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
package com.celzero.bravedns.provider

import Logger.LOG_PROVIDER
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.service.FirewallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ApplicationProvider : ContentProvider() {

    private val appInfoRepository by inject<AppInfoRepository>()

    companion object {
        private const val AUTHORITY = "com.celzero.bravedns.appprovider"
        private const val URI_APP = "vnd.android.cursor.dir/$AUTHORITY.apps"

        // apps: Uri.parse("content://$AUTHORITY/apps")
        val uriMatcher =
            UriMatcher(UriMatcher.NO_MATCH).apply {
                addURI(AUTHORITY, "apps", 1)
                addURI(AUTHORITY, "apps/#", 2)
            }

        private const val RESOLVER_PACKAGE_NAME = "com.celzero.interop"
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
    ): Cursor {
        if (!isValidRequest(uri)) {
            Logger.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("Invalid URI, cannot update without ID$uri")
        }
        return appInfoRepository.getAppsCursor()
    }

    override fun getType(uri: Uri): String {
        uriMatcher.match(uri).let {
            return when (it) {
                1 -> URI_APP
                2 -> URI_APP
                else -> throw IllegalArgumentException("Unknown URI: $uri")
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!isValidRequest(uri)) {
            Logger.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("invalid uri, cannot update without ID$uri")
        }
        val appInfo = AppInfo(values)

        val id = appInfoRepository.cpInsert(appInfo)
        // update the app info cache
        CoroutineScope(Dispatchers.IO).launch { FirewallManager.load() }
        context?.contentResolver?.notifyChange(uri, null)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!isValidRequest(uri)) {
            Logger.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("Invalid URI, cannot update without ID$uri")
        }
        Logger.i(LOG_PROVIDER, "request to delete app, parameters $uri, $selection, $selectionArgs")
        val id = ContentUris.parseId(uri).toInt()
        val count = appInfoRepository.cpDelete(id)
        // update the app info cache
        CoroutineScope(Dispatchers.IO).launch { FirewallManager.load() }
        context?.contentResolver?.notifyChange(uri, null)
        return count
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selectionClause: String?,
        selectionArgs: Array<out String>?
    ): Int {
        if (!isValidRequest(uri)) {
            Logger.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("invalid uri, cannot update without ID$uri")
        }

        val context = context ?: return 0
        val appInfo = AppInfo(values)

        if (selectionClause.isNullOrEmpty()) {
            val count = appInfoRepository.cpUpdate(appInfo)
            context.contentResolver?.notifyChange(uri, null)
            // update the app info cache
            CoroutineScope(Dispatchers.IO).launch { FirewallManager.load() }
            return count
        } else if (selectionClause.contains("uid") || selectionClause.contains("packageName")) {
            val c = selectionClause.count { it == '?' }
            // if the selection clause contains uid or packageName, then the selectionArgs should
            // contain the values for the same.
            if (c != (selectionArgs?.size ?: 0)) {
                Logger.e(LOG_PROVIDER, "invalid selection clause: $selectionClause")
                throw java.lang.IllegalArgumentException(
                    "invalid selection clause: $selectionClause"
                )
            }
            // replace the '?' in selectionClause with selectionArgs array
            var clause: String = selectionClause
            for (i in 0 until c) {
                clause = clause.replaceFirst("?", selectionArgs?.get(i) ?: "")
            }
            Logger.i(
                LOG_PROVIDER,
                "selection ${appInfo.appName}, ${appInfo.uid}, ${appInfo.firewallStatus} clause: $clause"
            )
            val count = appInfoRepository.cpUpdate(appInfo, clause)
            // update the app info cache
            CoroutineScope(Dispatchers.IO).launch { FirewallManager.load() }
            context.contentResolver?.notifyChange(uri, null)
            return count
        } else {
            Logger.e(LOG_PROVIDER, "invalid selection clause: $selectionClause")
            throw java.lang.IllegalArgumentException("invalid selection clause: $selectionClause")
        }
    }

    private fun isValidRequest(uri: Uri): Boolean {
        // check the calling package
        if (callingPackage != RESOLVER_PACKAGE_NAME) {
            Logger.e(LOG_PROVIDER, "request received from unknown package: $callingPackage")
            return false
        }

        uriMatcher.match(uri).let {
            return when (it) {
                1 -> true
                2 -> true
                else -> false
            }
        }
    }
}
