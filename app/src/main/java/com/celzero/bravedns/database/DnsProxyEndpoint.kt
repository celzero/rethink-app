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

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager

@Entity(tableName = "DNSProxyEndpoint")
class DnsProxyEndpoint// Room auto-increments id when its set to zero.
// A non-zero id overrides and sets caller-specified id instead.
    (
    @PrimaryKey(autoGenerate = true) var id: Int,
    var proxyName: String,
    var proxyType: String,
    proxyAppName: String,
    proxyIP: String,
    var proxyPort: Int,
    var isSelected: Boolean,
    var isCustom: Boolean,
    modifiedDataTime: Long,
    var latency: Int
) {
    var proxyAppName: String? = proxyAppName
    var proxyIP: String? = proxyIP
    var modifiedDataTime: Long = 0L

    override fun equals(other: Any?): Boolean {
        if (other !is DnsProxyEndpoint) return false
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }

    init {
        if (modifiedDataTime != 0L) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
    }

    fun isDeletable(): Boolean {
        return isCustom && !isSelected
    }

    fun getExplanationText(context: Context): String {
        // if selected, add forwarding to the text..
        return if (this.isSelected) {
            // don't show the app name if there is no app selected, earlier "Nobody" was labelled
            // for no app.
            if (this.proxyAppName != context.getString(R.string.cd_custom_dns_proxy_default_app)) {
                val app = FirewallManager.getAppInfoByPackage(this.proxyAppName)?.appName
                context.getString(
                    R.string.settings_socks_forwarding_desc, this.proxyIP,
                    this.proxyPort.toString(), app
                )
            } else {
                context.getString(
                    R.string.settings_socks_forwarding_desc_no_app, this.proxyIP,
                    this.proxyPort.toString()
                )
            }
        } else {
            if (this.proxyAppName != context.getString(R.string.cd_custom_dns_proxy_default_app)) {
                val app = FirewallManager.getAppInfoByPackage(this.proxyAppName)?.appName
                context.getString(
                    R.string.dns_proxy_desc, this.proxyIP, this.proxyPort.toString(),
                    app
                )
            } else {
                context.getString(
                    R.string.dns_proxy_desc_no_app, this.proxyIP,
                    this.proxyPort.toString()
                )
            }

        }
    }

    fun getPackageName(): String? {
        return proxyAppName
    }

    fun isInternal(context: Context): Boolean {
        return this.proxyType == context.getString(R.string.cd_dns_proxy_mode_internal)
    }
}
