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

import java.io.File
import java.util.concurrent.TimeUnit


class Constants {

    companion object {
        // on-device blocklist download path
        val ONDEVICE_BLOCKLIST_DOWNLOAD_PATH = File.separator + "downloads" + File.separator

        const val DOWNLOAD_BASE_URL = "https://download.rethinkdns.com"

        const val FILE_TAG = "filetag.json"

        // file names which are downloaded as part of on-device blocklists
        const val ONDEVICE_BLOCKLIST_FILE_TAG = "filetag.json"
        const val ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG = "basicconfig.json"
        const val ONDEVICE_BLOCKLIST_FILE_RD = "rd.txt"
        const val ONDEVICE_BLOCKLIST_FILE_TD = "td.txt"

        // url parameter used in configure blocklist webview
        private const val RETHINK_BLOCKLIST_CONFIGURE_URL_PARAMETER = "tstamp="

        // url to check to check the if there is update available for on-device blocklist
        const val ONDEVICE_BLOCKLIST_UPDATE_CHECK_URL =
            "$DOWNLOAD_BASE_URL/update/blocklists?$RETHINK_BLOCKLIST_CONFIGURE_URL_PARAMETER"

        // url parameter, part of update check for on-device blocklist
        const val ONDEVICE_BLOCKLIST_UPDATE_CHECK_PARAMETER_VCODE = "vcode="

        // url to check if there is app-update is available (this is for website version only)
        const val RETHINK_APP_UPDATE_CHECK =
            "$DOWNLOAD_BASE_URL/update/app?$ONDEVICE_BLOCKLIST_UPDATE_CHECK_PARAMETER_VCODE"

        // The version tag value(response) for the update check (both on-device and app update)
        // TODO: have two different response versions for blocklist update and app update
        const val UPDATE_CHECK_RESPONSE_VERSION = 1

        // meta data for on-device blocklist
        data class OnDeviceBlocklistsMetadata(val url: String, val filename: String)

        // folder name to store the local blocklist download files (eg../files/local_blocklist/<timestamp>)
        const val LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME = "local_blocklist"

        const val REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME = "remote_blocklist"

        // url_param_compress_blob
        // search param
        private const val URL_SEARCHPARAM_COMPRESS_BLOB = "?compressed"

        val ONDEVICE_BLOCKLISTS = listOf(
            OnDeviceBlocklistsMetadata(
                "$DOWNLOAD_BASE_URL/blocklists",
                ONDEVICE_BLOCKLIST_FILE_TAG
            ),
            OnDeviceBlocklistsMetadata(
                "$DOWNLOAD_BASE_URL/basicconfig",
                ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG
            ),
            OnDeviceBlocklistsMetadata(
                "$DOWNLOAD_BASE_URL/rank$URL_SEARCHPARAM_COMPRESS_BLOB",
                ONDEVICE_BLOCKLIST_FILE_RD
            ),
            OnDeviceBlocklistsMetadata(
                "$DOWNLOAD_BASE_URL/trie$URL_SEARCHPARAM_COMPRESS_BLOB",
                ONDEVICE_BLOCKLIST_FILE_TD
            )
        )

        val ONDEVICE_BLOCKLISTS_TEMP = listOf(
            OnDeviceBlocklistsMetadata("blocklists", ONDEVICE_BLOCKLIST_FILE_TAG),
            OnDeviceBlocklistsMetadata("basicconfig", ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG),
            OnDeviceBlocklistsMetadata("rank", ONDEVICE_BLOCKLIST_FILE_RD),
            OnDeviceBlocklistsMetadata("trie", ONDEVICE_BLOCKLIST_FILE_TD)
        )

        const val FILETAG_TEMP_DOWNLOAD_URL = "blocklists"

        // url to download the rethinkdns apk file
        const val RETHINK_APP_DOWNLOAD_LINK = "https://rethinkdns.com/download"

        // base-url for rethinkdns
        const val RETHINK_BASE_URL_SKY = "https://sky.rethinkdns.com/"
        const val RETHINK_BASE_URL_MAX = "https://max.rethinkdns.com/"

        const val RETHINK_SEARCH_URL = "https://rethinkdns.com/search?s="

        // default filetag.json for remote blocklist (stored in assets folder) (v053i)
        const val PACKAGED_REMOTE_FILETAG_TIMESTAMP: Long = 1657632597183

        // rethinkdns sponsor link
        const val RETHINKDNS_SPONSOR_LINK = "https://svc.rethinkdns.com/r/sponsor"

        // base-url for bravedns
        const val BRAVEDNS_DOMAIN = "bravedns.com"

        // base-url for rethinkdns
        const val RETHINKDNS_DOMAIN = "rethinkdns.com"

        // default doh url
        const val DEFAULT_DOH_URL = "https://basic.rethinkdns.com/dns-query"

        // json object constants received as part of update check
        // FIXME: Avoid usage of these parameters, map to POJO instead
        const val JSON_VERSION = "version"
        const val JSON_UPDATE = "update"
        const val JSON_LATEST = "latest"

        // app type unknown
        const val UNKNOWN_APP = "Unknown"

        // Number of network log entries to store in the database.
        const val TOTAL_LOG_ENTRIES_THRESHOLD = 10000

        // invalid application uid
        const val INVALID_UID = -1

        // missing uid, used when the uid is undermined. see ConnectionTracer#getUidQ()
        // changing this requires changes in RethinkDnsEndpointDao file
        const val MISSING_UID = -2000

        // label for rethinkdns plus doh endpoint
        const val RETHINK_DNS_PLUS = "RethinkDNS Plus"

        // constants used as part of intent to load the viewpager's screen
        const val VIEW_PAGER_SCREEN_TO_LOAD = "view_pager_screen"

        // default custom http proxy port number
        const val HTTP_PROXY_PORT = "8118"

        // default custom socks5 ip
        const val SOCKS_DEFAULT_IP = "127.0.0.1"

        // default custom socks5 port
        const val SOCKS_DEFAULT_PORT = "9050"

        // constants to send type of proxy: for socks5
        const val SOCKS = "Socks5"

        // data-time format used as part of network log adapter
        const val DATE_FORMAT_PATTERN = "HH:mm:ss"

        // constants generated as part of BuildConfig.FLAVORS
        const val FLAVOR_PLAY = "play"
        const val FLAVOR_FDROID = "fdroid"
        const val FLAVOR_WEBSITE = "website"
        const val FLAVOR_HEADLESS = "headless"

        // Various notification action constants used part of NotificationCompat.Action
        const val NOTIFICATION_ACTION = "NOTIFICATION_VALUE"
        const val NOTIF_ACTION_STOP_VPN = "RETHINK_STOP" // stop vpn service
        const val NOTIF_ACTION_PAUSE_VPN = "RETHINK_PAUSE" // pause vpn
        const val NOTIF_ACTION_RESUME_VPN = "RETHINK_RESUME" // resume vpn
        const val NOTIF_ACTION_DNS_VPN = "RETHINK_DNSONLY" // battery-saver dns-only
        const val NOTIF_ACTION_DNS_FIREWALL_VPN = "RETHINK_FULLMODE" // default dns+firewall
        const val NOTIF_ACTION_RULES_FAILURE = "RETHINK_RULES_RELOAD" // load rules failure
        const val NOTIF_ACTION_NEW_APP_ALLOW = "NEW_APP_ALLOW" // allow network access for new apps
        const val NOTIF_ACTION_NEW_APP_DENY = "NEW_APP_DENY" // deny network access for new apps

        // various notification intent extra name/values used part of notification's pending-intent
        const val NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME =
            "ACCESSIBILITY" // accessibility failure name
        const val NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE =
            "ACCESSIBILITY_FAILURE" // accessibility failure value
        const val NOTIF_INTENT_EXTRA_NEW_APP_NAME = "NEW_APP" // new app install name
        const val NOTIF_INTENT_EXTRA_NEW_APP_VALUE =
            "NEW_APP_INSTALL_NOTIFY" // new app install value

        // new app install intent extra name for uid. see RefreshDatabase#makeNewAppVpnIntent()
        const val NOTIF_INTENT_EXTRA_APP_UID = "NEW_APP_UID"

        // DNS message type received by the DNS resolver
        const val NXDOMAIN = "NXDOMAIN"

        // The minimum interval before checking if the internal accessibility service
        // (used to block apps-not-in-use) is indeed running.
        // Ref: {@link com.celzero.bravedns.util.Utilities#isAccessibilityServiceEnabled} and
        // {@link com.celzero.bravedns.util.Utilities#isAccessibilityServiceEnabledViaSettingsSecure}
        val ACCESSIBILITY_SERVICE_HEARTBEAT_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(5)

        // minimum interval before checking if there is a change in active network
        // (metered/unmetered)
        val ACTIVE_NETWORK_CHECK_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(60)

        // View model - filter string
        const val FILTER_IS_SYSTEM = "isSystem"

        // IPv4 uses 0.0.0.0 as an unspecified address
        const val UNSPECIFIED_IP_IPV4 = "0.0.0.0"

        // IPv6 uses :: as an unspecified address
        const val UNSPECIFIED_IP_IPV6 = "::"

        // special port number not assigned to any app
        const val UNSPECIFIED_PORT = 0

        // IPv6 loopback address
        const val LOOPBACK_IPV6 = "::1"

        // Invalid port number
        const val INVALID_PORT = -1

        // intent for settings->vpn screen
        const val ACTION_VPN_SETTINGS_INTENT = "android.net.vpn.SETTINGS"

        // default live data page size used by recycler views
        const val LIVEDATA_PAGE_SIZE = 50

        // dns logs live data page size
        const val DNS_LIVEDATA_PAGE_SIZE = 30

        // To initiate / reset the timestamp in milliseconds
        const val INIT_TIME_MS = 0L

        // various time formats used in app
        const val TIME_FORMAT_1 = "HH:mm:ss"
        const val TIME_FORMAT_2 = "yy.MM (dd)"
        const val TIME_FORMAT_3 = "dd MMMM yyyy, HH:mm:ss"

        // play services package name
        const val PKG_NAME_PLAY_STORE = "com.android.vending"

        // max endpoint
        const val MAX_ENDPOINT = "max"

        // The UID used to be generic uid used to block IP addresses which are intended to
        // block for all the applications.
        const val UID_EVERYBODY = -1000
    }
}
