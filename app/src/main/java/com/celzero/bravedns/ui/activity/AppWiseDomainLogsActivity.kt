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
import androidx.activity.compose.setContent
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.appdetails.*
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppWiseDomainsAdapter
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AppWiseDomainLogsActivity :
    BaseActivity(), SearchView.OnQueryTextListener {
    private val b = AppLogsState()

    private val persistentState by inject<PersistentState>()
    private val networkLogsViewModel: AppConnectionsViewModel by viewModel()
    private var uid: Int = INVALID_UID
    private lateinit var appInfo: AppInfo
    private var isActiveConns = false

    private var isBlocked: Boolean? = null

    companion object {
        private const val QUERY_TEXT_DELAY: Long = 1000
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        setContent { RethinkTheme { AppLogsContent(b) } }

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }
        uid = intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        isActiveConns = intent.getBooleanExtra(AppInfoActivity.INTENT_ACTIVE_CONNS, false)
        if (intent.hasExtra(AppInfoActivity.INTENT_IS_BLOCKED)) {
            isBlocked = intent.getBooleanExtra(AppInfoActivity.INTENT_IS_BLOCKED, false)
        }

        if (uid == INVALID_UID) {
            finish()
        }
        // must be set before the adapters observe; the paged queries branch on it
        networkLogsViewModel.setBlockedFilter(isBlocked)
        val isRethink = android.os.Process.myUid() == uid
        init()
        if (isActiveConns) {
            setActiveConnsAdapter(isRethink)
        } else {
            if (isRethink) {
                setRethinkAdapter()
            } else {
                setAdapter()
            }
        }
        setClickListener()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("compose-log-time", b.selectedTime)
        outState.putString("compose-log-query", b.awlSearch.query)
    }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        selectTime(savedInstanceState.getInt("compose-log-time", 2))
        b.awlSearch.setQuery(savedInstanceState.getString("compose-log-query").orEmpty(), false)
    }

    override fun onResume() {
        super.onResume()
        // fix for #1939, OEM-specific bug, especially on heavily customized Android
        // some ROMs kill or freeze the keyboard/IME process to save memory or battery,
        // causing SearchView to stop receiving input events
        // this is a workaround to restart the IME process
        b.awlSearch.setQuery("", false)
        b.awlSearch.clearFocus()

        val imm = this.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(window.decorView)
        b.awlSearch.clearFocus()
    }

    private fun setTabbedViewTxt() {
        b.tbRecentToggleBtn.text = getString(R.string.ci_desc, "1", getString(R.string.lbl_hour))
        b.tbDailyToggleBtn.text = getString(R.string.ci_desc, "24", getString(R.string.lbl_hour))
        b.tbWeeklyToggleBtn.text = getString(R.string.ci_desc, "7", getString(R.string.lbl_day))
    }

    private fun selectTime(index: Int) {
        if (b.selectedTime == index) return
        val old = b.timeButtons[b.selectedTime]
        b.selectedTime = index
        val mb = b.timeButtons[index]
        selectToggleBtnUi(mb)
        val timeCategory = AppConnectionsViewModel.TimeCategory.fromValue(index)
            ?: AppConnectionsViewModel.TimeCategory.SEVEN_DAYS
        networkLogsViewModel.timeCategoryChanged(timeCategory, true)
        unselectToggleBtnUi(old)
    }

    private fun selectToggleBtnUi(mb: WgControlState) {
        mb.backgroundTintList =
            ColorStateList.valueOf(
                UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
            )
        mb.setTextColor(UIUtils.fetchColor(this, R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(mb: WgControlState) {
        mb.setTextColor(UIUtils.fetchColor(this, R.attr.primaryTextColor))
        mb.backgroundTintList =
            ColorStateList.valueOf(
                UIUtils.fetchToggleBtnColors(this, R.color.defaultToggleBtnBg)
            )
    }

    private fun init() {
        if (!isActiveConns) {
            setTabbedViewTxt()
            highlightToggleBtn()
        } else {
            // no need to show toggle button and delete button for active connections
            b.toggleGroup.visibility = View.GONE
            b.awlDelete.visibility = View.GONE
        }

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

                val appName = appName(packages.count())
                updateAppNameInSearchHint(appName)
                displayIcon(
                    Utilities.getIcon(this, appInfo.packageName, appInfo.appName),
                    b.awlAppDetailIcon1
                )
            }
        }
    }

    private fun updateAppNameInSearchHint(appName: String) {
        val appNameTruncated = appName.substring(0, appName.length.coerceAtMost(10))
        val hint = if (isActiveConns) {
            getString(
                R.string.two_argument_colon,
                appNameTruncated,
                getString(R.string.search_universal_ips)
            )
        } else {
            getString(
                R.string.two_argument_colon,
                appNameTruncated,
                getString(R.string.search_custom_domains)
            )
        }
        b.awlSearch.queryHint = hint
        return
    }


    private fun highlightToggleBtn() {
        b.selectedTime = 2
        selectToggleBtnUi(b.tbWeeklyToggleBtn)
    }

    private fun setClickListener() {
        b.onTime = ::selectTime
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

    private fun displayIcon(drawable: Drawable?, mIconImageView: WgControlState) {
        if (isFinishing || isDestroyed) return
        mIconImageView.drawable = drawable ?: Utilities.getDefaultIcon(this)
    }

    private fun setActiveConnsAdapter(isRethink: Boolean) {
        Logger.v(LOG_TAG_UI, "setActiveConnsAdapter: uid: $uid, isRethink: $isRethink")
        if (!isRethink) networkLogsViewModel.setUid(uid)
        val recyclerAdapter = AppWiseDomainsAdapter(this, this, uid, true)
        if (isRethink) {
            val uptime = VpnController.uptimeMs()
            networkLogsViewModel.getRethinkAllActiveConns(uptime).observe(this) {
                // Fix: Stop layout before submitting data to prevent IndexOutOfBoundsException
                try {
                    b.stopScrollGeneration++
                    recyclerAdapter.submitData(this.lifecycle, it)
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_UI, "Error submitting rethink active connections data: ${e.message}", e)
                }
            }
        } else {
            networkLogsViewModel.activeConnections.observe(this) {
                // Fix: Stop layout before submitting data to prevent IndexOutOfBoundsException
                try {
                    b.stopScrollGeneration++
                    recyclerAdapter.submitData(this.lifecycle, it)
                } catch (e: Exception) {
                    Logger.e(
                        LOG_TAG_UI,
                        "Error submitting active connections data: ${e.message}",
                        e
                    )
                }
            }
        }
        b.awlRecyclerConnection.adapter = recyclerAdapter


    }

    private fun setAdapter() {
        networkLogsViewModel.setUid(uid)
        val recyclerAdapter = AppWiseDomainsAdapter(this, this, uid)
        networkLogsViewModel.appDomainLogs.observe(this) {
            // Fix: Stop layout before submitting data to prevent IndexOutOfBoundsException
            try {
                b.stopScrollGeneration++
                recyclerAdapter.submitData(this.lifecycle, it)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "Error submitting domain logs data: ${e.message}", e)
            }
        }
        b.awlRecyclerConnection.adapter = recyclerAdapter


    }

    private fun setRethinkAdapter() {
        networkLogsViewModel.setUid(uid)
        val recyclerAdapter = AppWiseDomainsAdapter(this, this, uid)
        networkLogsViewModel.rinrDomainLogs.observe(this) {
            // Fix: Stop layout before submitting data to prevent IndexOutOfBoundsException
            try {
                b.stopScrollGeneration++
                recyclerAdapter.submitData(this.lifecycle, it)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "Error submitting rethink domain logs data: ${e.message}", e)
            }
        }
        b.awlRecyclerConnection.adapter = recyclerAdapter


    }



    override fun onQueryTextSubmit(query: String): Boolean {
        Utilities.delay(QUERY_TEXT_DELAY, lifecycleScope) {
            if (!this.isFinishing) {
                val type = if (isActiveConns) {
                    AppConnectionsViewModel.FilterType.ACTIVE_CONNECTIONS
                } else {
                    AppConnectionsViewModel.FilterType.DOMAIN
                }
                networkLogsViewModel.setFilter(query, type)
            }
        }
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        Utilities.delay(QUERY_TEXT_DELAY, lifecycleScope) {
            if (!this.isFinishing) {
                val type = if (isActiveConns) {
                    AppConnectionsViewModel.FilterType.ACTIVE_CONNECTIONS
                } else {
                    AppConnectionsViewModel.FilterType.DOMAIN
                }
                networkLogsViewModel.setFilter(query, type)
            }
        }
        return true
    }

    private fun showDeleteConnectionsDialog() {
        val builder = WgAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.ada_delete_logs_dialog_title)
        builder.setMessage(R.string.ada_delete_logs_dialog_desc)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.lbl_proceed)) { _, _ -> deleteAppLogs() }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
        builder.create().show()
    }

    private fun deleteAppLogs() {
        io { networkLogsViewModel.deleteLogs(uid) }
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }
}
