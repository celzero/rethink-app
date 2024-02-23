/*
 * Copyright 2023 RethinkDNS and its authors
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.database.ODoHEndpointDAO
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

class ODoHEndpointViewModel(private val endpointDao: ODoHEndpointDAO) : ViewModel() {
    val dohEndpointList =
        Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) { endpointDao.getODoHEndpointLiveData() }
            .liveData
            .cachedIn(viewModelScope)
}
