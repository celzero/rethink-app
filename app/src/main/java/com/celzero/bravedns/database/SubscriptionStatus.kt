/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.database

import Logger
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "SubscriptionStatus")
class SubscriptionStatus {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
    var accountId: String = ""

    var purchaseToken: String = ""
    var productId: String = ""
    var planId: String = ""
    var sessionToken: String = ""
    var productTitle: String = ""
    var state: Int = 0
    var purchaseTime: Long = 0L
    var accountExpiry: Long = 0L
    var billingExpiry: Long = 0L
    // "developerPayload":"{\"ws\":{\"cid\":\"aa95f04efcb19a54c7605a02e5dd0b435906b993d12bec031a60f3f1272f4f0e\",\"sessiontoken\":\"22695:4:1752256088:524537c17ba103463ba1d330efaf05c146ba3404af:023f958b6c1949568f55078e3c58fe6885d3e57322\",\"expiry\":\"2025-08-11T00:00:00.000Z\",\"status\":\"valid\"}}"
    var developerPayload: String = ""
    var status: Int = -1
    var lastUpdatedTs: Long = System.currentTimeMillis()

    fun isExpired(): Boolean {
        return System.currentTimeMillis() > billingExpiry
    }

    fun isActive(): Boolean {
        return status == SubscriptionState.STATE_ACTIVE.id
    }

    fun isCancelled(): Boolean {
        return status == SubscriptionState.STATE_CANCELLED.id
    }

    fun isRevoked(): Boolean {
        return status == SubscriptionState.STATE_REVOKED.id
    }

    fun isValidState(): Boolean {
        return status in listOf(
            SubscriptionState.STATE_ACTIVE.id,
            SubscriptionState.STATE_CANCELLED.id
        )
    }

    fun gracePeriodMillis(): Long {
        Logger.d("SubscriptionStatus", "HSFragment gracePeriodMillis: accountExpiry: $accountExpiry, billingExpiry: $billingExpiry")
        return accountExpiry - billingExpiry
    }

    enum class SubscriptionState(val id: Int) {
        STATE_INITIAL(0),
        STATE_ACTIVE(1),
        STATE_CANCELLED(2),
        STATE_EXPIRED(3),
        STATE_UNKNOWN(4),
        STATE_PURCHASED(5),
        STATE_ACK_PENDING(6),
        STATE_REVOKED(7),
        STATE_PURCHASE_FAILED(8);

        companion object {
            fun fromId(id: Int): SubscriptionState {
                return entries.find { it.id == id } ?: STATE_UNKNOWN
            }
        }
    }
}

/**
 * Data classes for query results
 */
data class StatusCount(
    val status: Int,
    val count: Int
) {
    val stateName: String get() = SubscriptionStatus.SubscriptionState.fromId(status).name
}

data class ProductCount(
    val productId: String,
    val count: Int
)

data class TokenCount(
    val purchaseToken: String,
    val count: Int
)
