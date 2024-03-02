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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityConfigureOtherDnsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.fragment.DnsCryptListFragment
import com.celzero.bravedns.ui.fragment.DnsProxyListFragment
import com.celzero.bravedns.ui.fragment.DoTListFragment
import com.celzero.bravedns.ui.fragment.DohListFragment
import com.celzero.bravedns.ui.fragment.ODoHListFragment
import com.celzero.bravedns.util.Themes
import com.google.android.material.tabs.TabLayoutMediator
import org.koin.android.ext.android.inject

class ConfigureOtherDnsActivity : AppCompatActivity(R.layout.activity_configure_other_dns) {
    private val b by viewBinding(ActivityConfigureOtherDnsBinding::bind)

    private val persistentState by inject<PersistentState>()

    private var dnsType: Int = 0

    companion object {
        private const val DNS_TYPE = "dns_type"

        fun getIntent(context: Context, dnsType: Int): android.content.Intent {
            val intent = android.content.Intent(context, ConfigureOtherDnsActivity::class.java)
            intent.putExtra(DNS_TYPE, dnsType)
            return intent
        }
    }

    enum class DnsScreen(val index: Int) {
        DOH(0),
        DNS_PROXY(1),
        DNS_CRYPT(2),
        DOT(3),
        ODOH(4);

        companion object {
            fun getCount(): Int {
                return entries.size
            }

            fun getDnsType(index: Int): DnsScreen {
                return when (index) {
                    DOH.index -> DOH
                    DNS_PROXY.index -> DNS_PROXY
                    DNS_CRYPT.index -> DNS_CRYPT
                    DOT.index -> DOT
                    ODOH.index -> ODOH
                    else -> DOH
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        dnsType = intent.getIntExtra(DNS_TYPE, dnsType)
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {

        b.dnsDetailActViewpager.adapter =
            object : FragmentStateAdapter(this) {
                override fun createFragment(position: Int): Fragment {
                    return when (DnsScreen.getDnsType(dnsType)) {
                        DnsScreen.DNS_CRYPT -> DnsCryptListFragment.newInstance()
                        DnsScreen.DNS_PROXY -> DnsProxyListFragment.newInstance()
                        DnsScreen.DOH -> DohListFragment.newInstance()
                        DnsScreen.DOT -> DoTListFragment.newInstance()
                        DnsScreen.ODOH -> ODoHListFragment.newInstance()
                    }
                }

                override fun getItemCount(): Int {
                    return 1
                }
            }

        TabLayoutMediator(b.dnsDetailActTabLayout, b.dnsDetailActViewpager) { tab, _ ->
                tab.text = getDnsTypeName(dnsType)
            }
            .attach()
    }

    private fun getDnsTypeName(type: Int): String {
        return when (DnsScreen.getDnsType(type)) {
            DnsScreen.DOH -> getString(R.string.other_dns_list_tab1)
            DnsScreen.DNS_CRYPT -> getString(R.string.dc_dns_crypt)
            DnsScreen.DNS_PROXY -> getString(R.string.other_dns_list_tab3)
            DnsScreen.DOT -> getString(R.string.lbl_dot)
            DnsScreen.ODOH -> getString(R.string.lbl_odoh)
        }
    }
}
