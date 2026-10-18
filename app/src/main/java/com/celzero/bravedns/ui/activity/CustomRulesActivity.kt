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
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.bottomsheet.CustomDomainRulesBtmSheet
import com.celzero.bravedns.ui.bottomsheet.CustomIpRulesBtmSheet
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.controls.OriginalTabStrip
import com.celzero.bravedns.ui.compose.rules.RulesPage
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class CustomRulesActivity : BaseActivity() {
    private var fragmentIndex = 0
    private var rulesType = RULES.APP_SPECIFIC_RULES.ordinal
    private var uid = Constants.UID_EVERYBODY
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private val ipModel: CustomIpViewModel by viewModel()
    private val domainModel by inject<CustomDomainViewModel>()

    enum class Tabs(val screen: Int) {
        IP_RULES(0),
        DOMAIN_RULES(1);

        companion object {
            fun getCount(): Int {
                return entries.size
            }
        }
    }

    enum class RULES(val type: Int) {
        ALL_RULES(0),
        APP_SPECIFIC_RULES(1);

        companion object {
            fun getType(type: Int): RULES {
                return when (type) {
                    0 -> ALL_RULES
                    1 -> APP_SPECIFIC_RULES
                    else -> APP_SPECIFIC_RULES
                }
            }
        }
    }

    companion object {
        const val INTENT_RULES = "INTENT_RULES"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)


        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, 0)
        rulesType = intent.getIntExtra(INTENT_RULES, RULES.APP_SPECIFIC_RULES.type)
        uid = intent.getIntExtra(Constants.INTENT_UID, Constants.UID_EVERYBODY)
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        val rules = RULES.getType(rulesType)
        ipModel.setUid(uid)
        domainModel.setUid(uid)
        val app = rules == RULES.APP_SPECIFIC_RULES
        val ipSource = if (app) ipModel.customIpDetails else ipModel.allIpRules
        val domainSource = if (app) domainModel.customDomains else domainModel.allDomainRules
        val ipCount = if (app) ipModel.ipRulesCount(uid) else ipModel.allIpRulesCount()
        val domainCount = if (app) domainModel.domainRulesCount(uid) else domainModel.allDomainRulesCount()
        setContent {
            RethinkTheme {
                val pager = rememberPagerState(initialPage = fragmentIndex.coerceIn(0, Tabs.getCount() - 1), pageCount = { Tabs.getCount() })
                val scope = rememberCoroutineScope()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        OriginalTabStrip(pager.currentPage,
                            Tabs.entries.map { tab -> stringResource(if (tab == Tabs.IP_RULES) R.string.univ_view_blocked_ip else R.string.dc_custom_block_heading) },
                            onSelect = { page -> scope.launch { pager.animateScrollToPage(page) } },
                            pageOffset = pager.currentPageOffsetFraction)
                        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                            if (page == Tabs.IP_RULES.screen) RulesPage(false, uid, rules, ipSource, ipCount, ipModel::setFilter, eventLogger, pager.settledPage == page) { ip ->
                                CustomIpRulesBtmSheet.newInstance(ip).let { it.show(supportFragmentManager, it.tag) }
                            } else RulesPage(true, uid, rules, domainSource, domainCount, domainModel::setFilter, eventLogger, pager.settledPage == page) { domain ->
                                CustomDomainRulesBtmSheet.newInstance(domain).let { it.show(supportFragmentManager, it.tag) }
                            }
                        }
                    }
                }
            }
        }
        observeAppState()
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
