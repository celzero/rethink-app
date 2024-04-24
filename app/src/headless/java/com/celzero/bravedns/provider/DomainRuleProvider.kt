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

import Logger.LOG_PROVIDER
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class DomainRuleProvider : ContentProvider() {

    private val customDomainRepository by inject<CustomDomainRepository>()

    companion object {
        private const val AUTHORITY = "com.celzero.bravedns.domainrulesprovider"
        private const val URI_DOMAIN_RULES = "vnd.android.cursor.dir/$AUTHORITY.domainrules"

        // apps: Uri.parse("content://$AUTHORITY/apps")
        val uriMatcher =
            UriMatcher(UriMatcher.NO_MATCH).apply {
                addURI(AUTHORITY, "domainrules", 1)
                addURI(AUTHORITY, "domainrules/delete", 2)
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

        if (DEBUG)
            Logger.d(
                LOG_PROVIDER,
                "insert blocklists with uri: $uri, projection: $projection, selection: $selection, selectionArgs: $selectionArgs, sortOrder: $sortOrder"
            )
        return customDomainRepository.getRulesCursor()
    }

    override fun getType(uri: Uri): String {
        uriMatcher.match(uri).let {
            return when (it) {
                1 -> URI_DOMAIN_RULES
                2 -> URI_DOMAIN_RULES
                else -> throw IllegalArgumentException("Unknown URI: $uri")
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!isValidRequest(uri)) {
            Logger.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("invalid uri, cannot update without ID$uri")
        }
        val customDomain = CustomDomain(values)

        Logger.d(
            LOG_PROVIDER,
            "insert blocklists with uri: $uri, values: $values, obj: ${customDomain.domain}, ${customDomain.uid}, ${customDomain.status}"
        )
        val id = customDomainRepository.cpInsert(customDomain)
        context?.contentResolver?.notifyChange(uri, null)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!isValidRequest(uri)) {
            Logger.e(LOG_PROVIDER, "invalid uri, cannot update without ID: $uri")
            throw java.lang.IllegalArgumentException("Invalid URI, cannot update without ID$uri")
        }

        if (selection.isNullOrEmpty()) {
            Logger.e(LOG_PROVIDER, "invalid selection clause: $selection")
            throw java.lang.IllegalArgumentException("invalid selection clause: $selection")
        }

        Logger.i(LOG_PROVIDER, "request to delete app, parameters $uri, $selection, $selectionArgs")

        val domain =
            if (selection.contains("domain")) {
                selectionArgs?.get(0)
            } else {
                null
            }

        if (domain.isNullOrEmpty()) {
            Logger.e(LOG_PROVIDER, "required domain name on selection clause: $selection")
            throw java.lang.IllegalArgumentException(
                "required domain name on selection clause: $selection"
            )
        }

        val uid =
            if (selection.contains("uid")) {
                selectionArgs?.get(1)?.toInt()
            } else {
                Logger.e(LOG_PROVIDER, "required domain name on selection clause: $selection")
                throw java.lang.IllegalArgumentException(
                    "required domain name on selection clause: $selection"
                )
            } ?: Constants.UID_EVERYBODY

        val count = customDomainRepository.cpDelete(domain, uid)
        // update the app info cache
        CoroutineScope(Dispatchers.IO).launch { DomainRulesManager.load() }
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
        val customDomain = CustomDomain(values)

        if (selectionClause.isNullOrEmpty()) {
            val count = customDomainRepository.cpUpdate(customDomain)
            context.contentResolver?.notifyChange(uri, null)
            CoroutineScope(Dispatchers.IO).launch { DomainRulesManager.updateTrie(customDomain) }
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
            if (DEBUG)
                Logger.d(LOG_PROVIDER,
                    "selection ${customDomain.domain}, ${customDomain.uid}, ${customDomain.status} clause: $clause"
                )
            val count = customDomainRepository.cpUpdate(customDomain, clause)
            CoroutineScope(Dispatchers.IO).launch { DomainRulesManager.updateTrie(customDomain) }
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
