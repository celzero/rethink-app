package com.celzero.bravedns.database

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope

class DNSLogRepository(private val dnsLogDAO: DNSLogDAO) {


    fun insertAsync(dnsLogs: DNSLogs, coroutineScope: CoroutineScope = GlobalScope) {
        //coroutineScope.launch {
        dnsLogDAO.insert(dnsLogs)
        //}
    }

}
