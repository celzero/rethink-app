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

/** Stub: device-not-registered notifications are not supported in this build. */
object DeviceNotRegisteredNotifier {
    const val EXTRA_ENTITLEMENT_CID  = "dnr_entitlement_cid"
    const val EXTRA_STORED_CID       = "dnr_stored_cid"
    const val EXTRA_DEVICE_ID_PREFIX = "dnr_device_id_prefix"

    @Suppress("UNUSED_PARAMETER")
@Suppress("UNUSED_PARAMETER")    fun cancel(context: Context) { /* no-op */ }
}

