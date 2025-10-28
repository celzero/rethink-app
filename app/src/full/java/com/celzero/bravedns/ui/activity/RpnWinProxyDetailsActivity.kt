/*
 * Copyright 2025 RethinkDNS and its authors
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

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityRpnWinProxyDetailBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class RpnWinProxyDetailsActivity: AppCompatActivity(R.layout.activity_rpn_win_proxy_detail) {

    private val b by viewBinding(ActivityRpnWinProxyDetailBinding::bind)
    private val persistentState by inject<PersistentState>()

    private lateinit var cc: String

    companion object {
        const val TAG = "WinDetAct"
        const val COUNTRY_CODE = "country_code"
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        initViews()
        initClickListeners()
    }

    private fun initViews() {
        // read the proxy id from intent, proxy id will have the prefix of ID_RPN_WIN
        cc = intent.getStringExtra(COUNTRY_CODE) ?: ""
        Logger.v(LOG_TAG_UI, "$TAG; initViews: country code from intent: $cc")
        if (!::cc.isInitialized || cc.isEmpty()) {
            // show dialog and finish the activity
            Logger.w(LOG_TAG_UI, "$TAG; empty country code, finishing activity")
            showNoProxyFoundDialog()
            finish()
            return
        }
        // get the apps which are part of this proxy
        // get ips which are part of this proxy
        // get domains which are part of this proxy
        io {
            val apps = ProxyManager.getAppsCountForProxy(cc)
            val ipCount = IpRulesManager.getRulesCountByCC(cc)
            val domainCount = DomainRulesManager.getRulesCountByCC(cc)
            Logger.i(LOG_TAG_UI, "$TAG; initViews: apps: $apps, ips: $ipCount, domains: $domainCount for country code: $cc")
            uiCtx {
                b.frwpAppsCount.text = apps.toString()
                b.frwpDomainsCount.text = domainCount.toString()
                b.frwpIpsCount.text = ipCount.toString()
            }
        }
    }

    private fun initClickListeners() {
        b.openAppSelector.setOnClickListener {
            Utilities.showToastUiCentered(this, "Apps part of other proxy/excluded from proxy will be listed here", Toast.LENGTH_LONG)
        }

    }

    private fun showNoProxyFoundDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle("No proxy found")
        builder.setMessage("Proxy information is missing for this proxy id.Please ensure that the proxy is configured correctly and try again.")
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.ada_noapp_dialog_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            finish()
        }
        builder.create().show()
    }

    private fun io(f: suspend () -> Unit) {
        this.lifecycleScope.launch(Dispatchers.IO) {
            f()
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

}
