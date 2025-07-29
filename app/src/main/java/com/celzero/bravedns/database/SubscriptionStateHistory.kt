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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Data class for state transition history
 */
@Entity(tableName = "SubscriptionStateHistory")
data class SubscriptionStateHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val subscriptionId: Int,
    val fromState: Int,
    val toState: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String? = null
) {
    val fromStateName: String get() = SubscriptionStatus.SubscriptionState.fromId(fromState).name
    val toStateName: String get() = SubscriptionStatus.SubscriptionState.fromId(toState).name
}


/**
 * Data class for transition statistics
 */
data class TransitionStatistic(
    val fromState: Int,
    val toState: Int,
    val count: Int
) {
    val fromStateName: String get() = SubscriptionStatus.SubscriptionState.fromId(fromState).name
    val toStateName: String get() = SubscriptionStatus.SubscriptionState.fromId(toState).name
}
