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
package com.celzero.bravedns.util

import java.util.concurrent.TimeUnit


class Constants {

    companion object {
        //Log
        const val LOG_TAG = "com.celzero.bravedns"

        //Download path and file names
        const val DOWNLOAD_PATH = "/downloads/"
        const val FILE_TAG_NAME = "/filetag.json"
        const val FILE_BASIC_CONFIG = "/basicconfig.json"
        const val FILE_RD_FILE = "/rd.txt"
        const val FILE_TD_FILE = "/td.txt"
        const val LOCAL_BLOCKLIST_FILE_COUNT = 4

        //Download URL's
        const val JSON_DOWNLOAD_BLOCKLIST_LINK = "https://download.bravedns.com/blocklists"

        //const val JSON_DOWNLOAD_BASIC_CONFIG_LINK = "https://download.bravedns.com/basicconfig"
        //const val JSON_DOWNLOAD_BASIC_RANK_LINK = "https://download.bravedns.com/rank"
        //const val JSON_DOWNLOAD_BASIC_TRIE_LINK = "https://download.bravedns.com/trie"
        const val REFRESH_BLOCKLIST_URL = "https://download.bravedns.com/update/blocklists?tstamp="
        const val APP_DOWNLOAD_AVAILABLE_CHECK = "https://download.bravedns.com/update/app?vcode="
        const val CONFIGURE_BLOCKLIST_URL_LOCAL = "https://bravedns.com/configure?v=app&tstamp="
        const val CONFIGURE_BLOCKLIST_URL_REMOTE = "https://bravedns.com/configure?v=app"

        val DOWNLOAD_URLS = listOf("https://download.bravedns.com/blocklists", "https://download.bravedns.com/basicconfig", "https://download.bravedns.com/rank", "https://download.bravedns.com/trie")

        val FILE_NAMES = listOf("/filetag.json", "/basicconfig.json", "/rd.txt", "/td.txt")

        // Earlier the link was https://bravedns.com/downloads
        // modified below link post v053c release
        const val APP_DOWNLOAD_LINK = "https://rethinkdns.com/download"

        const val BRAVE_BASE_STAMP = "https://basic.bravedns.com/"
        const val BRAVE_CONFIGURE_BASE_STAMP = "bravedns.com/configure"

        const val DOWNLOAD_STATUS_SUCCESSFUL = "STATUS_SUCCESSFUL"

        const val BRAVE_BASIC_URL = "bravedns.com"
        const val APPEND_VCODE = "vcode="

        //constants for the server response json
        const val JSON_VERSION = "version"
        const val JSON_UPDATE = "update"
        const val JSON_LATEST = "latest"

        //Firewall system components
        const val APP_CAT_SYSTEM_COMPONENTS = "System Components"
        const val APP_CAT_SYSTEM_APPS = "System Apps"
        const val APP_CAT_OTHER = "Other"
        const val APP_NON_APP = "Non-App System"
        const val INSTALLED_CAT_APPS = "Installed Apps"
        const val UNKNOWN_APP = "Unknown"

        //No package applications
        const val NO_PACKAGE = "no_package"

        //Network monitor
        const val FIREWALL_CONNECTIONS_IN_DB = 5000

        // UID used in Vpn service (block())
        const val INVALID_UID = -1
        const val MISSING_UID = -2000

        const val RETHINK_DNS_PLUS = "RethinkDNS Plus"
        const val RETHINK_DNS = "RethinkDNS Basic (default)"

        const val DELAY_FOR_BLOCK_RESPONSE: Long = 30000

        const val DNS_TYPE_DOH = 1
        const val DNS_TYPE_DNS_CRYPT = 2
        //const val DNS_TYPE_DNS_PROXY = 3

        const val SCREEN_TO_LOAD = "view_pager_screen"

        const val LOCATION_INTENT_EXTRA = "location"
        const val STAMP_INTENT_EXTRA = "stamp"
        const val URL_INTENT_EXTRA = "url"

        const val VPN_INTENT = "android.net.vpn.SETTINGS"

        const val HTTP_PROXY_PORT = "8118"

        const val SOCKS_DEFAULT_IP = "127.0.0.1"
        const val SOCKS_DEFAULT_PORT = "9050"
        const val SOCKS = "Socks5"

        const val DATE_FORMAT_PATTERN = "HH:mm:ss"

        const val ORBOT_SOCKS = 10L

        // Orbot mode constants
        const val ORBOT_MODE_NONE = 1
        const val ORBOT_MODE_SOCKS5 = 2
        const val ORBOT_MODE_HTTP = 3
        const val ORBOT_MODE_BOTH = 4

        //Application download source
        const val DOWNLOAD_SOURCE_PLAY_STORE = 1
        const val DOWNLOAD_SOURCE_FDROID = 2
        const val DOWNLOAD_SOURCE_WEBSITE = 3

        // build flavours constants
        const val FLAVOR_PLAY = "play"
        const val FLAVOR_FDROID = "fdroid"
        const val FLAVOR_WEBSITE = "website"

        const val PORT_VAL_UNKNOWN = "unknown"

        // Notification action constants.
        const val NOTIFICATION_ACTION = "NOTIFICATION_VALUE"
        const val STOP_VPN_NOTIFICATION_ACTION = "RETHINK_STOP"
        const val DNS_VPN_NOTIFICATION_ACTION = "RETHINK_DNSONLY" // battery-saver dns-only
        const val DNS_FIREWALL_VPN_NOTIFICATION_ACTION = "RETHINK_FULLMODE" // default dns+firewall
        const val RULES_FAILURE_NOTIFICATION_ACTION = "RETHINK_RULES_RELOAD" // load rules failure

        const val NXDOMAIN = "NXDOMAIN"

        // Time out to download of on-device blocklist.
        val WORK_MANAGER_TIMEOUT = TimeUnit.MINUTES.toMillis(40)

        const val FAV_ICON_URL = "https://icons.duckduckgo.com/ip2/"

        //Application theme constants
        const val THEME_SYSTEM_DEFAULT = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_TRUE_BLACK = 3

        // Notification action buttons
        const val NOTIFICATION_ACTION_STOP = 0
        const val NOTIFICATION_ACTION_DNS_FIREWALL = 1
        const val NOTIFICATION_ACTION_NONE = 2

        // DNS MODES
        const val APP_MODE_DNS = 0
        const val APP_MODE_FIREWALL = 1
        const val APP_MODE_DNS_FIREWALL = 2

        // DNS TYPES
        const val PREF_DNS_MODE_PROXY = 3
        const val PREF_DNS_MODE_DNSCRYPT = 2
        const val PREF_DNS_MODE_DOH = 1

        // Time limit to check if accessibility service is running - every 5 minutes.
        val ACCESSIBILITY_SERVICE_HEARTBEAT_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(5)

        // Threshold time to put network request in shared preference.
        val NETWORK_REQUEST_WRITE_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(1)
    }
}
