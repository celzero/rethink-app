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

import androidx.arch.core.util.Function
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.util.Constants.Companion.DNS_LIVEDATA_PAGE_SIZE
import com.celzero.bravedns.util.Constants.Companion.FILTER_IS_FILTER


class ConnectionTrackerViewModel(private val connectionTrackerDAO: ConnectionTrackerDAO) :
        ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var connectionTrackerList = Transformations.switchMap(filteredList,
                                                          (Function<String, LiveData<PagedList<ConnectionTracker>>> { input ->
                                                              if (input.isBlank()) {
                                                                  connectionTrackerDAO.getConnectionTrackerLiveData().toLiveData(
                                                                      pageSize = DNS_LIVEDATA_PAGE_SIZE)
                                                              } else if (input.contains(
                                                                      FILTER_IS_FILTER)) {
                                                                  val searchText = input.split(
                                                                      ":")[0]
                                                                  if (searchText.isEmpty()) {
                                                                      connectionTrackerDAO.getConnectionBlockedConnections().toLiveData(
                                                                          pageSize = DNS_LIVEDATA_PAGE_SIZE)
                                                                  } else {
                                                                      connectionTrackerDAO.getConnectionBlockedConnectionsByName(
                                                                          "%$searchText%").toLiveData(
                                                                          pageSize = DNS_LIVEDATA_PAGE_SIZE)
                                                                  }
                                                              } else {
                                                                  connectionTrackerDAO.getConnectionTrackerByName(
                                                                      "%$input%").toLiveData(
                                                                      DNS_LIVEDATA_PAGE_SIZE)
                                                              }
                                                          })

                                                         )

    fun setFilter(searchString: String?, filter: String?) {
        if (!searchString.isNullOrEmpty()) filteredList.value = "$searchString$filter"
        else filteredList.value = ""
    }

    fun setFilterBlocked(filter: String?) {
        if (!filter.isNullOrEmpty()) filteredList.value = filter
        else filteredList.value = ""
    }

}
