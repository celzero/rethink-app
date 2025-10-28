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
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LiveData
import androidx.paging.PagingData
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ActivityDetailedStatisticsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.DetailedStatisticsViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DetailedStatisticsActivity : AppCompatActivity(R.layout.activity_detailed_statistics) {
    private val b by viewBinding(ActivityDetailedStatisticsBinding::bind)

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
        setSubTitle(statType, timeCategory)
        setRecyclerView(statType, timeCategory)
    }

    private fun setSubTitle(type: SummaryStatisticsFragment.SummaryStatisticsType, timeCategory: SummaryStatisticsViewModel.TimeCategory) {
        if (type == SummaryStatisticsFragment.SummaryStatisticsType.TOP_ACTIVE_CONNS) {
            b.dsaSubtitle.visibility = View.GONE
            return
        }

        b.dsaSubtitle.text =
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
        timeCategory: SummaryStatisticsViewModel.TimeCategory
    ) {

        b.dsaRecycler.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(this)
        b.dsaRecycler.layoutManager = layoutManager

        val recyclerAdapter = SummaryStatisticsAdapter(this, persistentState, appConfig, type)
        recyclerAdapter.setTimeCategory(timeCategory)

        viewModel.timeCategoryChanged(timeCategory)
        handleStatType(type).observe(this) { recyclerAdapter.submitData(this.lifecycle, it) }

        // remove the view if there is no data
        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.dsaRecycler.visibility = View.GONE
                    b.dsaNoDataRl.visibility = View.VISIBLE
                } else {
                    b.dsaRecycler.visibility = View.VISIBLE
                    b.dsaNoDataRl.visibility = View.GONE
                }
            } else {
                b.dsaRecycler.visibility = View.VISIBLE
                b.dsaNoDataRl.visibility = View.GONE
            }
        }
        b.dsaRecycler.adapter = recyclerAdapter
    }

    private fun handleStatType(
        type: SummaryStatisticsFragment.SummaryStatisticsType
    ): LiveData<PagingData<AppConnection>> {
        viewModel.setData(type)
        return when (type) {
            SummaryStatisticsFragment.SummaryStatisticsType.TOP_ACTIVE_CONNS -> {
                b.dsaTitle.text = getString(R.string.top_active_conns)
                viewModel.getAllActiveConns
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                b.dsaTitle.text = getString(R.string.ssv_app_network_activity_heading)
                viewModel.getAllAllowedAppNetworkActivity
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                b.dsaTitle.text = getString(R.string.ssv_app_blocked_heading)
                viewModel.getAllBlockedAppNetworkActivity
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_ASN -> {
                b.dsaTitle.text = getString(R.string.most_contacted_asn)
                viewModel.getAllAllowedAsn
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_ASN -> {
                b.dsaTitle.text = getString(R.string.most_blocked_asn)
                viewModel.getAllBlockedAsn
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                b.dsaTitle.text = getString(R.string.ssv_most_contacted_domain_heading)
                viewModel.getAllContactedDomains
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                b.dsaTitle.text = getString(R.string.ssv_most_blocked_domain_heading)
                viewModel.getAllBlockedDomains
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                b.dsaTitle.text = getString(R.string.ssv_most_contacted_ips_heading)
                viewModel.getAllContactedIps
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                b.dsaTitle.text = getString(R.string.ssv_most_blocked_ips_heading)
                viewModel.getAllBlockedIps
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                b.dsaTitle.text = getString(R.string.ssv_most_contacted_countries_heading)
                viewModel.getAllContactedCountries
            }
        }
    }
}
