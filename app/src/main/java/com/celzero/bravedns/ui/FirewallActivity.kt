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

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityFirewallBinding
import com.celzero.bravedns.util.Constants
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.TabLayoutOnPageChangeListener


class FirewallActivity : AppCompatActivity(R.layout.activity_firewall), TabLayout.OnTabSelectedListener {
    private val b by viewBinding(ActivityFirewallBinding::bind)
    private var screenToLoad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenToLoad = intent.getIntExtra(Constants.SCREEN_TO_LOAD, 0)

        // FIXME: 22-01-2021 The view pager is migrated from ViewPager2 to Viewpager.  There is a
        // known bug in viewpager2 - Focus issue, the Firewall activity has search bar in all the
        // screens is causing the issue.
        //https://github.com/material-components/material-components-android/issues/500
        //https://github.com/android/views-widgets-samples/issues/107

        //Adding the tabs using addTab() method
        b.firewallActTabLayout.addTab(b.firewallActTabLayout.newTab().setText(getString(R.string.firewall_act_universal_tab)))
        b.firewallActTabLayout.addTab(b.firewallActTabLayout.newTab().setText(getString(R.string.firewall_act_network_monitor_tab)))
        b.firewallActTabLayout.addTab(b.firewallActTabLayout.newTab().setText(getString(R.string.firewall_act_apps_tab)))
        b.firewallActTabLayout.tabGravity = TabLayout.GRAVITY_FILL

        //Creating our pager adapter
        val adapter = Pager(supportFragmentManager, b.firewallActTabLayout.tabCount)

        //Adding adapter to pager
        b.firewallActViewpager.adapter = adapter
        b.firewallActViewpager.setCurrentItem(screenToLoad, true)

        b.firewallActViewpager.addOnPageChangeListener(TabLayoutOnPageChangeListener(b.firewallActTabLayout))

        //Adding onTabSelectedListener to swipe views
        b.firewallActTabLayout.setOnTabSelectedListener(this)
    }

    override fun onTabSelected(tab: TabLayout.Tab) {
        b.firewallActViewpager.currentItem = tab.position
        b.firewallActTabLayout.selectTab(tab)
    }

    override fun onTabUnselected(tab: TabLayout.Tab?) {}

    override fun onTabReselected(tab: TabLayout.Tab?) {}

}

internal class Pager(fm: FragmentManager?, var tabCount: Int) : FragmentStatePagerAdapter(fm!!) {
    //Overriding method getItem
    override fun getItem(position: Int): Fragment {
        //Returning the current tabs
        return when (position) {
            0 -> {
                UniversalFirewallFragment.newInstance()
            }
            1 -> {
                ConnectionTrackerFragment.newInstance()
            }
            2 -> {
                FirewallAppFragment.newInstance()
            }
            else -> FirewallAppFragment.newInstance()
        }
    }

    //Overriden method getCount to get the number of tabs
    override fun getCount(): Int {
        return tabCount
    }
}