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

import com.celzero.bravedns.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit


class Constants {

    companion object {
        // on-device blocklist download path
        val ONDEVICE_BLOCKLIST_DOWNLOAD_PATH = File.separator + "downloads" + File.separator

        // file names which are downloaded as part of on-device blocklists
        val ONDEVICE_BLOCKLIST_FILE_TAG_NAME = File.separator + "filetag.json"
        val ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG = File.separator + "basicconfig.json"
        val ONDEVICE_BLOCKLIST_FILE_RD_FILE = File.separator + "rd.txt"
        val ONDEVICE_BLOCKLIST_FILE_TD_FILE = File.separator + "td.txt"

        // url to check to check the if there is update available for on-device blocklist
        const val ONDEVICE_BLOCKLIST_UPDATE_CHECK_URL = "https://download.rethinkdns.com/update/blocklists?tstamp="

        // url parameter, part of update check for on-device blocklist
        const val ONDEVICE_BLOCKLIST_UPDATE_CHECK_PARAMETER_VCODE = "vcode="

        // url to check if there is app-update is available (this is for website version only)
        const val RETHINK_APP_UPDATE_CHECK = "https://download.rethinkdns.com/update/app?vcode="

        // url to launch the blocklist (remote/on-device) configure screen
        const val RETHINK_BLOCKLIST_CONFIGURE_URL = "https://rethinkdns.com/configure?v=app"

        // url parameter used in configure blocklist webview
        const val RETHINK_BLOCKLIST_CONFIGURE_URL_PARAMETER = "tstamp"

        // The version tag value(response) for the update check (both on-device and app update)
        // TODO: have two different response versions for blocklist update and app update
        const val UPDATE_CHECK_RESPONSE_VERSION = 1

        // meta data for on-device blocklist
        data class OnDeviceBlocklistsMetadata(val url: String, val filename: String)

        val ONDEVICE_BLOCKLISTS = listOf(
            OnDeviceBlocklistsMetadata("https://download.rethinkdns.com/blocklists",
                                       ONDEVICE_BLOCKLIST_FILE_TAG_NAME),
            OnDeviceBlocklistsMetadata("https://download.rethinkdns.com/basicconfig",
                                       ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG),
            OnDeviceBlocklistsMetadata("https://download.rethinkdns.com/rank",
                                       ONDEVICE_BLOCKLIST_FILE_RD_FILE),
            OnDeviceBlocklistsMetadata("https://download.rethinkdns.com/trie",
                                       ONDEVICE_BLOCKLIST_FILE_TD_FILE))

        // url to download the rethinkdns apk file
        const val RETHINK_APP_DOWNLOAD_LINK = "https://rethinkdns.com/download"

        // base-url for bravedns
        const val BRAVE_BASE_STAMP = "https://basic.bravedns.com/"

        // base-url stamp for configure blocklist
        const val RETHINK_BLOCKLIST_CONFIGURE_BASE_STAMP = "rethinkdns.com/configure"

        // base-url for bravedns
        const val BRAVE_BASE_URL = "bravedns.com"

        // base-url for rethinkdns
        const val RETHINK_BASE_URL = "rethinkdns.com"

        // json object constants received as part of update check
        // FIXME: Avoid usage of these parameters, map to POJO instead
        const val JSON_VERSION = "version"
        const val JSON_UPDATE = "update"
        const val JSON_LATEST = "latest"

        // Firewall app category constants
        // category: system components
        const val APP_CAT_SYSTEM_COMPONENTS = "System Components"

        // category: system apps
        const val APP_CAT_SYSTEM_APPS = "System Apps"

        // category: others
        const val APP_CAT_OTHER = "Other"

        // category: non-app system
        const val APP_NON_APP = "Non-App System"

        // category: installed apps
        const val INSTALLED_CAT_APPS = "Installed Apps"

        // app type unknown
        const val UNKNOWN_APP = "Unknown"

        // No package applications
        const val NO_PACKAGE = "no_package"

        // Number of network log entries to store in the database.
        const val TOTAL_NETWORK_LOG_ENTRIES_THRESHOLD = 5000

        // invalid application uid
        const val INVALID_UID = -1

        // missing uid, used when the uid is undermined. see ConnectionTracer#getUidQ()
        const val MISSING_UID = -2000

        // label for rethinkdns plus doh endpoint
        const val RETHINK_DNS_PLUS = "RethinkDNS Plus"

        // maximum time delay before sending block connection response
        val DELAY_FIREWALL_RESPONSE_MS: Long = TimeUnit.SECONDS.toMillis(30)

        // constants used as part of intent to load the viewpager's screen
        const val VIEW_PAGER_SCREEN_TO_LOAD = "view_pager_screen"

        // name-value to pass as part of intent
        // determines whether launched from local/remote
        const val BLOCKLIST_LOCATION_INTENT_EXTRA = "location"

        // stamp name-value for blocklist configure screen
        const val BLOCKLIST_STAMP_INTENT_EXTRA = "stamp"

        // url name-value for blocklist configure screen
        const val BLOCKLIST_URL_INTENT_EXTRA = "url"

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

        // represents the orbot proxy mode (see AppMode#ProxyMode)
        const val ORBOT_PROXY = 10L

        // constants generated as part of BuildConfig.FLAVORS (playstore/fdroid/website)
        const val FLAVOR_PLAY = "play"
        const val FLAVOR_FDROID = "fdroid"
        const val FLAVOR_WEBSITE = "website"

        // represents the unknown port in the port map. see class KnownPorts
        const val PORT_VAL_UNKNOWN = "unknown"

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
        const val NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME = "ACCESSIBILITY" // accessibility failure name
        const val NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE = "ACCESSIBILITY_FAILURE" // accessibility failure value
        const val NOTIF_INTENT_EXTRA_NEW_APP_NAME = "NEW_APP" // new app install name
        const val NOTIF_INTENT_EXTRA_NEW_APP_VALUE = "NEW_APP_INSTALL_NOTIFY" // new app install value

        // new app install intent extra name for uid. see RefreshDatabase#makeNewAppVpnIntent()
        const val NOTIF_INTENT_EXTRA_APP_UID = "NEW_APP_UID"

        // DNS message type received by the DNS resolver
        const val NXDOMAIN = "NXDOMAIN"

        // Maximum time out for the DownloadManager to wait for download of local blocklist.
        // The time out value is set as 40 minutes.
        val WORK_MANAGER_TIMEOUT = TimeUnit.MINUTES.toMillis(40)

        // base-url for fav icon download
        const val FAV_ICON_URL = "https://icons.duckduckgo.com/ip2/"

        // The minimum interval before checking if the internal accessibility service
        // (used to block apps-not-in-use) is indeed running.
        // Ref: {@link com.celzero.bravedns.util.Utilities#isAccessibilityServiceEnabled} and
        // {@link com.celzero.bravedns.util.Utilities#isAccessibilityServiceEnabledViaSettingsSecure}
        val ACCESSIBILITY_SERVICE_HEARTBEAT_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(5)

        // View model - filter string
        const val FILTER_IS_SYSTEM = "isSystem"
        const val FILTER_IS_FILTER = "isFilter"
        const val FILTER_CATEGORY = "category:"

        // IPv4 uses 0.0.0.0 as an unspecified address
        const val UNSPECIFIED_IP = "0.0.0.0"
        // special port number not assigned to any app
        const val UNSPECIFIED_PORT = 0

        // IPv6 uses ::0 as an unspecified address
        const val UNSPECIFIED_IPV6 = "::0"
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

        // various download status used as part of Work manager. see DownloadWatcher#checkForDownload()
        const val DOWNLOAD_FAILURE = -1
        const val DOWNLOAD_SUCCESS = 1
        const val DOWNLOAD_RETRY = 0

        // To initiate / reset the timestamp in milliseconds
        const val INIT_TIME_MS = 0L

        // Bug report file and directory constants
        const val BUG_REPORT_DIR_NAME = "bugreport/"
        const val BUG_REPORT_ZIP_FILE_NAME = "rethinkdns.bugreport.zip"
        const val BUG_REPORT_FILE_NAME = "bugreport_"

        // maximum number of files allowed as part of bugreport zip file
        const val BUG_REPORT_MAX_FILES_ALLOWED = 20

        // secure sharing of files associated with an app, used in share bugreport file feature
        const val FILE_PROVIDER_NAME = BuildConfig.APPLICATION_ID + ".provider"


        // various time formats used in app
        const val TIME_FORMAT_1 = "HH:mm:ss"
        const val TIME_FORMAT_2 = "yy.MM (dd)"
        const val TIME_FORMAT_3 = "dd MMMM yyyy, HH:mm:ss"

        // default duration for pause state: 15mins
        val DEFAULT_PAUSE_TIME_MS = TimeUnit.MINUTES.toMillis(15)

        // increment/decrement value to pause vpn
        val PAUSE_VPN_EXTRA_MILLIS = TimeUnit.MINUTES.toMillis(1)

        // play services package name
        const val PKG_NAME_PLAY_STORE = "com.android.vending"
    }
}
