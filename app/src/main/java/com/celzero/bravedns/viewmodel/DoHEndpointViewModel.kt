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
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.DoHEndpoint

class DoHEndpointViewModel : ViewModel() {

    companion object{
        lateinit var contextVal : Context
        fun setContext(context: Context){
            this.contextVal = context
        }
    }

    private val mDb = AppDatabase.invoke(contextVal.applicationContext)
    private val doHEndpointsDAO = mDb.dohEndpointsDAO()

    private var filteredList : MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var dohEndpointList = Transformations.switchMap<String, PagedList<DoHEndpoint>>(
                filteredList, (Function<String, LiveData<PagedList<DoHEndpoint>>> { input ->
            if (input.isBlank()) {
                doHEndpointsDAO.getDoHEndpointLiveData().toLiveData(pageSize = 50)
            } else if (input == "isSystem") {
                doHEndpointsDAO.getDoHEndpointLiveData().toLiveData(pageSize = 50)
            } else {
                doHEndpointsDAO.getDoHEndpointLiveDataByName("%$input%").toLiveData(pageSize = 50)
                //appDetailsDAO.getUnivAppDetailsLiveData("%$input%").toLiveData(50)
            }
        } as androidx.arch.core.util.Function<String, LiveData<PagedList<DoHEndpoint>>>)
    )

    fun setFilter(filter: String?) {
        filteredList.value = filter
    }

    fun setFilterBlocked(filter: String){
        filteredList.value = filter
    }

}