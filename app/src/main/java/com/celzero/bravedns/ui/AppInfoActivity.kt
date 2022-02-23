/*
 * Copyright 2021 RethinkDNS and its authors
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

import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppConnectionAdapter
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.databinding.ActivityAppDetailsBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_3
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class AppInfoActivity : AppCompatActivity(R.layout.activity_app_details) {
    private val b by viewBinding(ActivityAppDetailsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private var uid: Int = 0
    private lateinit var appInfo: AppInfo
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()
    private var ipListState: Boolean = true

    private var appStatus = FirewallManager.AppStatus.ALLOW
    private var connStatus = FirewallManager.ConnectionStatus.BOTH

    companion object {
        const val UID_INTENT_NAME = "UID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        uid = intent.getIntExtra(UID_INTENT_NAME, INVALID_UID)
        init()
        setupClickListeners()
    }

    private fun init() {
        val ai = FirewallManager.getAppInfoByUid(uid)
        // case: app is uninstalled but still available in RethinkDNS database
        if (ai == null || uid == INVALID_UID) {
            showNoAppFoundDialog()
            return
        }

        // asserting app info as the null-check for appInfo is done above
        appInfo = ai

        val packages = FirewallManager.getPackageNamesByUid(appInfo.uid)

        if (packages.count() != 1) {
            b.aadAppInfoIcon.visibility = View.GONE
        }

        b.aadAppDetailName.text = appName(packages.count())
        b.aadAppDetailDesc.text = appInfo.packageInfo
        updateBasicAppInfo()
        appStatus = FirewallManager.appStatus(appInfo.uid)
        connStatus = FirewallManager.connectionStatus(appInfo.uid)
        updateFirewallStatusUi(appStatus, connStatus)
        toggleIpRulesState(ipListState)

        displayIcon(Utilities.getIcon(this, appInfo.packageInfo, appInfo.appName),
                    b.aadAppDetailIcon)

        displayNetworkLogsIfAny(appInfo.uid)
    }

    private fun updateFirewallStatusUi(appStatus: FirewallManager.AppStatus,
                                       connectionStatus: FirewallManager.ConnectionStatus) {
        b.aadFirewallStatus.text = updateHtmlEncodedText(
            getString(R.string.ada_firewall_status, getFirewallText(appStatus, connectionStatus)))

        when (appStatus) {
            FirewallManager.AppStatus.ALLOW -> {
                disableWhitelistExcludeUi()
                enableAllow()
            }
            FirewallManager.AppStatus.BLOCK -> {
                disableWhitelistExcludeUi()
                enableBlock(connectionStatus)
            }
            FirewallManager.AppStatus.EXCLUDE -> {
                enableAppExcludedUi()
            }
            FirewallManager.AppStatus.WHITELIST -> {
                enableAppWhitelistedUi()
            }
            FirewallManager.AppStatus.UNTRACKED -> {
                // no-op
            }
        }
    }

    private fun setupClickListeners() {
        b.aadAppInfoIcon.setOnClickListener {
            Utilities.openAndroidAppInfo(this, appInfo.packageInfo)
        }

        b.aadAppSettingsBlock.setOnClickListener {
            toggleWifi(appInfo)
            updateFirewallStatusUi(appStatus, connStatus)
        }

        b.aadAppSettingsBlockMd.setOnClickListener {
            toggleMobileData(appInfo)
            updateFirewallStatusUi(appStatus, connStatus)
        }

        b.aadAppSettingsWhitelist.setOnClickListener {
            updateFirewallStatus(FirewallManager.AppStatus.WHITELIST, FirewallManager.ConnectionStatus.BOTH)
        }

        b.aadAppSettingsExclude.setOnClickListener {
            updateFirewallStatus(FirewallManager.AppStatus.EXCLUDE, FirewallManager.ConnectionStatus.BOTH)
        }

        b.aadConnDetailIndicator.setOnClickListener {
            toggleIpRulesState(ipListState)
        }

        b.aadConnDetailRl.setOnClickListener {
            toggleIpRulesState(ipListState)
        }
    }

    private fun toggleMobileData(appInfo: AppInfo) {
        val status = FirewallManager.appStatus(appInfo.uid)
        var aStat : FirewallManager.AppStatus = FirewallManager.AppStatus.BLOCK
        var cStat : FirewallManager.ConnectionStatus = FirewallManager.ConnectionStatus.BOTH

        when (FirewallManager.connectionStatus(appInfo.uid)) {
            FirewallManager.ConnectionStatus.MOBILE_DATA -> {
                aStat = FirewallManager.AppStatus.ALLOW
            }
            FirewallManager.ConnectionStatus.BOTH -> {
                cStat = if (status.blocked()) {
                    FirewallManager.ConnectionStatus.WIFI
                } else {
                    FirewallManager.ConnectionStatus.MOBILE_DATA
                }
            }
            else -> {
                //  no-op
            }
        }

        updateFirewallStatus(aStat, cStat)
    }

    private fun toggleWifi(appInfo: AppInfo) {
        val currentStatus = FirewallManager.appStatus(appInfo.uid)

        var aStat : FirewallManager.AppStatus = FirewallManager.AppStatus.BLOCK
        var cStat : FirewallManager.ConnectionStatus = FirewallManager.ConnectionStatus.BOTH

        when (FirewallManager.connectionStatus(appInfo.uid)) {
            FirewallManager.ConnectionStatus.WIFI -> {
                aStat = FirewallManager.AppStatus.ALLOW
            }
            FirewallManager.ConnectionStatus.BOTH -> {
                cStat = if (currentStatus.blocked()) {
                    FirewallManager.ConnectionStatus.MOBILE_DATA
                } else {
                    FirewallManager.ConnectionStatus.WIFI
                }
            }
            else -> {
                // no-op
            }
        }

        updateFirewallStatus(aStat, cStat)
    }

    private fun updateFirewallStatus(aStat: FirewallManager.AppStatus,
                                     cStat: FirewallManager.ConnectionStatus) {
        val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
        if (appNames.count() > 1) {
            showDialog(appNames, appInfo, aStat, cStat)
            return
        }

        completeFirewallChanges(aStat, cStat)
    }

    private fun completeFirewallChanges(aStat: FirewallManager.AppStatus, cStat: FirewallManager.ConnectionStatus) {
        appStatus = aStat
        connStatus = cStat
        updateFirewallStatus(appInfo.uid, aStat, cStat)
        updateFirewallStatusUi(aStat, cStat)
    }

    private fun displayNetworkLogsIfAny(uid: Int) {
        io {
            val list = connectionTrackerRepository.getLogsForApp(uid)

            uiCtx {
                if (list.isEmpty()) {
                    b.aadConnDetailDesc.text = getString(R.string.ada_ip_connection_count_zero)
                    b.aadConnDetailEmptyTxt.visibility = View.VISIBLE
                    b.aadConnDetailRecycler.visibility = View.GONE
                    return@uiCtx
                }

                b.aadConnDetailDesc.text = getString(R.string.ada_ip_connection_count,
                                                     list.size.toString())
                b.aadConnDetailRecycler.setHasFixedSize(true)
                layoutManager = LinearLayoutManager(this)
                b.aadConnDetailRecycler.layoutManager = layoutManager
                val recyclerAdapter = AppConnectionAdapter(this, list, uid)
                b.aadConnDetailRecycler.adapter = recyclerAdapter
                val dividerItemDecoration = DividerItemDecoration(b.aadConnDetailRecycler.context,
                                                                  (layoutManager as LinearLayoutManager).orientation)
                b.aadConnDetailRecycler.addItemDecoration(dividerItemDecoration)
            }
        }
    }

    private fun toggleIpRulesState(state: Boolean) {
        if (state) {
            b.aadConnDetailTopLl.visibility = View.VISIBLE
            b.aadConnDetailSearchLl.visibility = View.VISIBLE
            b.aadConnDetailIndicator.setImageResource(R.drawable.ic_keyboard_arrow_up_gray_24dp)
        } else {
            b.aadConnDetailSearchLl.visibility = View.GONE
            b.aadConnDetailTopLl.visibility = View.GONE
            b.aadConnDetailIndicator.setImageResource(R.drawable.ic_keyboard_arrow_down_gray_24dp)
        }
        ipListState = !state
    }


    private fun enableAppWhitelistedUi() {
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlock)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_whitelist_on, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
    }

    private fun enableAppExcludedUi() {
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlock)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_on, b.aadAppSettingsExclude)
    }

    private fun disableWhitelistExcludeUi() {
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
    }

    private fun enableAllow() {
        setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlock)
        setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
    }

    private fun enableBlock(cStat: FirewallManager.ConnectionStatus) {
        when (cStat) {
            FirewallManager.ConnectionStatus.MOBILE_DATA -> {
                setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlock)
                setDrawable(R.drawable.ic_firewall_data_off, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.WIFI -> {
                setDrawable(R.drawable.ic_firewall_wifi_off, b.aadAppSettingsBlock)
                setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.BOTH -> {
                setDrawable(R.drawable.ic_firewall_wifi_off, b.aadAppSettingsBlock)
                setDrawable(R.drawable.ic_firewall_data_off, b.aadAppSettingsBlockMd)
            }
        }
        setDrawable(R.drawable.ic_firewall_whitelist_off, b.aadAppSettingsWhitelist)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
    }

    private fun setDrawable(drawable: Int, txt: TextView) {
        val top = ContextCompat.getDrawable(this, drawable)
        txt.setCompoundDrawablesWithIntrinsicBounds(null, top, null, null)
    }

    private fun getFirewallText(aStat: FirewallManager.AppStatus,
                                cStat: FirewallManager.ConnectionStatus): CharSequence {
        return when (aStat) {
            FirewallManager.AppStatus.ALLOW -> "Allowed"
            FirewallManager.AppStatus.EXCLUDE -> "Excluded"
            FirewallManager.AppStatus.WHITELIST -> "Whitelisted"
            FirewallManager.AppStatus.BLOCK -> {
                when {
                    cStat.mobileData() -> "Allowed on WiFi"
                    cStat.wifi() -> "Allowed on Mobile data"
                    else -> "Blocked"
                }
            }
            FirewallManager.AppStatus.UNTRACKED -> "Unknown"
        }
    }

    private fun appName(packageCount: Int): String {
        return if (packageCount >= 2) {
            getString(R.string.ctbs_app_other_apps, appInfo.appName,
                      packageCount.minus(1).toString())
        } else {
            appInfo.appName
        }
    }

    private fun showNoAppFoundDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.ada_noapp_dialog_title))
        builder.setMessage(getString(R.string.ada_noapp_dialog_message))
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.ada_noapp_dialog_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            finish()
        }
        builder.create().show()
    }

    private fun showDialog(packageList: List<String>, appInfo: AppInfo,
                           aStat: FirewallManager.AppStatus, cStat: FirewallManager.ConnectionStatus) {

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(this)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        val count = packageList.count()
        builderSingle.setTitle(
            this.getString(R.string.ctbs_block_other_apps, appInfo.appName, count.toString()))

        val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageList)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle.setPositiveButton(aStat.name) { di: DialogInterface, _: Int ->
            di.dismiss()
            completeFirewallChanges(aStat, cStat)
        }.setNeutralButton(
            this.getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
        }

        val alertDialog: AlertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.show()
    }

    private fun updateBasicAppInfo() {
        // TODO: As we get the packageInfo details, we can now show the permission
        // details of the application as well. Need to add when the permission manager is
        // introduced.
        try {
            val packageInfo: PackageInfo = this.packageManager.getPackageInfo(appInfo.packageInfo,
                                                                              PackageManager.GET_PERMISSIONS)
            val installTime = Utilities.convertLongToTime(packageInfo.firstInstallTime,
                                                          TIME_FORMAT_3)
            val updateTime = Utilities.convertLongToTime(packageInfo.lastUpdateTime, TIME_FORMAT_3)
            b.aadDetails.text = updateHtmlEncodedText(
                getString(R.string.ada_uid, appInfo.uid.toString(), appInfo.appCategory,
                          installTime, updateTime))
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        GlideApp.with(this).load(drawable).error(Utilities.getDefaultIcon(this)).into(
            mIconImageView)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

}
