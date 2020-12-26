/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.viewmodel

import android.content.Context
import android.util.Log
import androidx.arch.core.util.Function
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG


class ConnectionTrackerViewModel(private val connectionTrackerDAO: ConnectionTrackerDAO) : ViewModel() {

    private var filteredList : MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var connectionTrackerList = Transformations.switchMap<String, PagedList<ConnectionTracker>>(
                filteredList, (Function<String, LiveData<PagedList<ConnectionTracker>>> { input ->
                    if (input.isBlank()) {
                        connectionTrackerDAO.getConnectionTrackerLiveData().toLiveData(pageSize = 20)
                    } else if(input.contains("isFilter")){
                        val searchText = input.split(":")[0]
                        if(DEBUG) Log.d(LOG_TAG, "Filter option - Function - $searchText, $input")
                        if(searchText.isEmpty()){
                            connectionTrackerDAO.getConnectionBlockedConnections().toLiveData(pageSize = 20)
                        }else {
                            connectionTrackerDAO.getConnectionBlockedConnectionsByName("%$searchText%").toLiveData(pageSize = 20)
                        }
                    }else {
                        connectionTrackerDAO.getConnectionTrackerByName("%$input%").toLiveData(20)
                    }
                } as Function<String, LiveData<PagedList<ConnectionTracker>>>)

            )

    fun setFilter(searchString: String, filter : String? ) {
        if(DEBUG) Log.d(LOG_TAG, "Filter option:$searchString, $filter ")
        filteredList.value = "$searchString$filter"
    }

    fun setFilterBlocked(filter: String){
        if(DEBUG) Log.d(LOG_TAG, "Filter option blocked:, $filter ")
        filteredList.value = filter
    }

}