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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Constants
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class FirewallActivity : AppCompatActivity() {
    private lateinit var viewPagerFirewall : ViewPager2
    private lateinit var tabLayoutFirewall : TabLayout
    private val FIREWALL_TABS_COUNT = 3


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall)
        init()
    }

    private fun init() {

        viewPagerFirewall = findViewById(R.id.firewall_act_viewpager)
        tabLayoutFirewall = findViewById(R.id.firewall_act_tabLayout)

        viewPagerFirewall.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> UniversalFirewallFragment.newInstance()
                    1 -> ConnectionTrackerFragment.newInstance()
                    else -> FirewallAppFragment.newInstance()
                }
            }
            override fun getItemCount(): Int {
                return FIREWALL_TABS_COUNT
            }
        }

        TabLayoutMediator(tabLayoutFirewall, viewPagerFirewall) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.firewall_act_universal_tab)
                1 -> getString(R.string.firewall_act_network_monitor_tab)
                else -> getString(R.string.firewall_act_apps_tab)
            }
            viewPagerFirewall.setCurrentItem(tab.position, false)
        }.attach()

        viewPagerFirewall.offscreenPageLimit = 2


    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.i(Constants.LOG_TAG, "New intent with flags for Firewall Activity: "+intent?.flags)
    }

}