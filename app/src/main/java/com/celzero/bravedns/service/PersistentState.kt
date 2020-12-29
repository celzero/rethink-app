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

package com.celzero.bravedns.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import android.util.Log
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.blockedCount
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.isScreenLocked
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.isScreenLockedSetting
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQ
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQueries
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.median50
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.medianP90
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.numUniversalBlock
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import settings.Settings


private class PersistentState internal constructor(context: Context) {
    companion object {
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        const val DNS_TYPE = "dns_type"
        const val PROXY_MODE = "proxy_mode"
        const val ALLOW_BYPASS = "allow_bypass"
        const val PRIVATE_DNS = "private_dns"
        const val EXCLUDE_FROM_VPN = "exclude_apps_vpn"
        const val IS_SCREEN_OFF = "screen_off"
        const val CONNECTION_CHANGE = "change_in_url"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        const val BLOCK_UNKNOWN_CONNECTIONS = "block_unknown_connections"
        const val HTTP_PROXY_ENABLED = "http_proxy_enabled"
        const val DNS_PROXY_ID = "dns_proxy_change"
        const val BLOCK_UDP_OTHER_THAN_DNS = "block_udp_traffic_other_than_dns"
        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getStringArray(R.array.doh_endpoint_names)[3]
            } else url
        }
    }
}
