/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityOtherDnsListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.fetchColor
import org.koin.android.ext.android.inject

class DnsListActivity : AppCompatActivity(R.layout.activity_other_dns_list) {
    private val b by viewBinding(ActivityOtherDnsListBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {

        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                b.dohText.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.DNS_PROXY -> {
                b.dnsProxyText.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.DNSCRYPT -> {
                b.dnsCryptText.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.DOT -> {
                b.dotText.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.ODOH -> {
                b.odohText.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            else -> {
                // no-op
            }
        }

        b.dnsCryptCard.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DNS_CRYPT.index
                )
            )
        }

        b.dnsProxyCard.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DNS_PROXY.index
                )
            )
        }

        b.dohCard.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DOH.index
                )
            )
        }

        b.dotCard.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DOT.index
                )
            )
        }

        b.odohCard.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.ODOH.index
                )
            )
        }
    }
}
