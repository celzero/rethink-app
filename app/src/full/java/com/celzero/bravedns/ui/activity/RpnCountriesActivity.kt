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
/*

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RpnCountriesAdapter
import com.celzero.bravedns.databinding.ActivityRpnCountriesBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class RpnCountriesActivity: AppCompatActivity(R.layout.activity_rpn_countries) {
    private val b by viewBinding(ActivityRpnCountriesBinding::bind)
    private val persistentState by inject<PersistentState>()

    //private val proxyCountriesAdapter = ProxyCountriesAdapter()
    private val proxyCountries = mutableListOf<String>()
    private val selectedCountries = mutableSetOf<String>()

    companion object {
        private const val TAG = "RpncUi"
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        io {
            fetchProxyCountries()
            uiCtx {
                showProxyCountries()
            }
        }
    }

    private suspend fun fetchProxyCountries() {
        // TODO: fetch the proxy countries from win config
        val ccs = emptyList<String>()
        Logger.v(LOG_TAG_UI, "$TAG fetch proxy countries: ${ccs.size}")
        proxyCountries.addAll(ccs)
        val selectedCCs = emptyList<String>()//RpnProxyManager.getSelectedCCs()
        Logger.v(LOG_TAG_UI, "$TAG selected countries: ${selectedCCs.size}")
        selectedCountries.addAll(selectedCCs)
        Logger.i(LOG_TAG_UI, "$TAG total cc: ${ccs.size}, selected: ${selectedCCs.size}")
    }

    private fun showProxyCountries() {
        if (proxyCountries.isEmpty()) {
            Logger.v(LOG_TAG_UI, "$TAG no proxy countries available, show err dialog")
            showNoProxyCountriesDialog()
            return
        }
        // show proxy countries
        val lst = proxyCountries.map { it }
        b.rpncList.layoutManager = LinearLayoutManager(this)
        val adapter = RpnCountriesAdapter(this, lst, selectedCountries)
        b.rpncList.adapter = adapter
    }

    private fun showNoProxyCountriesDialog() {
        // show alert dialog with no proxy countries
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("No countries available")
            .setMessage("No countries available for RPN. Please try again later.")
            .setPositiveButton(R.string.dns_info_positive) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
*/
