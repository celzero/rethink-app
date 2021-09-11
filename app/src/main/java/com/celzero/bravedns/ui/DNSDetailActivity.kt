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
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityDnsDetailBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.google.android.material.tabs.TabLayoutMediator
import org.koin.android.ext.android.inject

class DNSDetailActivity : AppCompatActivity(R.layout.activity_dns_detail) {
    private val b by viewBinding(ActivityDnsDetailBinding::bind)
    private var fragmentIndex = 0
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAB_LAYOUT_LOGS = 0
        private const val TAB_LAYOUT_CONFIGURE = 1
        private const val TAB_LAYOUT_TOTAL_COUNT = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        fragmentIndex = intent.getIntExtra(Constants.SCREEN_TO_LOAD, fragmentIndex)
        init()
    }

    private fun init() {
        b.dnsDetailActViewpager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    TAB_LAYOUT_LOGS -> DNSLogFragment.newInstance()
                    TAB_LAYOUT_CONFIGURE -> ConfigureDNSFragment.newInstance()
                    else -> ConfigureDNSFragment.newInstance()
                }
            }

            override fun getItemCount(): Int {
                return TAB_LAYOUT_TOTAL_COUNT
            }
        }

        TabLayoutMediator(b.dnsDetailActTabLayout,
                          b.dnsDetailActViewpager) { tab, position -> // Styling each tab here
            tab.text = when (position) {
                TAB_LAYOUT_LOGS -> getString(R.string.dns_act_log)
                TAB_LAYOUT_CONFIGURE -> getString(R.string.dns_act_configure_tab)
                else -> getString(R.string.dns_act_configure_tab)
            }
        }.attach()

        b.dnsDetailActViewpager.setCurrentItem(fragmentIndex, true)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}
