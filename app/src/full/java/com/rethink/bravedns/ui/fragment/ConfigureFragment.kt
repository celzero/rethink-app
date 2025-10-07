/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.rethinkdns.retrixed.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.rethinkdns.retrixed.R
import com.rethinkdns.retrixed.databinding.FragmentConfigureBinding
import com.rethinkdns.retrixed.ui.activity.AppListActivity
import com.rethinkdns.retrixed.ui.activity.AdvancedSettingActivity
import com.rethinkdns.retrixed.ui.activity.AntiCensorshipActivity
import com.rethinkdns.retrixed.ui.activity.DnsDetailActivity
import com.rethinkdns.retrixed.ui.activity.FirewallActivity
import com.rethinkdns.retrixed.ui.activity.MiscSettingsActivity
import com.rethinkdns.retrixed.ui.activity.NetworkLogsActivity
import com.rethinkdns.retrixed.ui.activity.ProxySettingsActivity
import com.rethinkdns.retrixed.ui.activity.TunnelSettingsActivity
import com.rethinkdns.retrixed.util.NewSettingsManager
import com.rethinkdns.retrixed.util.UIUtils.setBadgeDotVisible

class ConfigureFragment : Fragment(R.layout.fragment_configure) {

    private val b by viewBinding(FragmentConfigureBinding::bind)

    enum class ScreenType {
        APPS,
        DNS,
        FIREWALL,
        PROXY,
        VPN,
        OTHERS,
        LOGS,
        ANTI_CENSORSHIP
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        showNewBadgeIfNeeded()
    }

    private fun initView() {
        b.fsNetworkTv.text = getString(R.string.lbl_network).replaceFirstChar(Char::titlecase)
        b.fsLogsTv.text = getString(R.string.lbl_logs).replaceFirstChar(Char::titlecase)
        b.fsAntiCensorshipTv.text = getString(R.string.anti_censorship_title).replaceFirstChar(Char::titlecase)
    }

    private fun showNewBadgeIfNeeded() {
        val antiCensorship = NewSettingsManager.shouldShowBadge(NewSettingsManager.ANTI_CENSORSHIP)
        if (antiCensorship) {
            b.fsAntiCensorshipTv.setBadgeDotVisible(requireContext(), true)
        }
    }

    private fun setupClickListeners() {
        b.fsAppsCard.setOnClickListener {
            // open apps configuration
            startActivity(ScreenType.APPS)
        }

        b.fsDnsCard.setOnClickListener {
            // open dns configuration
            startActivity(ScreenType.DNS)
        }

        b.fsFirewallCard.setOnClickListener {
            // open firewall configuration
            startActivity(ScreenType.FIREWALL)
        }

        b.fsProxyCard.setOnClickListener {
            // open proxy configuration
            startActivity(ScreenType.PROXY)
        }

        b.fsNetworkCard.setOnClickListener {
            // open vpn configuration
            startActivity(ScreenType.VPN)
        }

        b.fsOthersCard.setOnClickListener {
            // open others configuration
            startActivity(ScreenType.OTHERS)
        }

        b.fsLogsCard.setOnClickListener {
            // open logs configuration
            startActivity(ScreenType.LOGS)
        }

        b.fsAntiCensorshipCard.setOnClickListener {
            // open developer options configuration
            startActivity(ScreenType.ANTI_CENSORSHIP)
            NewSettingsManager.markSettingSeen(NewSettingsManager.ANTI_CENSORSHIP)
        }
    }

    private fun startActivity(type: ScreenType) {
        val intent =
            when (type) {
                ScreenType.APPS -> Intent(requireContext(), AppListActivity::class.java)
                ScreenType.DNS -> Intent(requireContext(), DnsDetailActivity::class.java)
                ScreenType.FIREWALL -> Intent(requireContext(), FirewallActivity::class.java)
                ScreenType.PROXY -> Intent(requireContext(), ProxySettingsActivity::class.java)
                ScreenType.VPN -> Intent(requireContext(), TunnelSettingsActivity::class.java)
                ScreenType.OTHERS -> Intent(requireContext(), MiscSettingsActivity::class.java)
                ScreenType.LOGS -> Intent(requireContext(), NetworkLogsActivity::class.java)
                ScreenType.ANTI_CENSORSHIP -> Intent(requireContext(), AntiCensorshipActivity::class.java)
            }
        startActivity(intent)
    }
}
