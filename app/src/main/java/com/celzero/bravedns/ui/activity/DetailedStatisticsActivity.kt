/*
 * Copyright 2024 RethinkDNS and its authors
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
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.statistics.PagedStatisticsScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LiveData
import androidx.paging.PagingData
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.DetailedStatisticsViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DetailedStatisticsActivity : BaseActivity() {
    private var screenTitle by mutableStateOf("")
    private var subtitle by mutableStateOf<String?>(null)
    private var presenter: SummaryStatisticsAdapter? = null
    private var empty by mutableStateOf(false)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val viewModel: DetailedStatisticsViewModel by viewModel()

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    companion object {
        const val INTENT_TYPE = "STATISTICS_TYPE"
        const val INTENT_TIME_CATEGORY = "TIME_CATEGORY"
        const val INTENT_START_TIME = "START_TIME"

        // optional: when present, the blocklists screen is scoped to this uid
        // (sent by the per-app screen); absent means all apps
        const val INTENT_UID = "UID"
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

        val type =
            intent.getIntExtra(
                INTENT_TYPE,
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_APPS.tid
            )
        val tc = intent.getIntExtra(INTENT_TIME_CATEGORY, 0)
        val timeCategory =
            SummaryStatisticsViewModel.TimeCategory.fromValue(tc)
                ?: SummaryStatisticsViewModel.TimeCategory.ONE_HOUR
        val statType = SummaryStatisticsFragment.SummaryStatisticsType.getType(type)
        // per-app scope is only sent by the App Info screen; the stats screen
        // omits it so the drill-down stays device-wide there
        val uid = intent.getIntExtra(
            INTENT_UID,
            com.celzero.bravedns.util.Constants.INVALID_UID
        )
        val startTime =
            if (intent.hasExtra(INTENT_START_TIME)) {
                intent.getLongExtra(INTENT_START_TIME, 0L)
            } else {
                null
            }
        if (uid != com.celzero.bravedns.util.Constants.INVALID_UID) {
            viewModel.setUid(uid)
        }
        setSubTitle(statType, timeCategory)
        setRecyclerView(statType, timeCategory, uid, startTime)
        setContent { RethinkTheme { PagedStatisticsScreen(screenTitle, subtitle, empty, presenter) } }
    }

    private fun setSubTitle(type: SummaryStatisticsFragment.SummaryStatisticsType, timeCategory: SummaryStatisticsViewModel.TimeCategory) {
        if (type == SummaryStatisticsFragment.SummaryStatisticsType.TOP_ACTIVE_CONNS) {
            subtitle = null
            return
        }

        subtitle =
            when (timeCategory) {
                SummaryStatisticsViewModel.TimeCategory.ONE_HOUR -> {
                    getString(
                        R.string.three_argument,
                        getString(R.string.lbl_last),
                        getString(R.string.numeric_one),
                        getString(R.string.lbl_hour)
                    )
                }
                SummaryStatisticsViewModel.TimeCategory.TWENTY_FOUR_HOUR -> {
                    getString(
                        R.string.three_argument,
                        getString(R.string.lbl_last),
                        getString(R.string.numeric_twenty_four),
                        getString(R.string.lbl_hour)
                    )
                }
                SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS -> {
                    getString(
                        R.string.three_argument,
                        getString(R.string.lbl_last),
                        getString(R.string.numeric_seven),
                        getString(R.string.lbl_day)
                    )
                }
            }
    }

    private fun setRecyclerView(
        type: SummaryStatisticsFragment.SummaryStatisticsType,
        timeCategory: SummaryStatisticsViewModel.TimeCategory,
        uid: Int,
        startTime: Long?
    ) {

        val recyclerAdapter = SummaryStatisticsAdapter(this, persistentState, appConfig, type)
        recyclerAdapter.setUid(uid)
        recyclerAdapter.setTimeCategory(timeCategory)
        presenter = recyclerAdapter
        viewModel.timeCategoryChanged(timeCategory, startTime)
        handleStatType(type).observe(this) { recyclerAdapter.submitData(this.lifecycle, it) }
        recyclerAdapter.addLoadStateListener { empty = it.append.endOfPaginationReached && recyclerAdapter.itemCount < 1 }
    }

    private fun handleStatType(
        type: SummaryStatisticsFragment.SummaryStatisticsType
    ): LiveData<PagingData<AppConnection>> {
        viewModel.setData(type)
        return when (type) {
            SummaryStatisticsFragment.SummaryStatisticsType.TOP_ACTIVE_CONNS -> {
                screenTitle = getString(R.string.top_active_conns)
                viewModel.getAllActiveConns
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                screenTitle = getString(R.string.ssv_app_network_activity_heading)
                viewModel.getAllAllowedAppNetworkActivity
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                screenTitle = getString(R.string.ssv_app_blocked_heading)
                viewModel.getAllBlockedAppNetworkActivity
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_ASN -> {
                screenTitle = getString(R.string.most_contacted_asn)
                viewModel.getAllAllowedAsn
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_ASN -> {
                screenTitle = getString(R.string.most_blocked_asn)
                viewModel.getAllBlockedAsn
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                screenTitle = getString(R.string.ssv_most_contacted_domain_heading)
                viewModel.getAllContactedDomains
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                screenTitle = getString(R.string.ssv_most_blocked_domain_heading)
                viewModel.getAllBlockedDomains
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                screenTitle = getString(R.string.ssv_most_contacted_ips_heading)
                viewModel.getAllContactedIps
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                screenTitle = getString(R.string.ssv_most_blocked_ips_heading)
                viewModel.getAllBlockedIps
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                screenTitle = getString(R.string.ssv_most_contacted_countries_heading)
                viewModel.getAllContactedCountries
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS -> {
                screenTitle = getString(R.string.ssv_blocklists_heading)
                viewModel.getAllBlockedBlocklists
            }
        }
    }
}
