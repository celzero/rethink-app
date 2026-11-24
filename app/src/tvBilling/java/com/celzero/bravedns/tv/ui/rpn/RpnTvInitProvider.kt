/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rpn

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.celzero.bravedns.tv.ui.nav.TvPaidRoutes

/**
 * Process-start hook that connects the billing-capable TV RPN screens
 * to the channel-agnostic TV nav shell.
 *
 * This class (and its manifest declarations in
 * `src/playTv/AndroidManifest.xml` and `src/websiteTv/AndroidManifest.xml`)
 * exists only in billing-capable TV builds — manifest merging scopes it
 * to playTv and websiteTv variants. It runs before
 * [com.celzero.bravedns.RethinkDnsApplication] or any Compose code, so the
 * paid sub-graph is guaranteed to be registered by the time
 * [com.celzero.bravedns.tv.ui.nav.TvNavScaffold] builds its NavHost.
 * fdroidTv and full phone builds never include this provider, keeping the
 * no-billing guarantee for those channels.
 */
class RpnTvInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        TvPaidRoutes.install { navController -> rpnNavGraph(navController) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
