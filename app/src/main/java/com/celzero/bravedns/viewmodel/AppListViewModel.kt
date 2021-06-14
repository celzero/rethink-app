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

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.toLiveData
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.util.Constants.Companion.FILTER_CATEGORY
import com.celzero.bravedns.util.Constants.Companion.FILTER_IS_SYSTEM

class AppListViewModel(private val appInfoDAO: AppInfoDAO) : ViewModel() {

    private var filteredList : MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var appDetailsList = Transformations.switchMap(
        filteredList) { input:String ->
            if (input.isBlank()) {
                appInfoDAO.getUnivAppDetailsLiveData().toLiveData(pageSize = 50)
            } else if (input == FILTER_IS_SYSTEM) {
                appInfoDAO.getUnivAppSystemAppsLiveData().toLiveData(pageSize = 50)
            } else if (input.contains(FILTER_CATEGORY)) {
                val filterVal = input.split(":")[1]
                val result = filterVal.split(",").map { it.trim() }
                appInfoDAO.getUnivAppDetailsFilterForCategoryLiveData(result).toLiveData(pageSize = 50)
            } else {
                appInfoDAO.getUnivAppDetailsFilterLiveData("%$input%").toLiveData(pageSize = 50)
            }
        }

    fun setFilter(filter: String?) {
        filteredList.value = filter
    }

    fun setFilterBlocked(filter: String){
        filteredList.value = filter
    }
}
