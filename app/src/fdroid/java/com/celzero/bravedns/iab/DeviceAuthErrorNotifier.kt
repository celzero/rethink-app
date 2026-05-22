/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.iab

import android.content.Context

/**
 * Stub: in-app billing / device auth error notifications are not available on the F-Droid build.
 * All methods are no-ops so that shared source-set code compiles without modification.
 */
object DeviceAuthErrorNotifier {

    const val EXTRA_OPERATION = "auth_error_operation"
    const val EXTRA_ACCOUNT_ID = "auth_error_account_id"
    const val EXTRA_DEVICE_ID_PREFIX = "auth_error_device_id_prefix"

    @Suppress("UNUSED_PARAMETER")
    fun notify(context: Context, error: ServerApiError.Unauthorized401, theme: Int) { /* no-op */ }

    @Suppress("UNUSED_PARAMETER")
    fun cancel(context: Context) { /* no-op */ }
}

