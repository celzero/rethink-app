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

package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityNetworkLogsBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity.Companion.RULES_SEARCH_ID
import com.celzero.bravedns.ui.fragment.ConnectionTrackerFragment
import com.celzero.bravedns.ui.fragment.DnsLogFragment
import com.celzero.bravedns.ui.fragment.RethinkLogFragment
import com.celzero.bravedns.ui.fragment.WgNwStatsFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.tabs.TabLayoutMediator
import org.koin.android.ext.android.inject

class NetworkLogsActivity : AppCompatActivity(R.layout.activity_network_logs) {
    private val b by viewBinding(ActivityNetworkLogsBinding::bind)
    private var fragmentIndex = 0
    private var searchParam = ""
    // to handle search navigation from universal firewall, to show only the search results
    // of the selected universal rule, show only network logs tab
    private var isUnivNavigated = false
    // to handle the wireguard connections
    private var isWireGuardLogs = false

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    enum class Tabs(val screen: Int) {
        NETWORK_LOGS(0),
        DNS_LOGS(1),
        RETHINK_LOGS(2),
        WIREGUARD_STATS(3)
    }
    
    companion object {
        const val RULES_SEARCH_ID_WIREGUARD = "W:"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, 0)
        searchParam = intent.getStringExtra(Constants.SEARCH_QUERY) ?: ""
        if (searchParam.contains(RULES_SEARCH_ID)) {
            isUnivNavigated = true
        } else if(searchParam.contains(RULES_SEARCH_ID_WIREGUARD)) {
            isWireGuardLogs = true
        }
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {

        b.logsActViewpager.adapter =
            object : FragmentStateAdapter(this) {
                override fun createFragment(position: Int): Fragment {
                    return getFragment(position)
                }

                override fun getItemCount(): Int {
                    return getCount()
                }
            }

        TabLayoutMediator(b.logsActTabLayout, b.logsActViewpager) { tab, position
                -> // Styling each tab here
                tab.text = getTabText(position)
            }
            .attach()

        b.logsActViewpager.setCurrentItem(fragmentIndex, false)

        observeAppState()

        b.appLogs.setOnClickListener {
            openConsoleLogActivity()
        }
    }

    private fun openConsoleLogActivity() {
        val intent = Intent(this, ConsoleLogActivity::class.java)
        startActivity(intent)
    }

    private fun getCount(): Int {
        if (isUnivNavigated) {
            return 1
        }
        if (isWireGuardLogs) {
            return 3
        }

        var count = 0
        if (persistentState.routeRethinkInRethink) {
            count = 1
        }
        return when (appConfig.getBraveMode()) {
            AppConfig.BraveMode.DNS -> count + 1
            AppConfig.BraveMode.FIREWALL -> count + 1
            AppConfig.BraveMode.DNS_FIREWALL -> count + 2
        }
    }

    private fun getFragment(position: Int): Fragment {
        if (isUnivNavigated) {
            return ConnectionTrackerFragment.newInstance(searchParam)
        }
        if (isWireGuardLogs) {
            return when(position) {
                0 -> ConnectionTrackerFragment.newInstance(searchParam)
                1 -> DnsLogFragment.newInstance(searchParam)
                2 -> WgNwStatsFragment.newInstance(searchParam)
                else -> ConnectionTrackerFragment.newInstance(searchParam)
            }
        }
        return when (position) {
            0 -> {
                if (appConfig.getBraveMode().isDnsMode()) {
                    DnsLogFragment.newInstance(searchParam)
                } else if (appConfig.getBraveMode().isFirewallMode()) {
                    ConnectionTrackerFragment.newInstance(searchParam)
                } else {
                    ConnectionTrackerFragment.newInstance(searchParam)
                }
            }
            1 -> {
                if (appConfig.getBraveMode().isDnsMode()) {
                    RethinkLogFragment.newInstance(searchParam)
                } else if (appConfig.getBraveMode().isFirewallMode()) {
                    RethinkLogFragment.newInstance(searchParam)
                } else {
                    DnsLogFragment.newInstance(searchParam)
                }
            }
            2 -> {
                RethinkLogFragment.newInstance(searchParam)
            }
            else -> {
                ConnectionTrackerFragment.newInstance(searchParam)
            }
        }
    }

    // get tab text based on brave mode
    private fun getTabText(position: Int): String {
        if (isWireGuardLogs) {
            return when(position) {
                0 -> getString(R.string.firewall_act_network_monitor_tab)
                1 -> getString(R.string.dns_mode_info_title)
                2 -> getString(R.string.title_statistics)
                else -> getString(R.string.firewall_act_network_monitor_tab)
            }
        }

        return when (position) {
            0 -> {
                if (appConfig.getBraveMode().isDnsMode()) {
                    getString(R.string.dns_mode_info_title)
                } else if (appConfig.getBraveMode().isFirewallMode()) {
                    getString(R.string.firewall_act_network_monitor_tab)
                } else {
                    getString(R.string.firewall_act_network_monitor_tab)
                }
            }
            1 -> {
                if (appConfig.getBraveMode().isDnsMode()) {
                    getString(R.string.app_name)
                } else if (appConfig.getBraveMode().isFirewallMode()) {
                    getString(R.string.app_name)
                } else {
                    getString(R.string.dns_mode_info_title)
                }
            }
            2 -> {
                getString(R.string.app_name)
            }
            else -> {
                getString(R.string.firewall_act_network_monitor_tab)
            }
        }
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this) {
            if (it == BraveVPNService.State.PAUSED) {
                startActivity(Intent().setClass(this, PauseActivity::class.java))
                finish()
            }
        }
    }
}
