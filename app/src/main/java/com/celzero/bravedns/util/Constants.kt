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

class Constants {

    companion object{

        //Log
        const val LOG_TAG = "RethinkDNS"

        //Download path and file names
        const val DOWNLOAD_PATH = "/downloads"
        const val FILE_TAG_NAME ="/filetag.json"
        const val FILE_BASIC_CONFIG = "/basicconfig.json"
        const val FILE_RD_FILE = "/rd.txt"
        const val FILE_TD_FILE = "/td.txt"

        //Download URL's
        const val JSON_DOWNLOAD_BLOCKLIST_LINK = "https://download.bravedns.com/blocklists"
        const val JSON_DOWNLOAD_BASIC_CONFIG_LINK = "https://download.bravedns.com/basicconfig"
        const val JSON_DOWNLOAD_BASIC_RANK_LINK = "https://download.bravedns.com/rank"
        const val JSON_DOWNLOAD_BASIC_TRIE_LINK = "https://download.bravedns.com/trie"
        const val REFRESH_BLOCKLIST_URL = "https://download.bravedns.com/update/blocklists?tstamp="
        const val APP_DOWNLOAD_AVAILABLE_CHECK = "https://download.bravedns.com/update/app?vcode="
        const val CONFIGURE_BLOCKLIST_URL_LOCAL = "https://bravedns.com/configure?v=app&tstamp="
        const val CONFIGURE_BLOCKLIST_URL_REMOTE =  "https://bravedns.com/configure?v=app"

        const val APP_DOWNLOAD_LINK = "https://bravedns.com/downloads"

        const val BRAVE_BASIC_URL = "basic.bravedns"
        const val APPEND_VCODE = "vcode="

        //Firewall system components
        const val APP_CAT_SYSTEM_COMPONENTS = "System Components"
        const val APP_CAT_SYSTEM_APPS = "System Apps"
        const val APP_CAT_OTHER = "Other"
        const val APP_NON_APP = "Non-App System "
        const val INSTALLED_CAT_APPS = "Installed Apps"

        //No package applications
        const val NO_PACKAGE = "no_package"

        //Whitelist
        const val RECOMMENDED  = " [Recommended]"

        //Application download source
        const val DOWNLOAD_SOURCE_PLAY_STORE = 1
        const val DOWNLOAD_SOURCE_OTHERS = 2

        //Network monitor
        const val FIREWALL_CONNECTIONS_IN_DB = 21000

        const val MISSING_UID = -2000

        const val RETHINK_DNS_PLUS = "RethinkDNS Plus"

        const val DELAY_FOR_BLOCK_RESPONSE : Long = 30000
        const val BACKGROUND_DELAY_CHECK : Long = 4000
        const val BACKGROUND_DELAY_CHECK_REMAINING : Long = 22000
        const val BACKGROUND_DELAY_CHECK_3 : Long = 12000

        const val DNS_TYPE_DOH = 1
        const val DNS_TYPE_DNS_CRYPT = 2
        const val DNS_TYPE_DNS_PROXY = 3

    }

}
