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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.rememberFragmentState
import androidx.lifecycle.Lifecycle
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.controls.OriginalTabStrip
import com.celzero.bravedns.ui.compose.themeColor
import kotlinx.coroutines.launch

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity.Companion.RULES_SEARCH_ID
import com.celzero.bravedns.ui.fragment.ConnectionTrackerFragment
import com.celzero.bravedns.ui.fragment.ConnectionTrackerFragment.Companion.MERGE_RETHINK_LOGS
import com.celzero.bravedns.ui.fragment.DnsLogFragment
import com.celzero.bravedns.ui.fragment.RethinkLogFragment
import com.celzero.bravedns.ui.fragment.WgNwStatsFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject

class NetworkLogsActivity : BaseActivity() {
    private var fragmentIndex = 0
    private var searchParam = ""
    // to handle search navigation from universal firewall, to show only the search results
    // of the selected universal rule, show only network logs tab
    private var isUnivNavigated = false
    // to handle the wireguard connections
    private var isWireGuardLogs = false
    private var isRpnLogs = false

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
        const val RULES_SEARCH_ID_RPN = "RPN:"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        b.root.applySystemBarEdgeInsets()

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, 0)
        searchParam = intent.getStringExtra(Constants.SEARCH_QUERY) ?: ""
        if (searchParam.contains(RULES_SEARCH_ID)) {
            isUnivNavigated = true
        } else if(searchParam.contains(RULES_SEARCH_ID_WIREGUARD)) {
            isWireGuardLogs = true
        } else if (searchParam.contains(RULES_SEARCH_ID_RPN)) {
            isRpnLogs = true
        }
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun init() {
        observeAppState()
        setContent { RethinkTheme {
            val count = getCount()
            val pager = rememberPagerState(initialPage = fragmentIndex.coerceIn(0, count - 1), pageCount = { count })
            val scope = rememberCoroutineScope()
            val states = (0 until count).map { page -> key(page) { rememberFragmentState() } }
            val specifications = remember(count) { List(count) { getFragment(it) } }
            // Own insets above the pager so both tabs and every Fragment stay inside the
            // status bar, cutout, navigation bar and keyboard without per-page spacers.
            Column(Modifier.fillMaxSize().background(themeColor(R.attr.background)).safeDrawingPadding().testTag("network-logs-content")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OriginalTabStrip(pager.currentPage, List(count) { getTabText(it) },
                        onSelect = { page -> scope.launch { pager.animateScrollToPage(page) } },
                        modifier = Modifier.weight(1f), pageOffset = pager.currentPageOffsetFraction)
                    IconButton(::openConsoleLogActivity, Modifier.padding(end = 15.dp, bottom = 5.dp)) { Icon(painterResource(R.drawable.ic_android_icon), stringResource(R.string.console_log_title), Modifier.alpha(.7f), tint = Color.Unspecified) }
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                    val specification = specifications[page]
                    // Lifecycle-only boundary; each child Fragment renders real Compose content.
                    AndroidFragment(clazz = specification.javaClass, modifier = Modifier.fillMaxSize(), fragmentState = states[page], arguments = specification.arguments ?: Bundle.EMPTY,
                        maxLifecycle = if (pager.settledPage == page) Lifecycle.State.RESUMED else Lifecycle.State.STARTED)
                }
            }
        } }
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
        if (persistentState.routeRethinkInRethink && !MERGE_RETHINK_LOGS) {
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
        if (isRpnLogs) {
            return when(position) {
                0 -> ConnectionTrackerFragment.newInstance(searchParam)
                1 -> DnsLogFragment.newInstance(searchParam)
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
