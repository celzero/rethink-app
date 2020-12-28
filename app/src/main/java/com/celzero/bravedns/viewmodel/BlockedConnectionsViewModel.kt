/*
 * Copyright 2020 RethinkDNS and its authors
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

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.toLiveData
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.BlockedConnections
import com.celzero.bravedns.database.BlockedConnectionsDAO

class BlockedConnectionsViewModel(private val blockedConnectionsDAO: BlockedConnectionsDAO) : ViewModel() {

    private var filteredList : MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var blockedUnivRulesList = Transformations.switchMap(
                filteredList

    ) { input ->
        if (input.isBlank()) {
            blockedConnectionsDAO.getUnivBlockedConnectionsLiveData().toLiveData(pageSize = 50)
        } else if (input!! == "isFilter") {
            blockedConnectionsDAO.getUnivBlockedConnectionsLiveData().toLiveData(pageSize = 50)
        } else {
            blockedConnectionsDAO.getUnivBlockedConnectionsByIP("%$input%").toLiveData(50)
        }
    }

    fun setFilter(filter: String?) {
        filteredList.value = filter
    }

    fun setFilterBlocked(filter: String){
        filteredList.value = filter
    }
}
