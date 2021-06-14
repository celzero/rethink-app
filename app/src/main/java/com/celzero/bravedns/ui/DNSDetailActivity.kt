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
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityDnsDetailBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.google.android.material.tabs.TabLayoutMediator
import org.koin.android.ext.android.inject

class DNSDetailActivity : AppCompatActivity(R.layout.activity_dns_detail) {
    private val b by viewBinding(ActivityDnsDetailBinding::bind)
    private val dnsTabsCount = 2
    private var screenToLoad = 0
    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (persistentState.theme == 0) {
            if (isDarkThemeOn()) {
                setTheme(R.style.AppThemeTrueBlack)
            } else {
                setTheme(R.style.AppThemeWhite)
            }
        } else if (persistentState.theme == 1) {
            setTheme(R.style.AppThemeWhite)
        } else if (persistentState.theme == 2) {
            setTheme(R.style.AppTheme)
        } else {
            setTheme(R.style.AppThemeTrueBlack)
        }
        super.onCreate(savedInstanceState)
        screenToLoad = intent.getIntExtra(Constants.SCREEN_TO_LOAD,0)
        init()
    }

    private fun init() {
        b.dnsDetailActViewpager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> DNSLogFragment.newInstance()
                    else -> ConfigureDNSFragment.newInstance()
                }
            }

            override fun getItemCount(): Int {
                return dnsTabsCount
            }
        }

        TabLayoutMediator(b.dnsDetailActTabLayout, b.dnsDetailActViewpager) { tab, position -> // Styling each tab here
            tab.text = when (position) {
                0 -> getString(R.string.dns_act_log)
                else -> getString(R.string.dns_act_configure_tab)
            }
        }.attach()

        b.dnsDetailActViewpager.setCurrentItem(screenToLoad, true)
    }

    private fun Context.isDarkThemeOn(): Boolean {
           return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            val stamp = data?.getStringArrayExtra(Constants.STAMP_INTENT_EXTRA)
        }
    }
}