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

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentDnsConfigureBinding
import com.celzero.bravedns.util.Constants

class DNSConfigureFragment : Fragment(R.layout.fragment_dns_configure) {
    private val b by viewBinding(FragmentDnsConfigureBinding::bind)

    private var isLocalConfigureVisible: Boolean = false
    private var isRethinkConfigureVisible: Boolean = false

    companion object {
        fun newInstance() = DNSConfigureFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        initClickListeners()
    }

    private fun initObservers() {

    }

    private fun initClickListeners() {

        b.onDeviceBlockConfigureTitle.setOnClickListener {
            if (isLocalConfigureVisible) {
                b.onDeviceBlockChipGroup.visibility = View.GONE
                isLocalConfigureVisible = false
            } else {
                b.onDeviceBlockChipGroup.visibility = View.VISIBLE
                isLocalConfigureVisible = true
            }
        }

        b.customBlocklistCard.setOnClickListener {
            val intent = Intent(requireContext(), DomainDetailActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }

        b.rethinkSubtitle.setOnClickListener {
            if (isRethinkConfigureVisible) {
                b.rethinkChipGroup.visibility = View.GONE
                isRethinkConfigureVisible = false
            } else {
                b.rethinkChipGroup.visibility = View.VISIBLE
                isRethinkConfigureVisible = true
            }
        }

        b.otherDnsCard.setOnClickListener {
            val intent = Intent(requireContext(), DNSListActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
    }

    private fun initView() {
        // Show the on-device blocklist card only on non-play store builds
        if (BuildConfig.FLAVOR != Constants.FLAVOR_PLAY) {
            b.onDeviceBlockConfigureTitle.visibility = View.VISIBLE
        } else {
            b.onDeviceBlockConfigureTitle.visibility = View.GONE
        }
    }

}
