/*
 * Copyright 2026 RethinkDNS and its authors
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
import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.dns.*
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.data.BlockFreeDnsItem
import com.celzero.bravedns.data.BlockFreeDnsType
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.BlockFreeDnsViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class BlockFreeDnsActivity : BaseActivity() {
    private lateinit var b: BlockFreeDnsPresentation

    private val persistentState by inject<PersistentState>()
    private val viewModel: BlockFreeDnsViewModel by viewModel()

    private lateinit var adapter: BlockFreeDnsListState

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)


        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        b = BlockFreeDnsPresentation(this)
        setupRecycler()
        setupFilterChips()
        observeViewModel()
        updateSelectedCard()
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { BlockFreeDnsContent(b, adapter) } }
        })
    }


    private fun setupRecycler() {
        adapter = BlockFreeDnsListState(
            selectedKey = persistentState.blockFreeDns,
            onItemSelected = { item -> onDnsSelected(item) }
        )
    }

    private fun setupFilterChips() {
        b.bfdFilterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                checkedIds.contains(R.id.bfd_chip_rethink) -> BlockFreeDnsType.RETHINK
                checkedIds.contains(R.id.bfd_chip_doh) -> BlockFreeDnsType.DOH
                checkedIds.contains(R.id.bfd_chip_dot) -> BlockFreeDnsType.DOT
                checkedIds.contains(R.id.bfd_chip_dnscrypt) -> BlockFreeDnsType.DNSCRYPT
                checkedIds.contains(R.id.bfd_chip_odoh) -> BlockFreeDnsType.ODOH
                checkedIds.contains(R.id.bfd_chip_dns_proxy) -> BlockFreeDnsType.DNS_PROXY
                else -> null // "All"
            }
            viewModel.setFilter(filter)
        }
    }

    private fun observeViewModel() {
        viewModel.filteredItems.observe(this) { items ->
            adapter.submitList(items)
            b.bfdEmptyTxt.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun onDnsSelected(item: BlockFreeDnsItem) {
        Logger.i(LOG_TAG_DNS, "Block-free DNS selected: ${item.key}")
        persistentState.blockFreeDns = item.key
        adapter.updateSelectedKey(item.key)
        updateSelectedCard()
    }

    private fun updateSelectedCard() {
        val key = persistentState.blockFreeDns
        if (key.isEmpty()) {
            b.bfdSelectedName.text = "NA"
            return
        }
        // get display name from the current list or fallback to URL portion
        val items = viewModel.allItems.value
        val found = items?.find { it.key == key }
        val displayName = found?.name ?: BlockFreeDnsType.urlFromKey(key)
        b.bfdSelectedName.text = displayName
        b.bfdSelectedCard.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("block_free_filter_id", b.bfdFilterChipGroup.checkedId)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Framework ChipGroup restoration used to dispatch this same installed callback.
        if (savedInstanceState.containsKey("block_free_filter_id")) {
            b.bfdFilterChipGroup.restore(savedInstanceState.getInt("block_free_filter_id"))
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case DNS list changed while away
        updateSelectedCard()
    }
}

