/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityFirewallBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.google.android.material.tabs.TabLayoutMediator
import org.koin.android.ext.android.inject


class FirewallActivity : AppCompatActivity(R.layout.activity_firewall) {
    private val b by viewBinding(ActivityFirewallBinding::bind)
    private var fragmentIndex = 0
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAB_LAYOUT_TOTAL_COUNT = 3
    }

    enum class FirewallTabs(val screen: Int) {
        UNIVERSAL(0), LOGS(1), ALL_APPS(2)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, 0)
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {

        b.firewallActViewpager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    FirewallTabs.UNIVERSAL.screen -> UniversalFirewallFragment.newInstance()
                    FirewallTabs.LOGS.screen -> ConnectionTrackerFragment.newInstance()
                    FirewallTabs.ALL_APPS.screen -> FirewallAppFragment.newInstance()
                    else -> UniversalFirewallFragment.newInstance()
                }
            }

            override fun getItemCount(): Int {
                return TAB_LAYOUT_TOTAL_COUNT
            }
        }

        TabLayoutMediator(b.firewallActTabLayout,
                          b.firewallActViewpager) { tab, position -> // Styling each tab here
            tab.text = when (position) {
                FirewallTabs.UNIVERSAL.screen  -> getString(R.string.firewall_act_universal_tab)
                FirewallTabs.LOGS.screen  -> getString(R.string.firewall_act_network_monitor_tab)
                FirewallTabs.ALL_APPS.screen  -> getString(R.string.firewall_act_apps_tab)
                else -> getString(R.string.firewall_act_universal_tab)
            }
        }.attach()

        b.firewallActViewpager.setCurrentItem(fragmentIndex, true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // In two-cases (accessibility failure/new app install action), the app directly launches
        // firewall activity from notification action. Need to handle the pause state for those cases
        checkAppState()

        if (isAccessibilityIntent(intent)) {
            handleAccessibilitySettings()
        } else if (isNewAppInstalledIntent(intent)) {
            // navigate to all apps screen
            b.firewallActViewpager.setCurrentItem(FirewallTabs.ALL_APPS.screen, true)
        } else {
            // no-op
        }
    }

    private fun checkAppState() {
        VpnController.connectionStatus.observe(this, {
            if (it == BraveVPNService.State.PAUSED) {
                openAppPausedActivity()
            }
        })
    }

    private fun openAppPausedActivity() {
        val intent = Intent()
        intent.setClass(this, PauseActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun isAccessibilityIntent(intent: Intent): Boolean {
        // Created as part of accessibility failure notification.
        val extras = intent.extras
        if (extras != null) {
            val value = extras.getString(Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME)
            if (!value.isNullOrEmpty() && value == Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE) {
                return true
            }
        }
        return false
    }

    private fun isNewAppInstalledIntent(intent: Intent): Boolean {
        // check whether the intent is from new app installed notification
        val extras = intent.extras
        if (extras != null) {
            val value = extras.getString(Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME)
            if (!value.isNullOrEmpty() && value == Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE) {
                return true
            }
        }
        return false
    }

    private fun handleAccessibilitySettings() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.alert_permission_accessibility_regrant)
        builder.setMessage(R.string.alert_firewall_accessibility_regrant_explanation)
        builder.setPositiveButton(
            getString(R.string.univ_accessibility_crash_dialog_positive)) { _, _ ->
            openRethinkAppInfo(this)
        }
        builder.setNegativeButton(
            getString(R.string.univ_accessibility_crash_dialog_negative)) { _, _ ->
        }
        builder.setCancelable(false)

        builder.create().show()
    }

    // FIXME: Add appropriate to ensure back button navigation.
    // Today, if user presses back from settings screen, it naivgates to launcher instead
    private fun openRethinkAppInfo(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val packageName = context.packageName
        intent.data = Uri.parse("package:$packageName")
        ContextCompat.startActivity(context, intent, null)
    }
}
