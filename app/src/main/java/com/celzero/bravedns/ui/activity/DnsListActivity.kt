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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import androidx.activity.compose.setContent
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.dnssettings.*
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class DnsListActivity : BaseActivity() {
    private val b = DnsProtocolState()

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private companion object {
        // Minimum window width (in dp) at which the DNS card grid switches from
        // 2 to 3 columns; unfolded foldables (~590dp) and tablets cross this.
        const val GRID_WIDE_MIN_WIDTH_DP = 480
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        setContent { RethinkTheme { DnsProtocolGrid(b) } }
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupClickListeners() {
        b.cardDnscrypt.onClick = {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DNS_CRYPT.index
                )
            )
        }

        b.cardDnsproxy.onClick = {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DNS_PROXY.index
                )
            )
        }

        b.cardDoh.onClick = {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DOH.index
                )
            )
        }

        b.cardDot.onClick = {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.DOT.index
                )
            )
        }

        b.cardOdoh.onClick = {
            startActivity(
                ConfigureOtherDnsActivity.getIntent(
                    this,
                    ConfigureOtherDnsActivity.DnsScreen.ODOH.index
                )
            )
        }

        b.cardRethinkDns.onClick = { invokeRethinkActivity() }
    }

    private fun invokeRethinkActivity() {
        val intent = Intent(this, ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(
            ConfigureRethinkBasicActivity.INTENT,
            ConfigureRethinkBasicActivity.FragmentLoader.DB_LIST.ordinal
        )
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        resetUi()
        updateSelectedStatus()
    }

    private fun resetUi() {
        b.cardDoh.strokeWidth = 0
        b.cardDnsproxy.strokeWidth = 0
        b.cardDnscrypt.strokeWidth = 0
        b.cardDot.strokeWidth = 0
        b.cardOdoh.strokeWidth = 0
        b.cardRethinkDns.strokeWidth = 0
        b.initialDoh.color = fetchColor(this, R.attr.primaryTextColor)
        b.abbrDoh.color = fetchColor(this, R.attr.primaryTextColor)
        b.initialDnsproxy.color = fetchColor(this, R.attr.primaryTextColor)
        b.abbrDnsproxy.color = fetchColor(this, R.attr.primaryTextColor)
        b.initialDnscrypt.color = fetchColor(this, R.attr.primaryTextColor)
        b.abbrDnscrypt.color = fetchColor(this, R.attr.primaryTextColor)
        b.initialDot.color = fetchColor(this, R.attr.primaryTextColor)
        b.abbrDot.color = fetchColor(this, R.attr.primaryTextColor)
        b.initialOdoh.color = fetchColor(this, R.attr.primaryTextColor)
        b.abbrOdoh.color = fetchColor(this, R.attr.primaryTextColor)
        b.initialRethinkDns.color = fetchColor(this, R.attr.primaryTextColor)
        b.abbrRethinkDns.color = fetchColor(this, R.attr.primaryTextColor)
    }

    private fun updateSelectedStatus() {
        io {
            // always use the id as Dnsx.Preffered as it is the primary dns id for now
            val id = if (appConfig.isSmartDnsEnabled()) {
                Backend.Plus
            } else {
                Backend.Preferred
            }
            val state = VpnController.getDnsStatus(id)
            val working =
                if (state == null) {
                    false
                } else {
                    when (Transaction.Status.fromId(state)) {
                        Transaction.Status.COMPLETE,
                        Transaction.Status.START -> {
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            uiCtx { highlightSelectedUi(working) }
        }
    }

    private fun highlightSelectedUi(working: Boolean) {
        val strokeColor: Int
        val textColor: Int
        if (working) {
            strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
            textColor = fetchColor(this, R.attr.secondaryTextColor)
        } else {
            strokeColor = UIUtils.fetchToggleBtnColors(this, R.color.accentBad)
            textColor = fetchColor(this, R.attr.accentBad)
        }

        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                b.cardDoh.strokeColor = strokeColor
                b.cardDoh.strokeWidth = 2
                b.initialDoh.color = textColor
                b.abbrDoh.color = textColor
            }
            AppConfig.DnsType.DNS_PROXY -> {
                b.cardDnsproxy.strokeColor = strokeColor
                b.cardDnsproxy.strokeWidth = 2
                b.initialDnsproxy.color = textColor
                b.abbrDnsproxy.color = textColor
            }
            AppConfig.DnsType.DNSCRYPT -> {
                b.cardDnscrypt.strokeColor = strokeColor
                b.cardDnscrypt.strokeWidth = 2
                b.initialDnscrypt.color = textColor
                b.abbrDnscrypt.color = textColor
            }
            AppConfig.DnsType.DOT -> {
                b.cardDot.strokeColor = strokeColor
                b.cardDot.strokeWidth = 2
                b.initialDot.color = textColor
                b.abbrDot.color = textColor
            }
            AppConfig.DnsType.ODOH -> {
                b.cardOdoh.strokeColor = strokeColor
                b.cardOdoh.strokeWidth = 2
                b.initialOdoh.color = textColor
                b.abbrOdoh.color = textColor
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                b.cardRethinkDns.strokeColor = strokeColor
                b.cardRethinkDns.strokeWidth = 2
                b.initialRethinkDns.color = textColor
                b.abbrRethinkDns.color = textColor
            }
            else -> {
                // no-op
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
