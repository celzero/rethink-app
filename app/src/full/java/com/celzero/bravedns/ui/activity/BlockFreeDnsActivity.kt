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

import Logger
import Logger.LOG_TAG_DNS
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.BlockFreeDnsAdapter
import com.celzero.bravedns.data.BlockFreeDnsItem
import com.celzero.bravedns.data.BlockFreeDnsType
import com.celzero.bravedns.databinding.ActivityBlockFreeDnsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.BlockFreeDnsViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class BlockFreeDnsActivity : BaseActivity(R.layout.activity_block_free_dns) {
    private val b by viewBinding(ActivityBlockFreeDnsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val viewModel: BlockFreeDnsViewModel by viewModel()

    private lateinit var adapter: BlockFreeDnsAdapter

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
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        setupRecycler()
        setupFilterChips()
        observeViewModel()
        updateSelectedCard()
    }


    private fun setupRecycler() {
        adapter = BlockFreeDnsAdapter(
            context = this,
            selectedKey = persistentState.blockFreeDns,
            onItemSelected = { item -> onDnsSelected(item) }
        )
        b.bfdRecycler.layoutManager = LinearLayoutManager(this)
        b.bfdRecycler.adapter = adapter
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

    override fun onResume() {
        super.onResume()
        // Refresh in case DNS list changed while away
        updateSelectedCard()
    }
}

