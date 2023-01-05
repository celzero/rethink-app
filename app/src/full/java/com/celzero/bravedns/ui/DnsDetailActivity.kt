/*
 * Copyright 2020 RethinkDNS and its authors
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

class DnsDetailActivity : AppCompatActivity(R.layout.activity_dns_detail) {
    private val b by viewBinding(ActivityDnsDetailBinding::bind)

    private var fragmentIndex = 0
    private var searchParam: String = ""
    private val persistentState by inject<PersistentState>()

    enum class Tabs(val screen: Int) {
        LOGS(0),
        CONFIGURE(1);

        companion object {
            fun getCount(): Int {
                return values().count()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, 0)
        searchParam = intent.getStringExtra(Constants.SEARCH_QUERY) ?: ""
        init()
    }

    private fun init() {

        b.dnsDetailActViewpager.adapter =
            object : FragmentStateAdapter(this) {
                override fun createFragment(position: Int): Fragment {
                    return when (position) {
                        Tabs.LOGS.screen -> DnsLogFragment.newInstance(searchParam)
                        Tabs.CONFIGURE.screen -> DnsConfigureFragment.newInstance()
                        else -> DnsConfigureFragment.newInstance()
                    }
                }

                override fun getItemCount(): Int {
                    return Tabs.getCount()
                }
            }

        TabLayoutMediator(b.dnsDetailActTabLayout, b.dnsDetailActViewpager) { tab, position ->
                tab.text =
                    when (position) {
                        Tabs.LOGS.screen -> getString(R.string.dns_act_log)
                        Tabs.CONFIGURE.screen -> getString(R.string.dns_act_configure_tab)
                        else -> getString(R.string.dns_act_configure_tab)
                    }
            }
            .attach()

        b.dnsDetailActViewpager.setCurrentItem(fragmentIndex, false)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
}
