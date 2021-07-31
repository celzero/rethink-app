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
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityFirewallBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.google.android.material.tabs.TabLayout
import org.koin.android.ext.android.inject


class FirewallActivity : AppCompatActivity(R.layout.activity_firewall),
                         TabLayout.OnTabSelectedListener {
    private val b by viewBinding(ActivityFirewallBinding::bind)
    private var fragmentIndex = 0
    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Utilities.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        fragmentIndex = intent.getIntExtra(Constants.SCREEN_TO_LOAD, 0)

        val extras = intent.extras
        if (extras != null) {
            val value = extras.getString(Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME)
            if (!value.isNullOrEmpty() && value == Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE) {
                if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LoggerConstants.LOG_TAG_UI,
                                                                   "Intent thrown as part of accessibility failure notification.")
                handleAccessibilitySettings()
            }
        }

        // FIXME: 22-01-2021 The view pager is migrated from ViewPager2 to Viewpager. There is a
        // known bug in viewpager2 - Focus issue, the Firewall activity has search bar in all the
        // screens is causing the issue.
        //https://github.com/material-components/material-components-android/issues/500
        //https://github.com/android/views-widgets-samples/issues/107

        val tabTitles = arrayOf(getString(R.string.firewall_act_universal_tab),
                                getString(R.string.firewall_act_network_monitor_tab),
                                getString(R.string.firewall_act_apps_tab))
        b.firewallActTabLayout.setupWithViewPager(b.firewallActViewpager)

        //Adding the tabs using addTab() method
        b.firewallActTabLayout.addTab(b.firewallActTabLayout.newTab())
        b.firewallActTabLayout.addTab(b.firewallActTabLayout.newTab())
        b.firewallActTabLayout.addTab(b.firewallActTabLayout.newTab())
        b.firewallActTabLayout.tabGravity = TabLayout.GRAVITY_FILL

        //Creating our pager adapter
        val adapter = Pager(supportFragmentManager, b.firewallActTabLayout.tabCount, tabTitles)

        //Adding adapter to pager
        b.firewallActViewpager.adapter = adapter
        b.firewallActViewpager.setCurrentItem(fragmentIndex, false)
        b.firewallActViewpager.offscreenPageLimit = b.firewallActTabLayout.tabCount

        b.firewallActViewpager.addOnPageChangeListener(
            TabLayout.TabLayoutOnPageChangeListener(b.firewallActTabLayout))
        b.firewallActTabLayout.addOnTabSelectedListener(this)
    }

    override fun onTabSelected(tab: TabLayout.Tab) {
        b.firewallActTabLayout.selectTab(tab)
        b.firewallActViewpager.setCurrentItem(tab.position, false)
    }

    override fun onTabUnselected(tab: TabLayout.Tab?) {}

    override fun onTabReselected(tab: TabLayout.Tab?) {}

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Created as part of accessibility failure notification.
        val extras = intent.extras
        if (extras != null) {
            val value = extras.getString(Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME)
            if (!value.isNullOrEmpty() && value == Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE) {
                if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LoggerConstants.LOG_TAG_UI,
                                                                   "Intent thrown as part of accessibility failure notification.")
                // Reset the notification counter once user acts on the notification.
                // There was a case, where notifications were not shown post the user action.
                VpnController.getBraveVpnService()?.totalAccessibilityFailureNotifications = 0
                handleAccessibilitySettings()
            }
        }
    }

    private fun handleAccessibilitySettings() {
        if (!persistentState.backgroundEnabled) return

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

internal class Pager(fm: FragmentManager?, var tabCount: Int, var tabTitles: Array<String>) :
        FragmentStatePagerAdapter(fm!!) {
    override fun getItem(position: Int): Fragment {
        return when (position) {
            0 -> {
                UniversalFirewallFragment.newInstance()
            }
            1 -> {
                ConnectionTrackerFragment.newInstance()
            }
            else -> {
                FirewallAppFragment.newInstance()
            }
        }
    }

    override fun getCount(): Int {
        return tabCount
    }

    override fun getPageTitle(position: Int): CharSequence {
        return tabTitles[position]
    }
}
