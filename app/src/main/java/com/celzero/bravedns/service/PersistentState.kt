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
        private const val IS_FIRST_LAUNCH = "is_first_time_launch"
        private const val APPS_KEY_DATA = "pref_apps_data"
        private const val APPS_KEY_WIFI = "pref_apps_wifi"
        private const val CATEGORY_DATA = "blocked_categories"

        //const val URL_KEY = "pref_server_url"
        private const val DNS_MODE = "dns_mode"
        private const val FIREWALL_MODE = "firewall_mode"
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        private const val SCREEN_STATE = "screen_state"
        const val DNS_TYPE = "dns_type"
        const val PROXY_MODE = "proxy_mode"
        private const val DNS_ALL_TRAFFIC = "dns_all_traffic"
        const val ALLOW_BYPASS = "allow_bypass"
        const val PRIVATE_DNS = "private_dns"
        private const val ENABLE_LOCAL_LOGS = "local_logs"
        const val EXCLUDE_FROM_VPN = "exclude_apps_vpn"
        private const val APP_VERSION = "app_version"

        const val IS_SCREEN_OFF = "screen_off"
        private const val CUSTOM_URL_BOOLEAN = "custom_url_boolean"
        private const val CUSTOM_URL = "custom_url"

        private const val pref_auto_start_bootup = "auto_start_on_boot"
        private const val KILL_APP_FIREWALL = "kill_app_on_firewall"
        private const val SOCKS5 = "socks5_proxy"

        const val CONNECTION_CHANGE = "change_in_url"
        private const val DOWNLOAD_BLOCK_LIST_FILES = "download_block_list_files"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        private const val LOCAL_BLOCK_LIST_TIME = "local_block_list_downloaded_time"
        private const val REMOTE_BLOCK_LIST_TIME_MS = "remote_block_list_downloaded_time"
        private const val REMOTE_BLOCK_COMPLETE = "download_remote_block_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        private const val LOCAL_BLOCK_LIST_COUNT = "local_block_list_count"
        private const val REMOTE_BLOCK_LIST_COUNT = "remote_block_list_count"
        const val BLOCK_UNKNOWN_CONNECTIONS = "block_unknown_connections"
        private const val IS_INSERT_COMPLETE = "initial_insert_servers_complete"
        private const val APP_UPDATE_LAST_CHECK = "app_update_last_check"
        private const val APP_DOWNLOAD_SOURCE = "app_downloaded_source"

        private const val APPROVED_KEY = "approved"
        private const val ENABLED_KEY = "enabled"
        private const val SERVER_KEY = "server"

        private const val MEDIAN_90 = "median_p90"
        private const val NUMBER_REQUEST = "number_request"
        private const val BLOCKED_COUNT = "blocked_request"

        const val HTTP_PROXY_ENABLED = "http_proxy_enabled"
        private const val HTTP_PROXY_IPADDRESS = "http_proxy_ipaddress"
        private const val HTTP_PROXY_PORT = "http_proxy_port"

        const val DNS_PROXY_ID = "dns_proxy_change"

        const val BLOCK_UDP_OTHER_THAN_DNS = "block_udp_traffic_other_than_dns"

        private const val INTERNAL_STATE_NAME = "MainActivity"

        // The approval state is currently stored in a separate preferences file.
        // TODO: Unify preferences into a single file.
        private const val APPROVAL_PREFS_NAME = "IntroState"

        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getStringArray(R.array.doh_endpoint_names)[3]
            } else url
        }
    }
}
