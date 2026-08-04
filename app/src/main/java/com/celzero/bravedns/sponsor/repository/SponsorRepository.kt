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
package com.celzero.bravedns.sponsor.repository

import com.celzero.bravedns.sponsor.database.SponsorDao
import com.celzero.bravedns.sponsor.database.SponsorEntity
import com.celzero.bravedns.sponsor.model.SponsorInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SponsorRepository(private val sponsorDao: SponsorDao) {

    // The local DB row is the single source of truth for sponsorship state across
    // the app (About, Home, Sponsor screens). Billing results are funneled through
    // recordSponsorship(), which upserts here; all UI reads originate from this row.
    val isSponsored: Flow<Boolean> = sponsorDao.observeLatestSponsor().map { it != null }

    val sponsorInfo: Flow<SponsorInfo?> = sponsorDao.observeLatestSponsor().map { entity ->
        entity?.let {
            SponsorInfo(
                id = it.id,
                purchaseToken = it.purchaseToken,
                productId = it.productId,
                purchaseTime = it.purchaseTime,
                sponsorSince = it.sponsorSince,
                consumed = it.consumed,
                contributionCount = it.contributionCount,
                lastContributionTime = it.lastContributionTime
            )
        }
    }

    /**
     * Records a sponsorship using the values reported by the billing client.
     *
     * If a sponsor row already exists, it is treated as the primary record: the
     * contribution count is incremented and the entry is marked consumed, rather
     * than creating a duplicate. This keeps the existing DB entry authoritative
     * across repeat contributions.
     */
    suspend fun recordSponsorship(
        purchaseToken: String,
        productId: String,
        purchaseTime: Long
    ): Long {
        val existing = sponsorDao.getLatestSponsor()
        return if (existing != null) {
            sponsorDao.incrementContribution(existing.id, purchaseTime)
            sponsorDao.updateConsumed(existing.id, true)
            existing.id
        } else {
            val entity = SponsorEntity(
                purchaseToken = purchaseToken,
                productId = productId,
                purchaseTime = purchaseTime,
                sponsorSince = purchaseTime,
                consumed = true,
                contributionCount = 1,
                lastContributionTime = purchaseTime
            )
            sponsorDao.insert(entity)
        }
    }

    suspend fun getSponsorSince(): Long? {
        return sponsorDao.getLatestSponsor()?.sponsorSince
    }

    suspend fun isCurrentlySponsored(): Boolean {
        return sponsorDao.getLatestSponsor() != null
    }
}
