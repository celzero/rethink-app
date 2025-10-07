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
package com.rethinkdns.retrixed.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.rethinkdns.retrixed.data.AppConnection
import com.rethinkdns.retrixed.database.ConnectionTrackerDAO
import com.rethinkdns.retrixed.database.DnsLogDAO

class AlertsViewModel(
    private val connectionTrackerDao: ConnectionTrackerDAO,
    private val dnsLogDao: DnsLogDAO
) : ViewModel() {
    private var ipLogList: MutableLiveData<String> = MutableLiveData()
    private var domainLogList: MutableLiveData<String> = MutableLiveData()
    private var appLogList: MutableLiveData<String> = MutableLiveData()
    private var fromTime: MutableLiveData<Long> = MutableLiveData()
    private var toTime: MutableLiveData<Long> = MutableLiveData()

    init {
        ipLogList.postValue("")
        domainLogList.postValue("")
        appLogList.postValue("")
        fromTime.value = System.currentTimeMillis() - 1 * 60 * 60 * 1000L
        toTime.value = System.currentTimeMillis()
    }
}
