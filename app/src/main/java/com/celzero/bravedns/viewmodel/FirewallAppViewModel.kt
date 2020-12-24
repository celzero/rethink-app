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
import androidx.arch.core.util.Function
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo

class FirewallAppViewModel : ViewModel() {

    companion object{
        lateinit var contextVal : Context
        fun setContext(context: Context){
            this.contextVal = context
        }
    }

    private val mDb = AppDatabase.invoke(contextVal.applicationContext)
    private val appDetailsDAO = mDb.appInfoDAO()

    private var filteredList : MutableLiveData<String> = MutableLiveData()

    //private var appNameSearch : String = ""

    init {
        filteredList.value = ""
    }

    var firewallAppDetailsList = Transformations.switchMap(
        filteredList, Function<String, LiveData<List<AppInfo>>> { input ->
            var inputTxt = "%$input%"
            appDetailsDAO.getAppDetailsForLiveData(inputTxt)
        }
    )

    /*else if (input == "isSystem") {
                    appDetailsDAO.getUnivAppSystemAppsLiveData().toLiveData(pageSize = 50)
                } else if (input.contains("category:")) {
                    val filterVal = input.split(":")[1]
                    val result = filterVal.split(",").map { it.trim() }
                    if(DEBUG) Log.d(LOG_TAG, "FilterVal - $filterVal")
                    appDetailsDAO.getUnivAppDetailsFilterForCategoryLiveData(result).toLiveData(pageSize = 50)
                } else {
                    appDetailsDAO.getUnivAppDetailsFilterLiveData("%$input%").toLiveData(pageSize = 50)
                }*/

    fun setFilter(filter: String?) {
        filteredList.value = filter
    }

    fun setFilterBlocked(filter: String){
        filteredList.value = filter
    }

   /* fun setFilterWithCategories(filter : String, categories : String){
        appNameSearch = filter
        filteredList.value = categories
    }
*/
}