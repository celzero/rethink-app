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
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityOtherDnsListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.google.android.material.tabs.TabLayoutMediator
import org.koin.android.ext.android.inject

class DnsListActivity : AppCompatActivity(R.layout.activity_other_dns_list) {
    private val b by viewBinding(ActivityOtherDnsListBinding::bind)

    private var fragmentIndex = 0
    private val dnsTabsCount = 3
    private val persistentState by inject<PersistentState>()

    enum class Tabs(val screen: Int) {
        DOH(0),
        DNSCRYPT(1),
        DNSPROXY(2);

        companion object {
            fun getCount(): Int {
                return values().count()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, fragmentIndex)
        init()
    }

    private fun init() {
        b.otherDnsActViewpager.adapter =
            object : FragmentStateAdapter(this) {
                override fun createFragment(position: Int): Fragment {
                    return when (position) {
                        0 -> DohListFragment.newInstance()
                        1 -> DnsCryptListFragment.newInstance()
                        else -> DnsProxyListFragment.newInstance()
                    }
                }

                override fun getItemCount(): Int {
                    return dnsTabsCount
                }
            }

        TabLayoutMediator(b.otherDnsActTabLayout, b.otherDnsActViewpager) { tab, position ->
                tab.text =
                    when (position) {
                        0 -> getString(R.string.other_dns_list_tab1)
                        1 -> getString(R.string.other_dns_list_tab2)
                        else -> getString(R.string.other_dns_list_tab3)
                    }
            }
            .attach()

        b.otherDnsActViewpager.setCurrentItem(fragmentIndex, true)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
}
