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
package com.celzero.bravedns.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.database.SubscriptionStateHistory
import com.celzero.bravedns.database.SubscriptionStateHistoryDao
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

class PurchaseHistoryViewModel(private val historyDao: SubscriptionStateHistoryDao) : ViewModel() {

    private val trigger: MutableLiveData<Unit> = MutableLiveData(Unit)

    private val pagingConfig = PagingConfig(
        enablePlaceholders = true,
        prefetchDistance = 3,
        initialLoadSize = LIVEDATA_PAGE_SIZE * 2,
        maxSize = LIVEDATA_PAGE_SIZE * 3,
        // Double the page size vs the old config (matches DnsLogViewModel).
        pageSize = LIVEDATA_PAGE_SIZE * 2,
        // Trigger a fresh load from the new position when the user jumps >5 pages.
        jumpThreshold = 5,
    )

    val historyList: LiveData<PagingData<SubscriptionStateHistory>> =
        trigger.switchMap {
            Pager(pagingConfig) { historyDao.observeHistoryPaged() }
                .liveData
                .cachedIn(viewModelScope)
        }

    val totalCount: LiveData<Int> = liveData {
        emit(historyDao.getMeaningfulCount())
    }
}
