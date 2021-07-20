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
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityFirewallBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
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
