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
import androidx.lifecycle.lifecycleScope
import backend.Backend
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityOtherDnsListBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        updateSelectedStatus()
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

    private fun updateSelectedStatus() {
        // always use the id as Dnsx.Preffered as it is the primary dns id for now
        val state = VpnController.getDnsStatus(Backend.Preferred)
        val working =
            if (state == null) {
                false
            } else {
                when (Transaction.Status.fromId(state)) {
                    Transaction.Status.COMPLETE,
                    Transaction.Status.START -> {
                        true
                    }
                    else -> {
                        false
                    }
                }
            }
        highlightSelectedUi(working)
    }

    private fun highlightSelectedUi(working: Boolean) {
        val strokeColor: Int
        val textColor: Int
        if (working) {
            strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
            textColor = fetchColor(this, R.attr.secondaryTextColor)
        } else {
            strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentBad)
            textColor = fetchColor(this, R.attr.accentBad)
        }

        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                b.cardDoh.strokeColor = strokeColor
                b.cardDoh.strokeWidth = 2
                b.initialDoh.setTextColor(textColor)
                b.abbrDoh.setTextColor(textColor)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                b.cardDnsproxy.strokeColor = strokeColor
                b.cardDnsproxy.strokeWidth = 2
                b.initialDnsproxy.setTextColor(textColor)
                b.abbrDnsproxy.setTextColor(textColor)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                b.cardDnscrypt.strokeColor = strokeColor
                b.cardDnscrypt.strokeWidth = 2
                b.initialDnscrypt.setTextColor(textColor)
                b.abbrDnscrypt.setTextColor(textColor)
            }
            AppConfig.DnsType.DOT -> {
                b.cardDot.strokeColor = strokeColor
                b.cardDot.strokeWidth = 2
                b.initialDot.setTextColor(textColor)
                b.abbrDot.setTextColor(textColor)
            }
            AppConfig.DnsType.ODOH -> {
                b.cardOdoh.strokeColor = strokeColor
                b.cardOdoh.strokeWidth = 2
                b.initialOdoh.setTextColor(textColor)
                b.abbrOdoh.setTextColor(textColor)
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                b.cardRethinkDns.strokeColor = strokeColor
                b.cardRethinkDns.strokeWidth = 2
                b.initialRethinkDns.setTextColor(textColor)
                b.abbrRethinkDns.setTextColor(textColor)
            }
            else -> {
                // no-op
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
