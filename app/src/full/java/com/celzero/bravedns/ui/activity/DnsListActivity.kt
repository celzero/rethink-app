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
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityOtherDnsListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import org.koin.android.ext.android.inject

class DnsListActivity : AppCompatActivity(R.layout.activity_other_dns_list) {
    private val b by viewBinding(ActivityOtherDnsListBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupClickListeners() {
        b.cardDnscrypt.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DNS_CRYPT.index
                )
            )
        }

        b.cardDnsproxy.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DNS_PROXY.index
                )
            )
        }

        b.cardDoh.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DOH.index
                )
            )
        }

        b.cardDot.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DOT.index
                )
            )
        }

        b.cardOdoh.setOnClickListener {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.ODOH.index
                )
            )
        }

        b.cardRethinkDns.setOnClickListener { invokeRethinkActivity() }
    }

    private fun invokeRethinkActivity() {
        val intent = Intent(this, ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(
            ConfigureRethinkBasicActivity.INTENT,
            ConfigureRethinkBasicActivity.FragmentLoader.DB_LIST.ordinal
        )
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        resetUi()
        highlightSelectedUi()
    }

    private fun resetUi() {
        b.cardDoh.strokeWidth = 0
        b.cardDnsproxy.strokeWidth = 0
        b.cardDnscrypt.strokeWidth = 0
        b.cardDot.strokeWidth = 0
        b.cardOdoh.strokeWidth = 0
        b.cardRethinkDns.strokeWidth = 0
        b.initialDoh.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.abbrDoh.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.initialDnsproxy.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.abbrDnsproxy.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.initialDnscrypt.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.abbrDnscrypt.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.initialDot.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.abbrDot.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.initialOdoh.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.abbrOdoh.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.initialRethinkDns.setTextColor(fetchColor(this, R.attr.primaryTextColor))
        b.abbrRethinkDns.setTextColor(fetchColor(this, R.attr.primaryTextColor))
    }

    private fun highlightSelectedUi() {
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                b.cardDoh.strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
                b.cardDoh.strokeWidth = 2
                b.initialDoh.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
                b.abbrDoh.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.DNS_PROXY -> {
                b.cardDnsproxy.strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
                b.cardDnsproxy.strokeWidth = 2
                b.initialDnsproxy.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
                b.abbrDnsproxy.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.DNSCRYPT -> {
                b.cardDnscrypt.strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
                b.cardDnscrypt.strokeWidth = 2
                b.initialDnscrypt.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
                b.abbrDnscrypt.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.DOT -> {
                b.cardDot.strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
                b.cardDot.strokeWidth = 2
                b.initialDot.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
                b.abbrDot.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.ODOH -> {
                b.cardOdoh.strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
                b.cardOdoh.strokeWidth = 2
                b.initialOdoh.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
                b.abbrOdoh.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                b.cardRethinkDns.strokeColor =
                    UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
                b.cardRethinkDns.strokeWidth = 2
                b.initialRethinkDns.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
                b.abbrRethinkDns.setTextColor(fetchColor(this, R.attr.secondaryTextColor))
            }
            else -> {
                // no-op
            }
        }
    }
}
