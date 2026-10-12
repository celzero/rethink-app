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

/**
 * Typed result for [BillingBackendClient.refreshIdentity] and
 * [BillingBackendClient.resolveIdentity].
 *
 * Carries the specific failure reason instead of collapsing all errors into a
 * blank [Pair], so callers can distinguish authorization failures (which need
 * an immediate UI error screen) from transient network problems (which can be
 * silently retried).
 *
 * Shared across all flavors (fdroid stub + play/website real implementations).
 */
sealed class RefreshIdentityResult {

    /**
     * Both CID and DID are available (either from the store or freshly from the server).
     */
    data class Success(val cid: String, val did: String) : RefreshIdentityResult()

    /**
     * HTTP 401 Unauthorized from `/d/acc` (CID step) or `/d/reg` (DID step).
     *
     * The server actively rejected the credentials — retrying will not help.
     * Callers should surface [ServerApiError.Unauthorized401] to the UI.
     */
    data class Unauthorized(val accountId: String = "") : RefreshIdentityResult()

    /**
     * HTTP 409 Conflict from `/d/acc` or `/d/reg`.
     *
     * The account/device state on the server conflicts with the requested operation.
     * Callers should surface [ServerApiError.Conflict409] to the UI.
     */
    object Conflict : RefreshIdentityResult()

    /**
     * Any other failure: network unreachable, timeout, 5xx, or unexpected exception.
     * May be retried later; no immediate UI error is required.
     */
    object Failure : RefreshIdentityResult()

    /** `true` only for [Success]. */
    val isSuccess: Boolean get() = this is Success
}

