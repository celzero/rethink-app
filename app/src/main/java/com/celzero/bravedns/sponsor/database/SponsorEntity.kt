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
package com.celzero.bravedns.sponsor.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Sponsor")
data class SponsorEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "purchase_token")
    val purchaseToken: String,

    @ColumnInfo(name = "product_id")
    val productId: String,

    @ColumnInfo(name = "purchase_time")
    val purchaseTime: Long,

    @ColumnInfo(name = "sponsor_since")
    val sponsorSince: Long,

    @ColumnInfo(name = "consumed")
    val consumed: Boolean = false,

    @ColumnInfo(name = "contribution_count")
    val contributionCount: Int = 1,

    @ColumnInfo(name = "last_contribution_time")
    val lastContributionTime: Long = purchaseTime
)
