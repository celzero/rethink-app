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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.BlocklistAppsAdapter
import com.celzero.bravedns.adapter.DomainConnectionsAdapter
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import kotlinx.coroutines.launch
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityDomainConnectionsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.DomainConnectionsViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import com.google.android.material.chip.Chip
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DomainConnectionsActivity : BaseActivity(R.layout.activity_domain_connections){
    private val b by viewBinding(ActivityDomainConnectionsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val viewModel by viewModel<DomainConnectionsViewModel>()

    private var type: InputType = InputType.DOMAIN

    // when set, the blocklist drill-down shows only this app's blocked domains
    // (per-app screen); INVALID_UID keeps the expandable all-apps list
    private var scopedUid: Int = Constants.INVALID_UID

    // active chip filter in the blocklist detail view; rows match any of the
    // selected lists (OR). starts as the full set once chips are built
    private var selectedBlocklistTags: Set<String> = emptySet()

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
                b.dcTitle.text = domain
            }
            InputType.FLAG -> {
                val flag = intent.getStringExtra(INTENT_EXTRA_FLAG) ?: ""
                viewModel.setFlag(flag)
                b.dcTitle.text = getString(R.string.two_argument_space, flag, getCountryNameFromFlag(flag))
            }
            InputType.ASN -> {
                val asn = intent.getStringExtra(INTENT_EXTRA_ASN) ?: ""
                val isBlocked = intent.getBooleanExtra(INTENT_EXTRA_IS_BLOCKED, false)
                viewModel.setAsn(asn, isBlocked)
                b.dcTitle.text = asn
            }
            InputType.IP -> {
                val ip = intent.getStringExtra(INTENT_EXTRA_IP) ?: ""
                val isBlocked = intent.getBooleanExtra(INTENT_EXTRA_IS_BLOCKED, false)
                viewModel.setIp(ip, isBlocked)
                b.dcTitle.text = ip
            }
            InputType.BLOCKLIST -> {
                val blocklist = intent.getStringExtra(INTENT_EXTRA_BLOCKLIST) ?: ""
                viewModel.setBlocklist(blocklist)
                b.dcTitle.text = blocklist
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
    }

    private fun setSubTitle(timeCategory: DomainConnectionsViewModel.TimeCategory) {
        b.dcSubtitle.text =
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
        b.dcRecycler.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(this)
        b.dcRecycler.layoutManager = layoutManager

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

        // remove the view if there is no data
        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.dcRecycler.visibility = View.GONE
                    b.dcNoDataRl.visibility = View.VISIBLE
                    liveData.removeObservers(this)
                } else {
                    b.dcRecycler.visibility = View.VISIBLE
                    b.dcNoDataRl.visibility = View.GONE
                }
            } else {
                b.dcRecycler.visibility = View.VISIBLE
                b.dcNoDataRl.visibility = View.GONE
            }
        }
        b.dcRecycler.adapter = recyclerAdapter
    }

    private fun setBlocklistRecyclerView() {
        b.dcRecycler.setHasFixedSize(false)
        b.dcRecycler.layoutManager = LinearLayoutManager(this)
        b.dcRecycler.itemAnimator = null

        if (scopedUid != Constants.INVALID_UID) {
            // per-app scope: the app is already known, so skip the expandable
            // apps list and show this app's blocked domains directly
            setScopedBlocklistDomainsRecyclerView()
            return
        }

        val adapter = BlocklistAppsAdapter(this) { uid ->
            viewModel.getBlocklistDomainsForApp(uid, selectedBlocklistTags)
        }
        b.dcRecycler.adapter = adapter

        val chipTagById = HashMap<Int, String>()
        lifecycleScope.launch {
            // one checkable chip per list tag attributed to this blocklist, so
            // the "n lists" hint on the stats screen resolves into actual
            // names; all start checked and unchecking narrows the list below
            val tags = viewModel.getBlocklistTags()
            if (tags.isNotEmpty()) {
                b.dcBlocklistChips.removeAllViews()
                tags.forEach { tag ->
                    val chip = layoutInflater.inflate(
                        R.layout.item_chip_filter,
                        b.dcBlocklistChips,
                        false
                    ) as Chip
                    chip.text = tag
                    chip.isChecked = true
                    chip.isCheckedIconVisible = false
                    chip.id = View.generateViewId()
                    chipTagById[chip.id] = tag
                    b.dcBlocklistChips.addView(chip)
                }
                b.dcBlocklistChips.visibility = View.VISIBLE
            }
            selectedBlocklistTags = tags.toSet()

            var lastCheckedId = b.dcBlocklistChips.checkedChipIds.lastOrNull()
            // attach only after the initial programmatic checks so the
            // listener reflects user-driven changes from here on; at least
            // one chip always stays selected
            b.dcBlocklistChips.setOnCheckedStateChangeListener { group, checkedIds ->
                if (checkedIds.isEmpty()) {
                    lastCheckedId?.let { group.check(it) }
                    return@setOnCheckedStateChangeListener
                }
                lastCheckedId = checkedIds.last()
                selectedBlocklistTags = checkedIds.mapNotNull { chipTagById[it] }.toSet()
                refetchBlocklistApps(adapter)
            }

            refetchBlocklistApps(adapter)
        }
    }

    /**
     * Per-app variant: a flat list of the domains this blocklist blocked for
     * the scoped uid; the tag chips narrow the same way as the global view.
     */
    private fun setScopedBlocklistDomainsRecyclerView() {
        val adapter = SummaryStatisticsAdapter(
            this,
            persistentState,
            appConfig,
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS
        )
        // scoping the adapter makes its rows open the block/trust domain
        // rules bottom sheet (down arrow) for this app
        adapter.setUid(scopedUid)
        adapter.setTimeCategory(
            SummaryStatisticsViewModel.TimeCategory.fromValue(
                intent.getIntExtra(
                    INTENT_EXTRA_TIME_CATEGORY,
                    SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS.value
                )
            ) ?: SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS
        )
        b.dcRecycler.adapter = adapter

        val chipTagById = HashMap<Int, String>()
        lifecycleScope.launch {
            val tags = viewModel.getBlocklistTags()
            if (tags.isNotEmpty()) {
                b.dcBlocklistChips.removeAllViews()
                tags.forEach { tag ->
                    val chip = layoutInflater.inflate(
                        R.layout.item_chip_filter,
                        b.dcBlocklistChips,
                        false
                    ) as Chip
                    chip.text = tag
                    chip.isChecked = true
                    chip.isCheckedIconVisible = false
                    chip.id = View.generateViewId()
                    chipTagById[chip.id] = tag
                    b.dcBlocklistChips.addView(chip)
                }
                b.dcBlocklistChips.visibility = View.VISIBLE
            }
            selectedBlocklistTags = tags.toSet()

            var lastCheckedId = b.dcBlocklistChips.checkedChipIds.lastOrNull()
            b.dcBlocklistChips.setOnCheckedStateChangeListener { group, checkedIds ->
                if (checkedIds.isEmpty()) {
                    lastCheckedId?.let { group.check(it) }
                    return@setOnCheckedStateChangeListener
                }
                lastCheckedId = checkedIds.last()
                selectedBlocklistTags = checkedIds.mapNotNull { chipTagById[it] }.toSet()
                refetchScopedBlocklistDomains(adapter)
            }

            refetchScopedBlocklistDomains(adapter)
        }
    }

    private fun refetchScopedBlocklistDomains(adapter: SummaryStatisticsAdapter) {
        lifecycleScope.launch {
            val domains = viewModel.getBlocklistDomainsForApp(scopedUid, selectedBlocklistTags)
            adapter.submitData(lifecycle, PagingData.from(domains))
            if (domains.isEmpty()) {
                b.dcRecycler.visibility = View.GONE
                b.dcNoDataRl.visibility = View.VISIBLE
            } else {
                b.dcRecycler.visibility = View.VISIBLE
                b.dcNoDataRl.visibility = View.GONE
            }
        }
    }

    private fun refetchBlocklistApps(adapter: BlocklistAppsAdapter) {
        lifecycleScope.launch {
            val apps = viewModel.getBlocklistApps(selectedBlocklistTags)
            adapter.submitApps(apps)
            if (apps.isEmpty()) {
                b.dcRecycler.visibility = View.GONE
                b.dcNoDataRl.visibility = View.VISIBLE
            } else {
                b.dcRecycler.visibility = View.VISIBLE
                b.dcNoDataRl.visibility = View.GONE
            }
        }
    }
}
