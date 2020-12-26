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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class DNSDetailActivity : AppCompatActivity() {
    private lateinit var viewPagerDNS: ViewPager2
    private lateinit var tabLayoutFirewall: TabLayout
    private val DNS_TABS_COUNT = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_detail)
        init()
    }

    private fun init() {

        viewPagerDNS = findViewById(R.id.dns_detail_act_viewpager)
        tabLayoutFirewall = findViewById(R.id.dns_detail_act_tabLayout)

        viewPagerDNS.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> DNSLogFragment.newInstance()
                    else -> ConfigureDNSFragment.newInstance()
                }
            }

            override fun getItemCount(): Int {
                return DNS_TABS_COUNT
            }
        }

        TabLayoutMediator(tabLayoutFirewall, viewPagerDNS) { tab, position -> // Styling each tab here
            tab.text = when (position) {
                0 -> getString(R.string.dns_act_log)
                else -> getString(R.string.dns_act_configure_tab)
            }
            viewPagerDNS.setCurrentItem(tab.position, true)
        }.attach()

        val recyclerViewField = ViewPager2::class.java.getDeclaredField("mRecyclerView")
        recyclerViewField.isAccessible = true
        val recyclerView = recyclerViewField.get(viewPagerDNS) as RecyclerView

        val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
        touchSlopField.isAccessible = true
        val touchSlop = touchSlopField.get(recyclerView) as Int
        touchSlopField.set(recyclerView, touchSlop * 2)       // "8" was obtained experimentally
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "onActivityResult")
        if (resultCode == Activity.RESULT_OK) {
            val stamp = data?.getStringArrayExtra("stamp")
            if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "onActivityResult - Stamp : $stamp")
        }
    }


}