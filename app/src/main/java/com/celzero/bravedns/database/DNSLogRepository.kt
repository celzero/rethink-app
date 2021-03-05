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

package com.celzero.bravedns.database

import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope

class DNSLogRepository(private val dnsLogDAO: DNSLogDAO) {


    fun insertAsync(dnsLogs: DNSLogs, coroutineScope: CoroutineScope = GlobalScope) {
        //coroutineScope.launch {
        dnsLogDAO.insert(dnsLogs)
        //deleteConnectionTrackerCount()
        //}
    }

    fun deleteOlderData(date: Long){
        dnsLogDAO.deleteOlderData(date)
    }

    fun deleteConnectionTrackerCount(coroutineScope: CoroutineScope = GlobalScope) {
        //coroutineScope.launch {
            dnsLogDAO.deleteOlderDataCount(Constants.FIREWALL_CONNECTIONS_IN_DB)
        //}
    }

}
