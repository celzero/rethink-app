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
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_TAG_UI
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgNwStatsAdapter
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.databinding.FragmentWgNwStatsBinding
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_WIREGUARD
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.WgNwActivityViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel

class WgNwStatsFragment : Fragment(R.layout.fragment_wg_nw_stats) {
    private val b by viewBinding(FragmentWgNwStatsBinding::bind)
    private val viewModel: WgNwActivityViewModel by viewModel()
    private lateinit var adapter: WgNwStatsAdapter
    private var wgId: String = ""

    companion object {
        const val TAG = "WgNwStatsFragment"
        const val WG_ID = "wireguardId"
        fun newInstance(param: String): WgNwStatsFragment {
            val args = Bundle()
            args.putString(WG_ID, param)
            val fragment = WgNwStatsFragment()
            Logger.d(LOG_TAG_UI, "$TAG: newInstance called with param for $WG_ID: $param")
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.v(LOG_TAG_UI, "$TAG: onViewCreated")
        if (arguments != null) {
            wgId = requireArguments().getString(WG_ID, "")
            // remove the search id from the wg id
            if (wgId.contains(RULES_SEARCH_ID_WIREGUARD)) {
                wgId = wgId.substringAfter(RULES_SEARCH_ID_WIREGUARD)
            }
        }

        if (wgId.isEmpty() || !wgId.startsWith(ProxyManager.ID_WG_BASE)) {
            Logger.i(LOG_TAG_UI, "$TAG: invalid wg id: $wgId")
            showErrorDialog()
            return
        }

        initView()
    }

    private fun initView() {
        setTabbedViewTxt()
        highlightToggleBtn()
        setRecyclerView()
        setClickListeners()
        handleTotalUsagesUi()
    }

    private fun setClickListeners() {
        b.toggleGroup.addOnButtonCheckedListener(listViewToggleListener)
    }

    private fun setTabbedViewTxt() {
        b.tbRecentToggleBtn.text = getString(R.string.ci_desc, "1", getString(R.string.lbl_hour))
        b.tbDailyToggleBtn.text = getString(R.string.ci_desc, "24", getString(R.string.lbl_hour))
        b.tbWeeklyToggleBtn.text = getString(R.string.ci_desc, "7", getString(R.string.lbl_day))
    }

    private fun setRecyclerView() {
        adapter = WgNwStatsAdapter(requireContext())
        b.statsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.statsRecyclerView.adapter = adapter

        viewModel.setWgId(wgId)
        viewModel.wgAppNwActivity.observe(viewLifecycleOwner) {
            adapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        adapter.addLoadStateListener { loadState ->
            val isEmpty = adapter.itemCount < 1
            if (loadState.append.endOfPaginationReached && isEmpty) {
                b.tbStatsCard.visibility = View.GONE
                //b.toggleGroup.visibility = View.GONE
                b.tbLogsDisabledTv.visibility = View.VISIBLE
                viewModel.wgAppNwActivity.removeObservers(this)
            } else {
                b.tbLogsDisabledTv.visibility = View.GONE
                b.tbStatsCard.visibility = View.VISIBLE
                //b.toggleGroup.visibility = View.VISIBLE
            }
        }
    }

    private fun highlightToggleBtn() {
        val timeCategory = "0" // default is 1 hours, "0" tag is 1 hours
        val btn = b.toggleGroup.findViewWithTag<MaterialButton>(timeCategory)
        btn.isChecked = true
        selectToggleBtnUi(btn)
    }

    private val listViewToggleListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            val mb: MaterialButton = b.toggleGroup.findViewById(checkedId)
            if (isChecked) {
                selectToggleBtnUi(mb)
                val tcValue = (mb.tag as String).toIntOrNull() ?: 0
                val timeCategory =
                    WgNwActivityViewModel.TimeCategory.fromValue(tcValue)
                        ?: WgNwActivityViewModel.TimeCategory.ONE_HOUR
                Logger.d(LOG_TAG_UI, "$TAG: time category changed to $timeCategory")
                viewModel.timeCategoryChanged(timeCategory)
                handleTotalUsagesUi()
                return@OnButtonCheckedListener
            }

            unselectToggleBtnUi(mb)
        }

    private fun handleTotalUsagesUi() {
        io {
            val totalUsage = viewModel.totalUsage(wgId)
            uiCtx { setTotalUsagesUi(totalUsage) }
        }
    }

    private fun setTotalUsagesUi(dataUsage: DataUsageSummary) {
        val unmeteredUsage = (dataUsage.totalDownload + dataUsage.totalUpload)
        val totalUsage = unmeteredUsage + dataUsage.meteredDataUsage

        b.fssUnmeteredDataUsage.text =
            getString(
                R.string.two_argument_colon,
                getString(R.string.ada_app_unmetered),
                Utilities.humanReadableByteCount(unmeteredUsage, true)
            )
        b.fssMeteredDataUsage.text =
            getString(
                R.string.two_argument_colon,
                getString(R.string.ada_app_metered),
                Utilities.humanReadableByteCount(dataUsage.meteredDataUsage, true)
            )
        b.fssTotalDataUsage.text =
            getString(
                R.string.two_argument_colon,
                getString(R.string.lbl_overall),
                Utilities.humanReadableByteCount(totalUsage, true)
            )
        b.fssMeteredDataUsage.setCompoundDrawablesWithIntrinsicBounds(
            R.drawable.dot_accent,
            0,
            0,
            0
        )

        // set the alpha for the drawable
        val alphaValue = 128 // half-transparent
        val drawable = b.fssMeteredDataUsage.compoundDrawables[0] // drawableLeft
        drawable?.mutate()?.alpha = alphaValue

        // set the progress bar
        val ump = calculatePercentage(unmeteredUsage, totalUsage) // unmetered percentage
        val mp = calculatePercentage(dataUsage.meteredDataUsage, totalUsage) // metered percentage
        val secondaryVal = ump + mp

        b.fssProgressBar.max = secondaryVal
        b.fssProgressBar.progress = ump
        b.fssProgressBar.secondaryProgress = secondaryVal
    }

    private fun calculatePercentage(value: Long, maxValue: Long): Int {
        if (maxValue == 0L) return 0

        return (value * 100 / maxValue).toInt()
    }

    private fun selectToggleBtnUi(mb: MaterialButton) {
        mb.backgroundTintList =
            ColorStateList.valueOf(
                UIUtils.fetchToggleBtnColors(requireContext(), R.color.accentGood)
            )
        mb.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(mb: MaterialButton) {
        mb.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor))
        mb.backgroundTintList =
            ColorStateList.valueOf(
                UIUtils.fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg)
            )
    }

    private fun showErrorDialog() {
        // Show error dialog
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.lbl_wireguard))
            .setMessage(getString(R.string.config_invalid_desc))
            .setPositiveButton(R.string.fapps_info_dialog_positive_btn) { _, _ ->
                requireActivity().onBackPressedDispatcher
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    private fun io(f: suspend () -> Unit) {
        this.lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
