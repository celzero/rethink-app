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
package com.celzero.bravedns.database

/**
 * Aggregated count of log rows belonging to one time bucket of the activity
 * wall. Used by LogActivityAggregator to rebuild the wall from the log
 * databases without loading individual rows. [bucketIndex] is
 * (timestamp - dayStart) / bucketMs; [blocked] mirrors the row's isBlocked
 * column (1/0); [total] is the number of rows in that bucket/class pair.
 */
data class ActivityBucketRow(
    val bucketIndex: Long,
    val blocked: Int,
    val total: Long
)

/**
 * Blocked/total counts of log rows within an arbitrary time window.
 */
data class WindowCountRow(
    val blocked: Long,
    val total: Long
)

/**
 * Per-app activity counts within a time window, grouped by (uid, appName)
 * across the dns/connection log tables. [blocked] is the number of rows with
 * isBlocked = 1; allowed = total - blocked.
 */
data class AppActivityRow(
    val uid: Int,
    val appName: String,
    val total: Long,
    val blocked: Long
)
