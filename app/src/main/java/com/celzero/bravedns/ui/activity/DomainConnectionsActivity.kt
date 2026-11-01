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

import com.celzero.bravedns.ui.compose.controls.*
import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.statistics.PagedStatisticsScreen
import com.celzero.bravedns.ui.compose.statistics.selectStatisticsTags
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.BlocklistAppsAdapter
import com.celzero.bravedns.adapter.DomainConnectionsAdapter
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.util.SnackbarHelper.applySystemBarEdgeInsets
import com.celzero.bravedns.viewmodel.DomainConnectionsViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DomainConnectionsActivity : BaseActivity(){
    private var screenTitle by mutableStateOf("")
    private var subtitle by mutableStateOf<String?>(null)
    private var empty by mutableStateOf(false)
    private var presenter by mutableStateOf<com.celzero.bravedns.ui.compose.appdetails.AppConnectionsPresenter?>(null)
    private var groups by mutableStateOf<BlocklistAppsAdapter?>(null)
    private var tags by mutableStateOf<List<String>>(emptyList())
    private var onTagsChanged: () -> Unit = {}
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val viewModel by viewModel<DomainConnectionsViewModel>()

    private var type: InputType = InputType.DOMAIN

    // when set, the blocklist drill-down shows only this app's blocked domains
    // (per-app screen); INVALID_UID keeps the expandable all-apps list
    private var scopedUid: Int = Constants.INVALID_UID

    // active chip filter in the blocklist detail view; rows match any of the
    // selected lists (OR). starts as the full set once chips are built
    private var selectedBlocklistTags by mutableStateOf<Set<String>>(emptySet())

    private fun Context.isDarkThemeOn(): Boolean {
            return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
        }

    companion object {
        const val INTENT_EXTRA_TYPE = "TYPE"
        const val INTENT_EXTRA_FLAG = "FLAG"
        const val INTENT_EXTRA_DOMAIN = "DOMAIN"
        const val INTENT_EXTRA_ASN = "ASN"
        const val INTENT_EXTRA_IP = "IP"
        const val INTENT_EXTRA_BLOCKLIST = "BLOCKLIST"
        const val INTENT_EXTRA_IS_BLOCKED = "IS_BLOCKED"
        const val INTENT_EXTRA_TIME_CATEGORY = "TIME_CATEGORY"
        const val INTENT_EXTRA_UID = "UID"
    }

    enum class InputType(val type: Int) {
        DOMAIN(0), FLAG(1), ASN(2), IP(3), BLOCKLIST(4);
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

        val t = intent.getIntExtra(INTENT_EXTRA_TYPE, 0)
        type = InputType.entries.toTypedArray()[t]
        when (type) {
            InputType.DOMAIN -> {
                val domain = intent.getStringExtra(INTENT_EXTRA_DOMAIN) ?: ""
                val isBlocked = intent.getBooleanExtra(INTENT_EXTRA_IS_BLOCKED, false)
                viewModel.setDomain(domain, isBlocked)
                screenTitle = domain
            }
            InputType.FLAG -> {
                val flag = intent.getStringExtra(INTENT_EXTRA_FLAG) ?: ""
                viewModel.setFlag(flag)
                screenTitle = getString(R.string.two_argument_space, flag, getCountryNameFromFlag(flag))
            }
            InputType.ASN -> {
                val asn = intent.getStringExtra(INTENT_EXTRA_ASN) ?: ""
                val isBlocked = intent.getBooleanExtra(INTENT_EXTRA_IS_BLOCKED, false)
                viewModel.setAsn(asn, isBlocked)
                screenTitle = asn
            }
            InputType.IP -> {
                val ip = intent.getStringExtra(INTENT_EXTRA_IP) ?: ""
                val isBlocked = intent.getBooleanExtra(INTENT_EXTRA_IS_BLOCKED, false)
                viewModel.setIp(ip, isBlocked)
                screenTitle = ip
            }
            InputType.BLOCKLIST -> {
                val blocklist = intent.getStringExtra(INTENT_EXTRA_BLOCKLIST) ?: ""
                viewModel.setBlocklist(blocklist)
                screenTitle = blocklist
                val uid = intent.getIntExtra(INTENT_EXTRA_UID, Constants.INVALID_UID)
                if (uid != Constants.INVALID_UID) {
                    scopedUid = uid
                    viewModel.setUid(uid)
                }
            }
        }
        val tc = intent.getIntExtra(INTENT_EXTRA_TIME_CATEGORY, 0)
        val timeCategory =
            DomainConnectionsViewModel.TimeCategory.fromValue(tc)
                ?: DomainConnectionsViewModel.TimeCategory.ONE_HOUR
        setSubTitle(timeCategory)
        viewModel.timeCategoryChanged(timeCategory)
        setRecyclerView(timeCategory)
        setContent { RethinkTheme {
            PagedStatisticsScreen(screenTitle, subtitle, empty, presenter, selectableTitle = true, chips = {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    tags.forEachIndexed { index, tag ->
                        OriginalChip("item_chip_filter:(anonymous)", selected = tag in selectedBlocklistTags, onClick = { toggleTag(tag) }, label = { Text(tag) }, modifier = Modifier.testTag("blocklist_tag_$index"))
                    }
                }
            }, extraCount = groups?.itemCount ?: 0, extraRow = { groups?.GroupAt(it) })
        } }

    }

    private fun setSubTitle(timeCategory: DomainConnectionsViewModel.TimeCategory) {
        subtitle =
            when (timeCategory) {
                DomainConnectionsViewModel.TimeCategory.ONE_HOUR -> {
                    getString(
                        R.string.three_argument,
                        getString(R.string.lbl_last),
                        getString(R.string.numeric_one),
                        getString(R.string.lbl_hour)
                    )
                }

                DomainConnectionsViewModel.TimeCategory.TWENTY_FOUR_HOUR -> {
                    getString(
                        R.string.three_argument,
                        getString(R.string.lbl_last),
                        getString(R.string.numeric_twenty_four),
                        getString(R.string.lbl_hour)
                    )
                }

                DomainConnectionsViewModel.TimeCategory.SEVEN_DAYS -> {
                    getString(
                        R.string.three_argument,
                        getString(R.string.lbl_last),
                        getString(R.string.numeric_seven),
                        getString(R.string.lbl_day)
                    )
                }
            }
    }

    private fun setRecyclerView(timeCategory: DomainConnectionsViewModel.TimeCategory) {
        if (type == InputType.BLOCKLIST) {
            // blocklist drill-down uses a dedicated expandable list (apps ->
            // domains) instead of the shared paged adapter
            setBlocklistRecyclerView()
            return
        }
        val recyclerAdapter = DomainConnectionsAdapter(this, type)

        val liveData = when (type) {
            InputType.DOMAIN -> {
                viewModel.domainConnectionList
            }
            InputType.FLAG -> {
                viewModel.flagConnectionList
            }
            InputType.ASN -> {
                viewModel.asnConnectionList
            }
            InputType.IP -> {
                viewModel.ipConnectionList
            }
            InputType.BLOCKLIST -> {
                // handled by setBlocklistRecyclerView(); unreachable here
                viewModel.domainConnectionList
            }
        }

        liveData.observe(this) { recyclerAdapter.submitData(this.lifecycle, it) }

        recyclerAdapter.addLoadStateListener {
            empty = it.append.endOfPaginationReached && recyclerAdapter.itemCount < 1
            if (empty) liveData.removeObservers(this)
        }
        presenter = recyclerAdapter
    }

    private fun setBlocklistRecyclerView() {
        if (scopedUid != Constants.INVALID_UID) { setScopedBlocklistDomainsRecyclerView(); return }
        val adapter = BlocklistAppsAdapter(this) { uid -> viewModel.getBlocklistDomainsForApp(uid, selectedBlocklistTags) }
        groups = adapter
        onTagsChanged = { refetchBlocklistApps(adapter) }
        lifecycleScope.launch {
            tags = viewModel.getBlocklistTags()
            selectedBlocklistTags = tags.toSet()
            refetchBlocklistApps(adapter)
        }
    }
    private fun toggleTag(tag: String) {
        selectedBlocklistTags = selectStatisticsTags(tags, selectedBlocklistTags, tag)
        // A legacy last-tag uncheck rechecks it and emits one nested nonempty/refetch callback.
        onTagsChanged()
    }
    private fun setScopedBlocklistDomainsRecyclerView() {
        val adapter = SummaryStatisticsAdapter(this, persistentState, appConfig,
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS)
        adapter.setUid(scopedUid)
        adapter.setTimeCategory(SummaryStatisticsViewModel.TimeCategory.fromValue(
            intent.getIntExtra(INTENT_EXTRA_TIME_CATEGORY, SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS.value)
        ) ?: SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS)
        presenter = adapter
        onTagsChanged = { refetchScopedBlocklistDomains(adapter) }
        lifecycleScope.launch {
            tags = viewModel.getBlocklistTags()
            selectedBlocklistTags = tags.toSet()
            refetchScopedBlocklistDomains(adapter)
        }
    }
    private fun refetchScopedBlocklistDomains(adapter: SummaryStatisticsAdapter) {
        lifecycleScope.launch {
            val domains = viewModel.getBlocklistDomainsForApp(scopedUid, selectedBlocklistTags)
            adapter.submitData(lifecycle, PagingData.from(domains))
            empty = domains.isEmpty()
        }
    }
    private fun refetchBlocklistApps(adapter: BlocklistAppsAdapter) {
        lifecycleScope.launch {
            val apps = viewModel.getBlocklistApps(selectedBlocklistTags)
            adapter.submitApps(apps)
            empty = apps.isEmpty()
        }
    }
}
