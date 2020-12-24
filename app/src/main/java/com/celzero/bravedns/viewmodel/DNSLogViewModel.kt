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
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG


class DNSLogViewModel : ViewModel() {

    companion object{
        lateinit var contextVal : Context
        fun setContext(context: Context){
            this.contextVal = context
        }
    }

    private val mDb = AppDatabase.invoke(contextVal.applicationContext)
    private val dnsLogDAO = mDb.dnsLogDAO()

    private var filteredList : MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var dnsLogsList = Transformations.switchMap<String, PagedList<DNSLogs>>(
                filteredList, Function<String, LiveData<PagedList<DNSLogs>>> { input ->
                    if (input.isBlank()) {
                        dnsLogDAO.getDNSLogsLiveData().toLiveData(pageSize = 25)
                    } else if(input.contains("isFilter")){
                        val searchString = input.split(":")[0]
                        if(DEBUG) Log.d(LOG_TAG,"DNS logs filter : $input, $searchString")
                        if(searchString.isEmpty()) {
                            dnsLogDAO.getBlockedDNSLogsLiveData().toLiveData(pageSize = 25)
                        }else{
                            dnsLogDAO.getBlockedDNSLogsLiveDataByName("%$searchString%").toLiveData(pageSize = 25)
                        }
                    }else {
                        if(DEBUG) Log.d(LOG_TAG,"DNS logs filter : $input")
                        dnsLogDAO.getDNSLogsByQueryLiveData("%$input%").toLiveData(25)
                    }
                }

            )

    fun setFilter(searchString: String?, filter : String ) {
        filteredList.value = "$searchString$filter"
    }

    fun setFilterBlocked(filter: String?){
        filteredList.value = filter
    }

}