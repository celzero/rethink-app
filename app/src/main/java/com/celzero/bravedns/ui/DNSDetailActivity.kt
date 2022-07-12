/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.content.Context
import android.content.res.Configuration
import android.icu.text.CompactDecimalFormat
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityDnsDetailBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.Companion.openPauseActivityAndFinish
import com.google.android.material.tabs.TabLayoutMediator
import org.koin.android.ext.android.inject
import java.util.*

class DnsDetailActivity : AppCompatActivity(R.layout.activity_dns_detail) {
    private val b by viewBinding(ActivityDnsDetailBinding::bind)

    private var fragmentIndex = 0
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    enum class Tabs(val screen: Int) {
        LOGS(0), CONFIGURE(1);

        companion object {
            fun getCount(): Int {
                return values().count()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, fragmentIndex)
        init()
        initObservers()
    }

    private fun init() {

        if (!persistentState.logsEnabled) {
            b.latencyTxt.visibility = View.GONE
            b.totalQueriesTxt.visibility = View.GONE
            return
        }

        b.dnsDetailActViewpager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    Tabs.LOGS.screen -> DnsLogFragment.newInstance()
                    Tabs.CONFIGURE.screen -> DnsConfigureFragment.newInstance()
                    else -> DnsConfigureFragment.newInstance()
                }
            }

            override fun getItemCount(): Int {
                return Tabs.getCount()
            }
        }

        TabLayoutMediator(b.dnsDetailActTabLayout,
                          b.dnsDetailActViewpager) { tab, position -> // Styling each tab here
            tab.text = when (position) {
                Tabs.LOGS.screen -> getString(R.string.dns_act_log)
                Tabs.CONFIGURE.screen -> getString(R.string.dns_act_configure_tab)
                else -> getString(R.string.dns_act_configure_tab)
            }
        }.attach()

        b.dnsDetailActViewpager.setCurrentItem(fragmentIndex, true)

    }

    private fun initObservers() {
        observeDnsStats()
        observeDnscryptStatus()
        observeAppState()
    }

    private fun observeDnsStats() {
        persistentState.dnsRequestsCountLiveData.observe(this) {
            val lifeTimeConversion = formatDecimal(it)
            b.totalQueriesTxt.text = getString(R.string.dns_logs_lifetime_queries,
                                               lifeTimeConversion)
        }

        persistentState.dnsBlockedCountLiveData.observe(this) {
            val blocked = formatDecimal(it)
            b.latencyTxt.text = getString(R.string.dns_logs_blocked_queries, blocked)
        }

    }

    private fun formatDecimal(i: Long?): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            CompactDecimalFormat.getInstance(Locale.US,
                                             CompactDecimalFormat.CompactStyle.SHORT).format(i)
        } else {
            i.toString()
        }
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this) {
            if (it == BraveVPNService.State.PAUSED) {
                openPauseActivityAndFinish(this)
            }
        }

        appConfig.getConnectedDnsObservable().observe(this) {
            updateConnectedStatus(it)
        }
    }

    private fun updateConnectedStatus(connectedDns: String) {
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_dns_crypt_status)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, connectedDns)
            }
        }
    }

    // FIXME: Create common observer for dns instead of separate observers
    private fun observeDnscryptStatus() {
        appConfig.getDnscryptCountObserver().observe(this) {
            if (appConfig.getDnsType() != AppConfig.DnsType.DNSCRYPT) return@observe

            val connectedCrypt = getString(R.string.configure_dns_crypt, it.toString())
            b.connectedStatusTitle.text = connectedCrypt
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

}
