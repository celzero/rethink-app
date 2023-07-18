/*
 * Copyright 2023 RethinkDNS and its authors
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

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.WgAppsIncludeDialogBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.viewmodel.WgIncludeAppsViewModel

class WgIncludeAppsDialog(
    private var activity: Activity,
    internal var adapter: RecyclerView.Adapter<*>,
    var viewModel: WgIncludeAppsViewModel,
    themeID: Int,
    private val configId: Int
) : Dialog(activity, themeID), View.OnClickListener, SearchView.OnQueryTextListener {

    private lateinit var b: WgAppsIncludeDialogBinding

    private var layoutManager: RecyclerView.LayoutManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = WgAppsIncludeDialogBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)

        initializeValues()
        initializeClickListeners()
    }

    private fun initializeValues() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        layoutManager = LinearLayoutManager(activity)

        b.wgIncludeAppRecyclerViewDialog.layoutManager = layoutManager
        b.wgIncludeAppRecyclerViewDialog.adapter = adapter

        FirewallManager.getApplistObserver().observe(activity as LifecycleOwner) {
            b.wgIncludeAppSelectCountText.text =
                context.getString(R.string.firewall_card_status_active, it.toString())
        }
    }

    private fun initializeClickListeners() {
        b.wgIncludeAppDialogOkButton.setOnClickListener(this)

        b.wgIncludeAppDialogSearchView.setOnQueryTextListener(this)
        b.wgIncludeAppDialogSearchView.setOnSearchClickListener(this)

        b.wgIncludeAppDialogSearchView.setOnCloseListener {
            toggleCategoryChipsUi()
            false
        }

        b.wgIncludeAppSelectAllCheckbox.setOnClickListener {
            showDialog(b.wgIncludeAppSelectAllCheckbox.isChecked)
        }

        b.wgIncludeAppSelectAllCheckbox.setOnCheckedChangeListener(null)

        b.wgIncludeAppDialogSearchFilter.setOnClickListener(this)
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.wg_include_app_dialog_ok_button -> {
                clearSearch()
                dismiss()
            }
            R.id.wg_include_app_dialog_search_filter -> {
                toggleCategoryChipsUi()
            }
            R.id.wg_include_app_dialog_search_view -> {
                toggleCategoryChipsUi()
            }
            else -> {
                clearSearch()
                dismiss()
            }
        }
    }

    private fun clearSearch() {
        viewModel.setFilter("")
    }

    private fun toggleCategoryChipsUi() {
        if (!b.wgIncludeAppDialogChipGroup.isVisible) {
            b.wgIncludeAppDialogChipGroup.visibility = View.VISIBLE
        } else {
            b.wgIncludeAppDialogChipGroup.visibility = View.GONE
        }
    }

    private fun showDialog(toAdd: Boolean) {
        val builder = AlertDialog.Builder(context)
        if (toAdd) {
            builder.setTitle(context.getString(R.string.include_all_app_wg_dialog_title))
            builder.setMessage(context.getString(R.string.include_all_app_wg_dialog_desc))
        } else {
            builder.setTitle(context.getString(R.string.exclude_all_app_wg_dialog_title))
            builder.setMessage(context.getString(R.string.exclude_all_app_wg_dialog_desc))
        }
        builder.setCancelable(true)
        builder.setPositiveButton(
            if (toAdd) context.getString(R.string.lbl_include)
            else context.getString(R.string.exclude)
        ) { _, _ ->
            // add all if the list is empty or remove all if the list is full
            if (toAdd) {
                WireguardManager.addAllAppsToConfig(configId)
            } else {
                WireguardManager.removeAllAppsFromConfig(configId)
            }
            Toast.makeText(
                    context,
                    "All apps are ${if (toAdd) "included" else "excluded"}",
                    Toast.LENGTH_SHORT
                )
                .show()
        }

        builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ ->
            b.wgIncludeAppSelectAllCheckbox.isChecked = !toAdd
        }

        builder.create().show()
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }
}
