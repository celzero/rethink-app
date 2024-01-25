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

package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityCustomRulesBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.fragment.CustomDomainFragment
import com.celzero.bravedns.ui.fragment.CustomIpFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.google.android.material.tabs.TabLayoutMediator
import org.koin.android.ext.android.inject

class CustomRulesActivity : AppCompatActivity(R.layout.activity_custom_rules) {
    private val b by viewBinding(ActivityCustomRulesBinding::bind)
    private var fragmentIndex = 0
    private var rulesType = RULES.APP_SPECIFIC_RULES.ordinal
    private var uid = Constants.UID_EVERYBODY
    private val persistentState by inject<PersistentState>()

    enum class Tabs(val screen: Int) {
        IP_RULES(0),
        DOMAIN_RULES(1);

        companion object {
            fun getCount(): Int {
                return entries.size
            }
        }
    }

    enum class RULES(val type: Int) {
        ALL_RULES(0),
        APP_SPECIFIC_RULES(1);

        companion object {
            fun getType(type: Int): RULES {
                return when (type) {
                    0 -> ALL_RULES
                    1 -> APP_SPECIFIC_RULES
                    else -> APP_SPECIFIC_RULES
                }
            }
        }
    }

    companion object {
        const val INTENT_RULES = "INTENT_RULES"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, 0)
        rulesType = intent.getIntExtra(INTENT_RULES, RULES.APP_SPECIFIC_RULES.type)
        uid = intent.getIntExtra(Constants.INTENT_UID, Constants.UID_EVERYBODY)
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {

        b.logsActViewpager.adapter =
            object : FragmentStateAdapter(this) {
                override fun createFragment(position: Int): Fragment {
                    val rt = RULES.getType(rulesType)
                    return when (position) {
                        Tabs.DOMAIN_RULES.screen -> CustomDomainFragment.newInstance(uid, rt)
                        Tabs.IP_RULES.screen -> CustomIpFragment.newInstance(uid, rt)
                        else -> CustomIpFragment.newInstance(uid, rt)
                    }
                }

                override fun getItemCount(): Int {
                    return Tabs.getCount()
                }
            }

        TabLayoutMediator(b.logsActTabLayout, b.logsActViewpager) { tab, position
                -> // Styling each tab here
                tab.text =
                    when (position) {
                        Tabs.DOMAIN_RULES.screen -> getString(R.string.dc_custom_block_heading)
                        Tabs.IP_RULES.screen -> getString(R.string.univ_view_blocked_ip)
                        else -> getString(R.string.univ_view_blocked_ip)
                    }
            }
            .attach()

        b.logsActViewpager.setCurrentItem(fragmentIndex, false)

        observeAppState()
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this) {
            if (it == BraveVPNService.State.PAUSED) {
                startActivity(Intent().setClass(this, PauseActivity::class.java))
                finish()
            }
        }
    }
}
