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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppWiseIpsAdapter
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.databinding.ActivityAppWiseIpLogsBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AppWiseIpLogsActivity :
    AppCompatActivity(R.layout.activity_app_wise_ip_logs), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityAppWiseIpLogsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val networkLogsViewModel: AppConnectionsViewModel by viewModel()
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()
    private var uid: Int = INVALID_UID
    private var layoutManager: RecyclerView.LayoutManager? = null
    private lateinit var appInfo: AppInfo

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        uid = intent.getIntExtra(AppInfoActivity.UID_INTENT_NAME, INVALID_UID)
        if (uid == INVALID_UID) {
            finish()
        }
        init()
        setAdapter()
        observeNetworkLogSize()
        setClickListener()
    }

    private fun init() {
        setTabbedViewTxt()
        highlightToggleBtn()
        io {
            val appInfo = FirewallManager.getAppInfoByUid(uid)
            // case: app is uninstalled but still available in RethinkDNS database
            if (appInfo == null || uid == INVALID_UID) {
                uiCtx { finish() }
                return@io
            }

            val packages = FirewallManager.getPackageNamesByUid(appInfo.uid)
            uiCtx {
                this.appInfo = appInfo

                b.awlAppDetailName.text = appName(packages.count())
                displayIcon(
                    Utilities.getIcon(this, appInfo.packageName, appInfo.appName),
                    b.awlAppDetailIcon
                )
            }
        }
    }

    private fun setTabbedViewTxt() {
        b.tbRecentToggleBtn.text = getString(R.string.ci_desc, "1", getString(R.string.lbl_hour))
        b.tbDailyToggleBtn.text = getString(R.string.ci_desc, "24", getString(R.string.lbl_hour))
        b.tbWeeklyToggleBtn.text = getString(R.string.ci_desc, "7", getString(R.string.lbl_day))
    }

    private val listViewToggleListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            val mb: MaterialButton = b.toggleGroup.findViewById(checkedId)
            if (isChecked) {
                selectToggleBtnUi(mb)
                val tcValue = (mb.tag as String).toIntOrNull() ?: 0
                val timeCategory =
                    AppConnectionsViewModel.TimeCategory.fromValue(tcValue)
                        ?: AppConnectionsViewModel.TimeCategory.ONE_HOUR
                networkLogsViewModel.timeCategoryChanged(timeCategory, isDomain = false)
                return@OnButtonCheckedListener
            }

            unselectToggleBtnUi(mb)
        }

    private fun selectToggleBtnUi(mb: MaterialButton) {
        mb.backgroundTintList =
            ColorStateList.valueOf(UIUtils.fetchToggleBtnColors(this, R.color.accentGood))
        mb.setTextColor(UIUtils.fetchColor(this, R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(mb: MaterialButton) {
        mb.setTextColor(UIUtils.fetchColor(this, R.attr.primaryTextColor))
        mb.backgroundTintList =
            ColorStateList.valueOf(UIUtils.fetchToggleBtnColors(this, R.color.defaultToggleBtnBg))
    }

    private fun highlightToggleBtn() {
        val timeCategory = "0" // default is 1 hours, "0" tag is 1 hours
        val btn = b.toggleGroup.findViewWithTag<MaterialButton>(timeCategory)
        btn.isChecked = true
        selectToggleBtnUi(btn)
    }

    private fun setClickListener() {
        b.toggleGroup.addOnButtonCheckedListener(listViewToggleListener)
        b.awlDelete.setOnClickListener { showDeleteConnectionsDialog() }
        b.awlSearch.setOnQueryTextListener(this)
    }

    private fun appName(packageCount: Int): String {
        return if (packageCount >= 2) {
            getString(
                R.string.ctbs_app_other_apps,
                appInfo.appName,
                packageCount.minus(1).toString()
            )
        } else {
            appInfo.appName
        }
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        Glide.with(this).load(drawable).error(Utilities.getDefaultIcon(this)).into(mIconImageView)
    }

    private fun setAdapter() {
        networkLogsViewModel.setUid(uid)
        b.awlRecyclerConnection.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this)
        b.awlRecyclerConnection.layoutManager = layoutManager
        val recyclerAdapter = AppWiseIpsAdapter(this, this, uid)
        networkLogsViewModel.appIpLogs.observe(this) {
            recyclerAdapter.submitData(this.lifecycle, it)
        }
        b.awlRecyclerConnection.adapter = recyclerAdapter
    }

    private fun observeNetworkLogSize() {
        networkLogsViewModel.getConnectionsCount(uid).observe(this) {
            if (it == null) return@observe

            if (it <= 0) {
                showNoRulesUi()
                hideRulesUi()
                return@observe
            }

            hideNoRulesUi()
            showRulesUi()
        }
    }

    private fun showNoRulesUi() {
        b.awlNoRulesRl.visibility = android.view.View.VISIBLE
    }

    private fun hideRulesUi() {
        b.awlCardViewTop.visibility = android.view.View.GONE
        b.awlAppDetailRl.visibility = android.view.View.GONE
        b.awlRecyclerConnection.visibility = android.view.View.GONE
    }

    private fun hideNoRulesUi() {
        b.awlNoRulesRl.visibility = android.view.View.GONE
    }

    private fun showRulesUi() {
        b.awlCardViewTop.visibility = android.view.View.VISIBLE
        b.awlAppDetailRl.visibility = android.view.View.VISIBLE
        b.awlRecyclerConnection.visibility = android.view.View.VISIBLE
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        networkLogsViewModel.setFilter(query, AppConnectionsViewModel.FilterType.IP)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        Utilities.delay(500, lifecycleScope) {
            if (!this.isFinishing) {
                networkLogsViewModel.setFilter(query, AppConnectionsViewModel.FilterType.IP)
            }
        }
        return true
    }

    private fun showDeleteConnectionsDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.ada_delete_logs_dialog_title)
        builder.setMessage(R.string.ada_delete_logs_dialog_desc)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.lbl_proceed)) { _, _ -> deleteAppLogs() }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
        builder.create().show()
    }

    private fun deleteAppLogs() {
        io { connectionTrackerRepository.clearLogsByUid(uid) }
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
