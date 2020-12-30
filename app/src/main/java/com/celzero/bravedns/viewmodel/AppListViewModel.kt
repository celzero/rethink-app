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
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG

class AppListViewModel(private val appInfoDAO: AppInfoDAO) : ViewModel() {

    private var filteredList : MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var appDetailsList = Transformations.switchMap(
        filteredList) { input:String ->
            if (input.isBlank()) {
                appInfoDAO.getUnivAppDetailsLiveData().toLiveData(pageSize = 50)
            } else if (input == "isSystem") {
                appInfoDAO.getUnivAppSystemAppsLiveData().toLiveData(pageSize = 50)
            } else if (input.contains("category:")) {
                val filterVal = input.split(":")[1]
                val result = filterVal.split(",").map { it.trim() }
                Log.d(LOG_TAG, "FilterVal - $filterVal")
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
