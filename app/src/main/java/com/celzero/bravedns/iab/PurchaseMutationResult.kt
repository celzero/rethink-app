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
 * Result of a server-side cancel or revoke operation.
 * Replaces the previous Pair<Boolean, String> with typed outcomes.
 */
sealed class PurchaseMutationResult {

    /** Server accepted the cancel/revoke. */
    data class Success(val message: String) : PurchaseMutationResult()

    /** HTTP 401: handled by the auth-error flow. */
    data class Unauthorized(val message: String = "Unauthorized: 401") : PurchaseMutationResult()

    /** HTTP 409: handled by the conflict flow. */
    data class Conflict(val message: String = "Conflict: 409") : PurchaseMutationResult()

    /** Any other failure (network, 4xx/5xx, blank identity); [message] is for the UI. */
    data class Failure(val message: String) : PurchaseMutationResult()

    val isSuccess: Boolean get() = this is Success

    // human-readable message
    val text: String
        get() = when (this) {
            is Success -> message
            is Unauthorized -> message
            is Conflict -> message
            is Failure -> message
        }
}
